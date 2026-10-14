package com.volmit.staticstats.stats;

import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.dashboard.DashboardDataPlayer;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.detailed.DetailRankingCache;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.records.PersonalBestSnapshot;
import com.volmit.staticstats.participation.ParticipationCache;
import com.volmit.staticstats.participation.ParticipationQuery;
import com.volmit.staticstats.participation.ParticipationSnapshot;

import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.milestone.MilestoneCompletion;
import com.volmit.staticstats.milestone.MilestoneCompletionEvent;
import com.volmit.staticstats.milestone.MilestoneProgress;
import com.volmit.staticstats.milestone.MilestoneSettings;
import com.volmit.staticstats.season.SeasonCompletionEvent;
import com.volmit.staticstats.season.SeasonContext;
import com.volmit.staticstats.season.SeasonProfileSnapshot;
import com.volmit.staticstats.season.SeasonRankingSnapshot;
import com.volmit.staticstats.season.SeasonSettings;
import com.volmit.staticstats.season.SeasonSnapshot;
import com.volmit.staticstats.tracking.StatsListener;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class StatsService implements StaticStatistics, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<RuntimeConfig> configuration;
    private final StatsStore store;
    private final ConcurrentHashMap<UUID, TrackingContext> contexts = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final AtomicBoolean disconnectSavePending = new AtomicBoolean();
    private final List<Consumer<MilestoneCompletionEvent>> milestoneListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SeasonCompletionEvent>> seasonListeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService writer;
    private volatile RankingCache rankings;
    private volatile DetailRankingCache detailRankings;
    private volatile ParticipationCache participation;
    private StatsListener listener;
    private volatile RuntimeConfig appliedConfiguration;
    private volatile boolean running;
    private volatile long lastSaveAt;
    private volatile long lastSaveAttemptAt;
    private volatile String lastSaveFailure;
    private volatile long lastMilestoneSaveAttempt;

    public StatsService(JavaPlugin plugin, Supplier<RuntimeConfig> configuration) {
        this.plugin = Objects.requireNonNull(plugin);
        this.configuration = Objects.requireNonNull(configuration);
        store = new StatsStore(plugin.getDataFolder().toPath().resolve("storage").resolve("players.json"), Clock.systemUTC());
    }

    public void start() throws IOException {
        lifecycle.writeLock().lock();
        try {
            if (running) {
                return;
            }
            store.configureHistory(configuration.get().historySettings());
            store.configureMilestones(configuration.get().milestoneSettings());
            store.load();
            store.seasonEngine().configure(configuration.get().seasonSettings());
            store.seasonEngine().setEnabled(configuration.get().enabled());
            rankings = RankingCache.create(store::snapshots, store::historyZone, () -> configuration.get().rankingRules(), plugin.getLogger());
            detailRankings = DetailRankingCache.create(store::detailSnapshots, plugin.getLogger());
            participation = ParticipationCache.create(store::participationCapture, store::historySettings, plugin.getLogger());
            appliedConfiguration = configuration.get();
            writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Static-Statistics");
                thread.setDaemon(true);
                return thread;
            });
            running = true;
            lastSaveAttemptAt = System.currentTimeMillis();
            listener = new StatsListener(this);
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            plugin.getServer().getServicesManager().register(StaticStatistics.class, this, plugin, ServicePriority.Normal);
            writer.scheduleWithFixedDelay(this::maintenance, 1, 1, TimeUnit.SECONDS);
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                SchedulerUtils.runEntity(plugin, player, () -> initializeOnlinePlayer(player));
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        RankingCache closingRankings;
        DetailRankingCache closingDetailRankings;
        ParticipationCache closingParticipation;
        lifecycle.writeLock().lock();
        try {
            if (!running) {
                return;
            }
            running = false;
            closingRankings = rankings;
            closingDetailRankings = detailRankings;
            closingParticipation = participation;
            HandlerList.unregisterAll(listener);
            listener.close();
            plugin.getServer().getServicesManager().unregister(StaticStatistics.class, this);
            store.disconnectAll();
            store.seasonEngine().disconnectAll();
            store.seasonEngine().tick();
            contexts.clear();
            writer.execute(this::saveWithLogging);
            writer.shutdown();
        } finally {
            lifecycle.writeLock().unlock();
        }
        closingRankings.close();
        closingDetailRankings.close();
        closingParticipation.close();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("The final statistics write did not finish within 30 seconds and is still running.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.SEVERE, "Interrupted while waiting for statistics to finish saving", exception);
        } finally {
            milestoneListeners.clear();
            seasonListeners.clear();
            store.seasonEngine().close();
        }
    }

    @Override
    public Optional<PlayerStatsSnapshot> snapshot(UUID uuid) {
        return store.snapshot(uuid);
    }

    @Override
    public Optional<DetailSnapshot> details(UUID uuid) {
        return store.details(uuid);
    }

    @Override
    public Optional<DetailRankingSnapshot> cachedDetailRanking(DetailKey counter) {
        Objects.requireNonNull(counter);
        DetailRankingCache current = detailRankings;
        return running && current != null ? current.cachedRanking(counter) : Optional.empty();
    }

    @Override
    public CompletableFuture<DetailRankingSnapshot> detailRanking(DetailKey counter) {
        Objects.requireNonNull(counter);
        DetailRankingCache current = detailRankings;
        return running && current != null ? current.ranking(counter)
                : CompletableFuture.failedFuture(new IllegalStateException("Statistics service is stopped"));
    }

    @Override
    public Optional<PersonalBestSnapshot> personalBests(UUID uuid) {
        return store.personalBests(uuid);
    }

    @Override
    public Optional<ParticipationSnapshot> cachedParticipation(StatsPeriod period, int offset) {
        new ParticipationQuery(period, offset).window(System.currentTimeMillis(), store.historySettings());
        ParticipationCache current = participation;
        return running && current != null ? current.cached(period, offset) : Optional.empty();
    }

    @Override
    public CompletableFuture<ParticipationSnapshot> participation(StatsPeriod period, int offset) {
        new ParticipationQuery(period, offset).window(System.currentTimeMillis(), store.historySettings());
        ParticipationCache current = participation;
        return running && current != null ? current.summary(period, offset)
                : CompletableFuture.failedFuture(new IllegalStateException("Statistics service is stopped"));
    }

    @Override
    public void addDetail(UUID uuid, DetailKey key, long amount) {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(key);
        if (amount < 0) {
            throw new IllegalArgumentException("Detailed counter increments must be nonnegative integers");
        }
        if (amount == 0) {
            return;
        }
        lifecycle.readLock().lock();
        try {
            RuntimeConfig config = configuration.get();
            if (!running || !config.enabled() || !config.detailSettings().records(key)) {
                return;
            }
            TrackingContext context = contexts.get(uuid);
            if (context == null || eligible(context, config)) {
                store.addDetail(uuid, key, amount);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public Optional<PlayerStatsSnapshot> snapshot(UUID uuid, StatsPeriod period) {
        return store.snapshot(uuid, period);
    }

    @Override
    public Optional<PeriodStatsSnapshot> periodSnapshot(UUID uuid, StatsPeriod period) {
        return store.periodSnapshot(uuid, period);
    }

    @Override
    public List<PeriodStatsSnapshot> history(UUID uuid, StatsPeriod period) {
        return store.history(uuid, period);
    }

    @Override
    public List<PlayerSession> sessions(UUID uuid) {
        return store.sessions(uuid);
    }

    @Override
    public ZoneId historyZone() {
        return store.historyZone();
    }

    @Override
    public long activityRecordedSince(UUID uuid) {
        return store.activityRecordedSince(uuid);
    }

    @Override
    public List<MilestoneProgress> milestones(UUID uuid) {
        return store.milestones(uuid);
    }

    @Override
    public List<MilestoneCompletion> milestoneCompletions(UUID uuid) {
        return store.milestoneCompletions(uuid);
    }

    @Override
    public AutoCloseable onMilestoneCompletion(Consumer<MilestoneCompletionEvent> listener) {
        Objects.requireNonNull(listener);
        lifecycle.readLock().lock();
        try {
            if (!running) {
                throw new IllegalStateException("Statistics service is stopped");
            }
            milestoneListeners.add(listener);
            AtomicBoolean subscribed = new AtomicBoolean(true);
            return () -> {
                if (subscribed.compareAndSet(true, false)) {
                    milestoneListeners.remove(listener);
                }
            };
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public List<SeasonSnapshot> seasons() {
        return store.seasonEngine().seasons();
    }

    @Override
    public Optional<SeasonSnapshot> season(String id) {
        return store.seasonEngine().season(id);
    }

    @Override
    public Optional<SeasonProfileSnapshot> seasonSnapshot(UUID uuid, String id) {
        return store.seasonEngine().profile(uuid, id);
    }

    @Override
    public CompletableFuture<SeasonRankingSnapshot> seasonRanking(String id) {
        return store.seasonEngine().ranking(id);
    }

    @Override
    public AutoCloseable onSeasonCompletion(Consumer<SeasonCompletionEvent> listener) {
        Objects.requireNonNull(listener);
        lifecycle.readLock().lock();
        try {
            if (!running) {
                throw new IllegalStateException("Statistics service is stopped");
            }
            seasonListeners.add(listener);
            AtomicBoolean subscribed = new AtomicBoolean(true);
            return () -> {
                if (subscribed.compareAndSet(true, false)) {
                    seasonListeners.remove(listener);
                }
            };
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public Optional<PlayerStatsSnapshot> find(String nameOrUuid) {
        return store.find(nameOrUuid);
    }

    @Override
    public Optional<PlayerStatsSnapshot> find(String nameOrUuid, StatsPeriod period) {
        return store.find(nameOrUuid, period);
    }

    @Override
    public List<PlayerStatsSnapshot> leaderboard(StatKey key, int offset, int limit) {
        Objects.requireNonNull(key);
        if (offset < 0 || limit < 0) {
            throw new IllegalArgumentException("Leaderboard offset and limit cannot be negative");
        }
        Optional<RankingSnapshot> cached = cachedRanking(key);
        if (cached.isEmpty() || limit == 0) {
            return List.of();
        }
        List<RankedPlayer> entries = cached.get().entries();
        int start = Math.min(offset, entries.size());
        int end = (int) Math.min((long) start + limit, entries.size());
        List<PlayerStatsSnapshot> page = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) {
            page.add(entries.get(index).profile());
        }
        return List.copyOf(page);
    }

    @Override
    public Optional<RankingSnapshot> cachedRanking(StatKey key) {
        return cachedRanking(key, StatsPeriod.LIFETIME);
    }

    @Override
    public Optional<RankingSnapshot> cachedRanking(StatKey key, StatsPeriod period) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(period);
        RankingCache current = rankings;
        return running && current != null ? current.cachedRanking(key, period) : Optional.empty();
    }

    @Override
    public CompletableFuture<RankingSnapshot> ranking(StatKey key) {
        return ranking(key, StatsPeriod.LIFETIME);
    }

    @Override
    public CompletableFuture<RankingSnapshot> ranking(StatKey key, StatsPeriod period) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(period);
        RankingCache current = rankings;
        return running && current != null ? current.ranking(key, period)
                : CompletableFuture.failedFuture(new IllegalStateException("Statistics service is stopped"));
    }

    public List<DashboardDataPlayer> dashboardPlayers() {
        return store.dashboardPlayers();
    }

    @Override
    public int playerCount() {
        return store.playerCount();
    }

    @Override
    public void add(UUID uuid, StatKey key, double amount) {
        lifecycle.readLock().lock();
        try {
            RuntimeConfig config = configuration.get();
            if (running && config.enabled()) {
                TrackingContext context = contexts.get(uuid);
                if (context == null || eligible(context, config)) {
                    store.add(uuid, key, amount);
                }
                store.seasonEngine().add(uuid, store.name(uuid), key, amount, seasonContext(context));
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public CompletableFuture<Void> flushAsync() {
        lifecycle.readLock().lock();
        try {
            if (!running) {
                return CompletableFuture.failedFuture(new IllegalStateException("Statistics service is stopped"));
            }
            CompletableFuture<Void> completion = new CompletableFuture<>();
            writer.execute(() -> save(completion));
            return completion;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public void connect(UUID uuid, String name, TrackingContext context) {
        connect(new PlayerConnection(uuid, name, context, true));
    }

    public void quit(UUID uuid) {
        lifecycle.readLock().lock();
        try {
            if (running) {
                RuntimeConfig config = configuration.get();
                TrackingContext context = contexts.get(uuid);
                store.disconnect(uuid, config.enabled() && (context == null || eligible(context, config)));
                store.seasonEngine().disconnect(uuid, true);
                contexts.remove(uuid);
                requestDisconnectSave();
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public void updateContext(UUID uuid, TrackingContext context) {
        lifecycle.readLock().lock();
        try {
            if (running) {
                contexts.compute(uuid, (playerId, previous) -> {
                    RuntimeConfig config = configuration.get();
                    store.setTracking(playerId, config.enabled() && eligible(context, config));
                    store.seasonEngine().context(playerId, seasonContext(context));
                    return context;
                });
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public boolean eligible(UUID uuid) {
        RuntimeConfig config = configuration.get();
        TrackingContext context = contexts.get(uuid);
        return config.enabled() && (context == null || eligible(context, config) || store.seasonEngine().accepts(seasonContext(context)));
    }

    public void activity(UUID uuid) {
        lifecycle.readLock().lock();
        try {
            if (running && eligible(uuid)) {
                store.activity(uuid);
                store.seasonEngine().activity(uuid);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public void recordArrowHit(UUID uuid, StatKey statistic, Set<String> credited) {
        if (statistic != StatKey.ARROW_HITS_DEALT && statistic != StatKey.ARROW_HITS_TAKEN) {
            throw new IllegalArgumentException("Arrow hit credit requires an arrow hit statistic");
        }
        lifecycle.readLock().lock();
        try {
            RuntimeConfig config = configuration.get();
            if (!running || !config.enabled()) {
                return;
            }
            TrackingContext context = contexts.get(uuid);
            if ((context == null || eligible(context, config)) && credited.add("lifetime")) {
                store.add(uuid, statistic, 1);
            }
            store.seasonEngine().arrowHit(uuid, store.name(uuid), statistic, seasonContext(context), credited);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public void validateHistorySettings(HistorySettings candidate) {
        RuntimeConfig applied = appliedConfiguration;
        if (running && applied != null && !applied.historySettings().zone().equals(candidate.zone())) {
            throw new IllegalArgumentException("History time zone is chosen at startup and cannot change while Static is running");
        }
        store.validateHistorySettings(candidate);
    }

    public void validateMilestoneSettings(MilestoneSettings candidate) {
        store.validateMilestoneSettings(candidate);
    }

    public void validateSeasonSettings(SeasonSettings candidate) {
        store.seasonEngine().validate(candidate);
    }

    public synchronized void refreshConfiguration() {
        installConfiguration(configuration.get(), () -> { });
    }

    public synchronized void installConfiguration(RuntimeConfig config, Runnable publish) {
        lifecycle.writeLock().lock();
        try {
            if (!running || config == appliedConfiguration) {
                publish.run();
                return;
            }
            validateHistorySettings(config.historySettings());
            validateMilestoneSettings(config.milestoneSettings());
            if (!config.seasonSettings().equals(appliedConfiguration.seasonSettings())) {
                store.seasonEngine().configure(config.seasonSettings());
            }
            if (!config.historySettings().equals(appliedConfiguration.historySettings())) {
                store.configureHistory(config.historySettings());
            }
            if (!config.milestoneSettings().equals(appliedConfiguration.milestoneSettings())) {
                store.configureMilestones(config.milestoneSettings());
            }
            store.seasonEngine().setEnabled(config.enabled());
            for (Map.Entry<UUID, TrackingContext> entry : contexts.entrySet()) {
                contexts.computeIfPresent(entry.getKey(), (uuid, context) -> {
                    store.setTracking(uuid, config.enabled() && eligible(context, config));
                    return context;
                });
            }
            publish.run();
            appliedConfiguration = config;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    public long lastSaveAt() {
        return lastSaveAt;
    }

    public String lastSaveFailure() {
        return lastSaveFailure;
    }

    private void connect(PlayerConnection connection) {
        lifecycle.readLock().lock();
        try {
            if (!running) {
                return;
            }
            contexts.put(connection.uuid(), connection.context());
            RuntimeConfig config = configuration.get();
            boolean tracking = config.enabled() && eligible(connection.context(), config);
            store.connect(new StatsStore.Connection(connection.uuid(), connection.name(), tracking,
                    connection.countJoin() && tracking));
            store.seasonEngine().connect(connection.uuid(), connection.name(), seasonContext(connection.context()), connection.countJoin());
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private void initializeOnlinePlayer(Player player) {
        if (player.isOnline()) {
            connect(new PlayerConnection(player.getUniqueId(), player.getName(),
                    new TrackingContext(player.getWorld().getName(), player.getGameMode()), false));
        }
    }

    private void maintenance() {
        try {
            if (!running) {
                return;
            }
            refreshConfiguration();
            for (UUID uuid : contexts.keySet()) {
                store.checkpoint(uuid);
            }
            store.seasonEngine().tick();
            long now = System.currentTimeMillis();
            boolean milestoneDue = (store.hasPendingMilestoneWrites() || store.seasonEngine().hasPendingCompletion())
                    && (lastSaveFailure == null || now - lastMilestoneSaveAttempt >= 5_000);
            if (milestoneDue || now - lastSaveAttemptAt >= configuration.get().autosaveSeconds() * 1000L) {
                lastMilestoneSaveAttempt = now;
                saveWithLogging();
            }
            listener.removeExpiredShots();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Statistics maintenance failed", exception);
        }
    }

    private void save(CompletableFuture<Void> completion) {
        try {
            List<MilestoneCompletionEvent> events = store.save();
            lastSaveAt = System.currentTimeMillis();
            lastSaveAttemptAt = lastSaveAt;
            lastSaveFailure = null;
            publishMilestones(events);
            publishSeasons(store.takeSavedSeasonEvents());
            completion.complete(null);
        } catch (IOException | RuntimeException exception) {
            lastSaveAttemptAt = System.currentTimeMillis();
            lastSaveFailure = exception.toString();
            plugin.getLogger().log(Level.SEVERE, "Could not save player statistics to " + store.file(), exception);
            completion.completeExceptionally(exception);
        }
    }

    private void saveWithLogging() {
        save(new CompletableFuture<>());
    }

    private void publishMilestones(List<MilestoneCompletionEvent> events) {
        for (MilestoneCompletionEvent event : events) {
            for (Consumer<MilestoneCompletionEvent> listener : milestoneListeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Milestone completion subscriber failed for " + event.playerId(), exception);
                }
            }
        }
    }

    private void publishSeasons(List<SeasonCompletionEvent> events) {
        for (SeasonCompletionEvent event : events) {
            for (Consumer<SeasonCompletionEvent> listener : seasonListeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Season completion subscriber failed for " + event.snapshot().season().definition().id(), exception);
                }
            }
        }
    }

    private static SeasonContext seasonContext(TrackingContext context) {
        return context == null ? null : new SeasonContext(context.world(), context.gameMode().name());
    }

    private void requestDisconnectSave() {
        if (disconnectSavePending.compareAndSet(false, true)) {
            writer.schedule(() -> {
                disconnectSavePending.set(false);
                saveWithLogging();
            }, 1, TimeUnit.SECONDS);
        }
    }

    private static boolean eligible(TrackingContext context, RuntimeConfig config) {
        return !config.excludedWorlds().contains(context.world())
                && (context.gameMode() != GameMode.CREATIVE || config.trackCreative())
                && (context.gameMode() != GameMode.SPECTATOR || config.trackSpectator());
    }

    public record TrackingContext(String world, GameMode gameMode) {
        public TrackingContext {
            world = Objects.requireNonNull(world).toLowerCase(Locale.ROOT);
            Objects.requireNonNull(gameMode);
        }
    }

    private record PlayerConnection(UUID uuid, String name, TrackingContext context, boolean countJoin) {
    }
}
