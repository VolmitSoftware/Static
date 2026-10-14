package com.volmit.staticstats.season;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankedPlayer;
import com.volmit.staticstats.stats.RankingRules;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SeasonEngine implements AutoCloseable {
    private final Clock clock;
    private final Map<String, Season> seasons = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<CompletableFuture<SeasonRankingSnapshot>> pendingRankings = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Static-Seasons");
        thread.setDaemon(true);
        return thread;
    });
    private boolean enabled = true;
    private boolean closed;

    public SeasonEngine(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void close() {
        List<CompletableFuture<SeasonRankingSnapshot>> pending;
        synchronized (this) {
            closed = true;
            pending = new ArrayList<>(pendingRankings);
            pendingRankings.clear();
        }
        worker.shutdownNow();
        for (CompletableFuture<SeasonRankingSnapshot> completion : pending) {
            completion.completeExceptionally(new IllegalStateException("Season service is stopped"));
        }
    }

    public synchronized void validate(SeasonSettings settings) {
        validate(settings, clock.millis());
    }

    public synchronized void configure(SeasonSettings settings) {
        long now = clock.millis();
        validate(settings, now);
        checkpointAll(now);
        Map<String, SeasonDefinition> candidates = new HashMap<>();
        for (SeasonDefinition definition : settings.events()) {
            candidates.put(definition.id(), definition);
        }
        seasons.entrySet().removeIf(entry -> !entry.getValue().frozen && now < entry.getValue().definition.startsAt().toEpochMilli()
                && !candidates.containsKey(entry.getKey()));
        for (SeasonDefinition definition : settings.events()) {
            Season existing = seasons.get(definition.id());
            if (existing == null || !existing.frozen && now < existing.definition.startsAt().toEpochMilli()) {
                Season replacement = new Season(definition, Math.max(now, definition.startsAt().toEpochMilli()));
                replacement.frozen = now >= definition.startsAt().toEpochMilli();
                seasons.put(definition.id(), replacement);
            }
        }
        tick(now);
    }

    public synchronized void setEnabled(boolean next) {
        long now = clock.millis();
        checkpointAll(now);
        if (!enabled && next) {
            for (Session session : sessions.values()) {
                session.lastActivity = now;
            }
        }
        enabled = next;
    }

    public synchronized boolean accepts(SeasonContext context) {
        long now = clock.millis();
        if (!enabled) {
            return false;
        }
        for (Season season : seasons.values()) {
            if (active(season, now) && season.definition.rules().accepts(context)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void connect(UUID uuid, String name, SeasonContext context, boolean countJoin) {
        long now = clock.millis();
        checkpoint(uuid, now);
        sessions.put(uuid, new Session(name, context, now));
        if (countJoin) {
            add(uuid, name, StatKey.JOINS, 1, context);
        }
    }

    public synchronized void context(UUID uuid, SeasonContext next) {
        long now = clock.millis();
        checkpoint(uuid, now);
        Session session = sessions.get(uuid);
        if (session == null) {
            return;
        }
        for (Season season : seasons.values()) {
            SeasonRules rules = season.definition.rules();
            if (!rules.accepts(session.context) && rules.accepts(next)) {
                session.resumedAt.put(season.definition.id(), now);
            }
        }
        session.context = next;
    }

    public synchronized void activity(UUID uuid) {
        long now = clock.millis();
        checkpoint(uuid, now);
        Session session = sessions.get(uuid);
        if (session != null && enabled) {
            session.lastActivity = Math.max(session.cursor, now);
        }
    }

    public synchronized void disconnect(UUID uuid, boolean countQuit) {
        long now = clock.millis();
        checkpoint(uuid, now);
        Session session = sessions.get(uuid);
        if (session != null && countQuit) {
            add(uuid, session.name, StatKey.QUITS, 1, session.context);
        }
        sessions.remove(uuid);
    }

    public synchronized void disconnectAll() {
        checkpointAll(clock.millis());
        sessions.clear();
    }

    public synchronized void add(UUID uuid, String name, StatKey statistic, double amount, SeasonContext context) {
        if (statistic.derived() || !Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Season increments must be finite, nonnegative base values");
        }
        if (!enabled || amount == 0) {
            return;
        }
        long now = clock.millis();
        checkpoint(uuid, now);
        for (Season season : seasons.values()) {
            if (active(season, now) && season.definition.rules().accepts(context)) {
                season.profile(uuid, name, now).add(statistic, amount, now);
            }
        }
    }

    public synchronized void arrowHit(UUID uuid, String name, StatKey statistic, SeasonContext context, Set<String> credited) {
        long now = clock.millis();
        if (!enabled) {
            return;
        }
        for (Season season : seasons.values()) {
            if (active(season, now) && season.definition.rules().accepts(context) && credited.add("season/" + season.definition.id())) {
                season.profile(uuid, name, now).add(statistic, 1, now);
            }
        }
    }

    public synchronized List<SeasonSnapshot> seasons() {
        List<SeasonSnapshot> result = new ArrayList<>();
        for (Season season : seasons.values()) {
            result.add(snapshot(season));
        }
        result.sort(Comparator.comparing((SeasonSnapshot value) -> value.definition().startsAt()).reversed()
                .thenComparing(value -> value.definition().id()));
        return List.copyOf(result);
    }

    public synchronized Optional<SeasonSnapshot> season(String id) {
        Season season = seasons.get(id);
        return season == null ? Optional.empty() : Optional.of(snapshot(season));
    }

    public synchronized Optional<SeasonProfileSnapshot> profile(UUID uuid, String id) {
        checkpoint(uuid, clock.millis());
        Season season = seasons.get(id);
        if (season == null) {
            return Optional.empty();
        }
        Profile profile = season.profiles.get(uuid);
        return profile == null ? Optional.empty() : Optional.of(new SeasonProfileSnapshot(snapshot(season), profile.snapshot(uuid)));
    }

    public synchronized CompletableFuture<SeasonRankingSnapshot> ranking(String id) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Season service is stopped"));
        }
        tick();
        Season season = seasons.get(id);
        if (season == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown season " + id));
        }
        if (season.cached != null && (season.archivedAt > 0 || clock.millis() >= season.cached.ranking().refreshedAt()
                && clock.millis() - season.cached.ranking().refreshedAt() < 5_000)) {
            return CompletableFuture.completedFuture(season.cached);
        }
        if (season.failure != null && clock.millis() - season.failedAt < 5_000) {
            return CompletableFuture.failedFuture(season.failure);
        }
        if (season.pending == null) {
            requestRanking(season, false);
        }
        return season.pending.copy();
    }

    public synchronized void tick() {
        tick(clock.millis());
    }

    public synchronized boolean hasPendingCompletion() {
        for (Season season : seasons.values()) {
            if (season.notifyPending) {
                return true;
            }
        }
        return false;
    }

    public synchronized Capture capture() {
        checkpointAll(clock.millis());
        JsonArray data = new JsonArray();
        List<SeasonCompletionEvent> completions = new ArrayList<>();
        for (Season season : seasons.values()) {
            data.add(SeasonCodec.encode(season.definition, season.recordedSince, season.archivedAt, season.frozen, profiles(season)));
            if (season.notifyPending && season.cached != null) {
                completions.add(new SeasonCompletionEvent(season.cached, season.recovered));
            }
        }
        return new Capture(data, List.copyOf(completions));
    }

    public synchronized void acknowledge(List<SeasonCompletionEvent> events) {
        for (SeasonCompletionEvent event : events) {
            Season season = seasons.get(event.snapshot().season().definition().id());
            if (season != null && season.cached == event.snapshot()) {
                season.notifyPending = false;
            }
        }
    }

    public synchronized void load(JsonArray data) {
        if (data.size() > SeasonSettings.MAX_SEASONS) {
            throw new IllegalArgumentException("Too many persisted seasons");
        }
        Map<String, Season> loaded = new HashMap<>();
        for (JsonElement element : data) {
            SeasonCodec.Stored stored = SeasonCodec.decode(element.getAsJsonObject());
            Season season = new Season(stored.definition(), stored.recordedSince());
            season.archivedAt = stored.archivedAt();
            season.frozen = stored.frozen();
            season.recovered = season.archivedAt == 0 && clock.millis() >= season.definition.endsAt().toEpochMilli();
            for (PlayerStatsSnapshot profile : stored.profiles()) {
                season.profiles.put(profile.uuid(), new Profile(profile));
            }
            if (season.archivedAt > 0) {
                season.cached = rank(new RankingData(snapshot(season), profiles(season), season.archivedAt));
            }
            if (loaded.put(season.definition.id(), season) != null) {
                throw new IllegalArgumentException("Duplicate persisted season ID");
            }
        }
        new SeasonSettings(loaded.values().stream().map(value -> value.definition).toList());
        seasons.clear();
        seasons.putAll(loaded);
        sessions.clear();
    }

    private void validate(SeasonSettings settings, long now) {
        Map<String, SeasonDefinition> combined = new HashMap<>();
        for (Season existing : seasons.values()) {
            if (existing.frozen || now >= existing.definition.startsAt().toEpochMilli() || existing.archivedAt > 0) {
                combined.put(existing.definition.id(), existing.definition);
            }
        }
        for (SeasonDefinition definition : settings.events()) {
            Season existing = seasons.get(definition.id());
            boolean replacing = existing == null || !existing.frozen && now < existing.definition.startsAt().toEpochMilli();
            if (replacing && definition.endsAt().toEpochMilli() <= now) {
                throw new IllegalArgumentException("A new season cannot end in the past: " + definition.id());
            }
            SeasonDefinition frozen = combined.get(definition.id());
            if (frozen != null && !frozen.equals(definition)) {
                throw new IllegalArgumentException("Started season rules cannot change: " + definition.id());
            }
            combined.put(definition.id(), definition);
        }
        new SeasonSettings(new ArrayList<>(combined.values()));
    }

    private void tick(long now) {
        if (closed) {
            return;
        }
        checkpointAll(now);
        for (Season season : seasons.values()) {
            if (now >= season.definition.endsAt().toEpochMilli() && season.archivedAt == 0 && !season.finalizing
                    && (season.failedAt == 0 || now - season.failedAt >= 5_000)) {
                season.finalizing = true;
                season.cached = null;
                requestRanking(season, true);
            }
        }
    }

    private void checkpointAll(long now) {
        for (Season season : seasons.values()) {
            if (now >= season.definition.startsAt().toEpochMilli()) {
                season.frozen = true;
            }
        }
        for (UUID uuid : sessions.keySet()) {
            checkpoint(uuid, now);
        }
    }

    private void checkpoint(UUID uuid, long now) {
        Session session = sessions.get(uuid);
        if (session == null) {
            return;
        }
        long end = Math.max(session.cursor, now);
        if (enabled && end > session.cursor) {
            for (Season season : seasons.values()) {
                SeasonRules rules = season.definition.rules();
                long start = Math.max(session.cursor, season.recordedSince);
                long until = Math.min(end, season.definition.endsAt().toEpochMilli());
                if (season.archivedAt > 0 || season.finalizing || start >= until || !rules.accepts(session.context)) {
                    continue;
                }
                long activity = Math.max(session.lastActivity, session.resumedAt.getOrDefault(season.definition.id(), 0L));
                long activeEnd = activity > Long.MAX_VALUE - rules.afkMillis() ? Long.MAX_VALUE : activity + rules.afkMillis();
                long split = Math.max(start, Math.min(until, activeEnd));
                Profile profile = season.profile(uuid, session.name, start);
                profile.add(StatKey.ONLINE_TIME, until - start, until);
                profile.add(StatKey.ACTIVE_TIME, split - start, until);
                profile.add(StatKey.AFK_TIME, until - split, until);
            }
        }
        session.cursor = end;
    }

    private void requestRanking(Season season, boolean archive) {
        CompletableFuture<SeasonRankingSnapshot> completion = new CompletableFuture<>();
        pendingRankings.add(completion);
        completion.whenComplete((result, failure) -> {
            synchronized (this) {
                pendingRankings.remove(completion);
            }
        });
        CompletableFuture<SeasonRankingSnapshot> previous = season.pending;
        season.pending = completion;
        worker.execute(() -> finishRanking(season, archive, completion, previous));
    }

    private void finishRanking(Season season, boolean archive, CompletableFuture<SeasonRankingSnapshot> completion,
                               CompletableFuture<SeasonRankingSnapshot> previous) {
        try {
            RankingData data;
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Season service is stopped");
                }
                checkpointAll(clock.millis());
                long completedAt = archive ? clock.millis() : season.archivedAt;
                SeasonSnapshot metadata = new SeasonSnapshot(season.definition, archive ? SeasonStatus.ARCHIVED : snapshot(season).status(),
                        season.recordedSince, completedAt, season.profiles.size());
                data = new RankingData(metadata, profiles(season), clock.millis());
            }
            SeasonRankingSnapshot ranked = rank(data);
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Season service is stopped");
                }
                if (!archive && season.finalizing && season.pending != completion) {
                    season.pending.whenComplete((latest, failure) -> {
                        if (failure == null) {
                            completion.complete(latest);
                        } else {
                            completion.completeExceptionally(failure);
                        }
                    });
                    return;
                }
                if (archive) {
                    season.archivedAt = data.season().completedAt();
                    season.notifyPending = true;
                }
                if (archive || !season.finalizing) {
                    season.cached = ranked;
                }
                season.failure = null;
                if (season.pending == completion) {
                    season.pending = null;
                }
            }
            completion.complete(ranked);
            if (previous != null && !previous.isDone()) {
                previous.complete(ranked);
            }
        } catch (RuntimeException exception) {
            synchronized (this) {
                if (season.pending == completion) {
                    season.pending = null;
                }
                if (archive) {
                    season.finalizing = false;
                }
                season.failedAt = clock.millis();
                season.failure = exception;
            }
            completion.completeExceptionally(exception);
            if (previous != null) {
                previous.completeExceptionally(exception);
            }
            Logger.getLogger("Static").log(Level.SEVERE, "Could not build ranking for season " + season.definition.id(), exception);
        }
    }

    private SeasonSnapshot snapshot(Season season) {
        SeasonStatus status = season.archivedAt > 0 ? SeasonStatus.ARCHIVED
                : !season.frozen && clock.millis() < season.definition.startsAt().toEpochMilli() ? SeasonStatus.SCHEDULED : SeasonStatus.ACTIVE;
        return new SeasonSnapshot(season.definition, status, status == SeasonStatus.SCHEDULED ? 0 : season.recordedSince,
                season.archivedAt, season.profiles.size());
    }

    private static boolean active(Season season, long now) {
        boolean active = season.archivedAt == 0 && !season.finalizing && now >= season.recordedSince && now < season.definition.endsAt().toEpochMilli();
        if (active) {
            season.frozen = true;
        }
        return active;
    }

    private static List<PlayerStatsSnapshot> profiles(Season season) {
        List<PlayerStatsSnapshot> profiles = new ArrayList<>(season.profiles.size());
        for (Map.Entry<UUID, Profile> entry : season.profiles.entrySet()) {
            profiles.add(entry.getValue().snapshot(entry.getKey()));
        }
        return List.copyOf(profiles);
    }

    private static SeasonRankingSnapshot rank(RankingData data) {
        SeasonRules rules = data.season().definition().rules();
        RankingSnapshot ranking = RankingSnapshot.create(rules.statistic(), data.refreshedAt(), data.profiles(), rules.rankingRules(),
                StatsPeriod.LIFETIME.window(data.refreshedAt(), ZoneOffset.UTC), ZoneOffset.UTC);
        List<RankedPlayer> winners = new ArrayList<>();
        if (data.season().status() == SeasonStatus.ARCHIVED) {
            for (RankedPlayer entry : ranking.entries()) {
                if (entry.rank() != 1) {
                    break;
                }
                winners.add(entry);
            }
        }
        return new SeasonRankingSnapshot(data.season(), ranking, winners);
    }

    public record Capture(JsonArray data, List<SeasonCompletionEvent> events) { }

    private record RankingData(SeasonSnapshot season, List<PlayerStatsSnapshot> profiles, long refreshedAt) { }

    private static final class Season {
        private final SeasonDefinition definition;
        private final long recordedSince;
        private final Map<UUID, Profile> profiles = new HashMap<>();
        private long archivedAt;
        private boolean finalizing;
        private boolean frozen;
        private boolean notifyPending;
        private boolean recovered;
        private long failedAt;
        private RuntimeException failure;
        private SeasonRankingSnapshot cached;
        private CompletableFuture<SeasonRankingSnapshot> pending;

        private Season(SeasonDefinition definition, long recordedSince) {
            this.definition = definition;
            this.recordedSince = recordedSince;
        }

        private Profile profile(UUID uuid, String name, long now) {
            Profile profile = profiles.computeIfAbsent(uuid, ignored -> new Profile(new PlayerStatsSnapshot(uuid, name, Map.of(), now, now)));
            profile.name = name;
            return profile;
        }
    }

    private static final class Profile {
        private final Map<StatKey, Double> values = new EnumMap<>(StatKey.class);
        private final long recordedSince;
        private String name;
        private long updatedAt;

        private Profile(PlayerStatsSnapshot profile) {
            values.putAll(profile.values());
            recordedSince = profile.recordedSince();
            name = profile.name();
            updatedAt = profile.updatedAt();
        }

        private void add(StatKey key, double amount, long now) {
            if (amount == 0) {
                return;
            }
            double total = values.getOrDefault(key, 0D) + amount;
            if (!Double.isFinite(total)) {
                throw new IllegalArgumentException("Season statistic overflow for " + key.id());
            }
            values.put(key, total);
            updatedAt = Math.max(updatedAt, now);
        }

        private PlayerStatsSnapshot snapshot(UUID uuid) {
            return new PlayerStatsSnapshot(uuid, name, values, updatedAt, recordedSince);
        }
    }

    private static final class Session {
        private final String name;
        private final Map<String, Long> resumedAt = new HashMap<>();
        private SeasonContext context;
        private long cursor;
        private long lastActivity;

        private Session(String name, SeasonContext context, long now) {
            this.name = name;
            this.context = context;
            cursor = now;
            lastActivity = now;
        }
    }
}
