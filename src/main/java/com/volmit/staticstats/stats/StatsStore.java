package com.volmit.staticstats.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.volmit.staticstats.dashboard.DashboardDataPlayer;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailLedger;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.records.PersonalBestSnapshot;
import com.volmit.staticstats.participation.ParticipationCapture;
import com.volmit.staticstats.participation.ParticipationProfile;
import com.volmit.staticstats.milestone.MilestoneCompletion;
import com.volmit.staticstats.milestone.MilestoneCompletionEvent;
import com.volmit.staticstats.milestone.MilestoneDefinition;
import com.volmit.staticstats.milestone.MilestoneLedger;
import com.volmit.staticstats.milestone.MilestoneProgress;
import com.volmit.staticstats.milestone.MilestoneSettings;
import com.volmit.staticstats.season.SeasonCompletionEvent;
import com.volmit.staticstats.season.SeasonEngine;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

public final class StatsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Clock clock;
    private final SeasonEngine seasons;
    private final ConcurrentLinkedQueue<SeasonCompletionEvent> savedSeasonEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<UUID, PlayerRecord> players = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock historyLock = new ReentrantReadWriteLock();
    private volatile HistorySettings historySettings = HistorySettings.defaults();
    private volatile MilestoneSettings milestoneSettings = new MilestoneSettings(false, Map.of());
    private final Set<MilestoneDefinition> milestoneDefinitions = new HashSet<>();
    private final AtomicInteger pendingMilestoneWrites = new AtomicInteger();
    private volatile boolean historyExists;

    public StatsStore(Path file, Clock clock) {
        this.file = Objects.requireNonNull(file).toAbsolutePath();
        this.clock = Objects.requireNonNull(clock);
        seasons = new SeasonEngine(clock);
    }

    public void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Map<UUID, PlayerRecord> loaded = new HashMap<>();
        Set<MilestoneDefinition> loadedDefinitions = new HashSet<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported statistics format version");
            }
            boolean persistedHistory = root.has("history-zone");
            if (root.has("seasons")) {
                seasons.load(root.getAsJsonArray("seasons"));
            }
            if (root.has("milestone-definitions")) {
                for (JsonElement element : root.getAsJsonArray("milestone-definitions")) {
                    JsonObject item = element.getAsJsonObject();
                    MilestoneDefinition definition = new MilestoneDefinition(StatKey.parse(item.get("statistic").getAsString()),
                            item.get("threshold").getAsDouble());
                    if (!loadedDefinitions.add(definition) || loadedDefinitions.size() > MilestoneSettings.MAX_RECORDED_DEFINITIONS) {
                        throw new IllegalArgumentException("Invalid milestone definition registry");
                    }
                }
            }
            if (persistedHistory && !ZoneId.of(root.get("history-zone").getAsString()).normalized().equals(historySettings.zone().normalized())) {
                throw new IllegalArgumentException("Configured history zone differs from the persisted history zone");
            }
            for (JsonElement element : root.getAsJsonArray("players")) {
                JsonObject data = element.getAsJsonObject();
                PlayerStatsSnapshot snapshot = decode(data);
                PlayerRecord record = new PlayerRecord(snapshot);
                record.history.unknownConnectionHistory();
                if (data.has("details")) {
                    record.details.load(data.getAsJsonObject("details"));
                }
                if (data.has("milestones")) {
                    record.milestones.load(data.getAsJsonArray("milestones"));
                    for (MilestoneCompletion completion : record.milestones.completions()) {
                        if (!loadedDefinitions.contains(completion.definition())) {
                            throw new IllegalArgumentException("Milestone completion has no registered definition");
                        }
                    }
                }
                if (data.has("history")) {
                    if (!persistedHistory) {
                        throw new IllegalArgumentException("Activity history has no persisted time zone");
                    }
                    record.history = PlayerHistory.decode(data.getAsJsonObject("history"), historySettings, clock.millis());
                }
                if (loaded.put(snapshot.uuid(), record) != null) {
                    throw new IllegalArgumentException("Duplicate player record " + snapshot.uuid());
                }
            }
            historyExists = persistedHistory;
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException | NullPointerException | DateTimeException exception) {
            throw new IOException("Cannot read statistics from " + file, exception);
        }
        players.clear();
        players.putAll(loaded);
        milestoneDefinitions.clear();
        milestoneDefinitions.addAll(loadedDefinitions);
        pendingMilestoneWrites.set(0);
        try {
            configureMilestones(milestoneSettings);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Cannot configure milestones from " + file, exception);
        }
    }

    public void join(UUID uuid, String name) {
        connect(new Connection(uuid, name, true, true));
    }

    public void connect(Connection connection) {
        historyLock.readLock().lock();
        try {
            long now = clock.millis();
            PlayerRecord record = players.computeIfAbsent(connection.uuid(),
                    ignored -> createRecord(new PlayerStatsSnapshot(connection.uuid(), connection.name(), Map.of(), now, now)));
            synchronized (record) {
                record.name = connection.name();
                record.checkpoint(now, historySettings);
                if (record.history.online()) {
                    record.history.tracking(connection.tracking(), now);
                    return;
                }
                record.history.connect(connection.tracking(), now, historySettings);
                historyExists = true;
                record.updatedAt = Math.max(record.updatedAt, now);
                if (connection.countJoin()) {
                    record.add(StatKey.JOINS, 1, now, historySettings);
                }
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public void setTracking(UUID uuid, boolean tracking) {
        historyLock.readLock().lock();
        try {
            PlayerRecord record = players.get(uuid);
            if (record == null) {
                return;
            }
            synchronized (record) {
                long now = clock.millis();
                record.checkpoint(now, historySettings);
                record.history.tracking(tracking, now);
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public void quit(UUID uuid) {
        disconnect(uuid, true);
    }

    public void disconnect(UUID uuid, boolean countQuit) {
        disconnect(uuid, countQuit, SessionEndReason.QUIT);
    }

    public void disconnectAll() {
        for (UUID uuid : players.keySet()) {
            disconnect(uuid, false, SessionEndReason.SHUTDOWN);
        }
    }

    public void add(UUID uuid, StatKey key, double amount) {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(key);
        if (key.derived() || !Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Statistics increments must be finite, nonnegative base values");
        }
        if (amount == 0) {
            return;
        }
        historyLock.readLock().lock();
        try {
            long now = clock.millis();
            PlayerRecord record = players.computeIfAbsent(uuid,
                    ignored -> createRecord(new PlayerStatsSnapshot(uuid, uuid.toString(), Map.of(), now, now)));
            synchronized (record) {
                record.checkpoint(now, historySettings);
                record.add(key, amount, now, historySettings);
                historyExists = true;
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public Optional<PlayerStatsSnapshot> snapshot(UUID uuid) {
        return snapshot(uuid, StatsPeriod.LIFETIME);
    }

    public Optional<DetailSnapshot> details(UUID uuid) {
        Objects.requireNonNull(uuid);
        historyLock.readLock().lock();
        try {
            PlayerRecord record = players.get(uuid);
            if (record == null) {
                return Optional.empty();
            }
            synchronized (record) {
                return Optional.of(record.details.snapshot(uuid, record.name));
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public List<DetailSnapshot> detailSnapshots() {
        historyLock.readLock().lock();
        try {
            List<DetailSnapshot> result = new ArrayList<>(players.size());
            for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
                PlayerRecord record = entry.getValue();
                synchronized (record) {
                    result.add(record.details.snapshot(entry.getKey(), record.name));
                }
            }
            return List.copyOf(result);
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public ParticipationCapture participationCapture() {
        historyLock.readLock().lock();
        try {
            long now = clock.millis();
            List<ParticipationProfile> profiles = new ArrayList<>(players.size());
            for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
                PlayerRecord record = entry.getValue();
                synchronized (record) {
                    record.checkpoint(now, historySettings);
                    profiles.add(record.history.participation(entry.getKey()));
                }
            }
            return new ParticipationCapture(now, historySettings, profiles);
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public HistorySettings historySettings() {
        return historySettings;
    }

    public void addDetail(UUID uuid, DetailKey key, long amount) {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(key);
        if (amount < 0) {
            throw new IllegalArgumentException("Detailed counter increments must be nonnegative integers");
        }
        if (amount == 0) {
            return;
        }
        historyLock.readLock().lock();
        try {
            long now = clock.millis();
            if (now <= 0) {
                throw new IllegalArgumentException("Detailed counter observation time must be positive");
            }
            PlayerRecord record = players.computeIfAbsent(uuid,
                    ignored -> createRecord(new PlayerStatsSnapshot(uuid, uuid.toString(), Map.of(), now, now)));
            synchronized (record) {
                record.details.add(key, amount, now);
                record.updatedAt = Math.max(record.updatedAt, now);
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public Optional<PlayerStatsSnapshot> snapshot(UUID uuid, StatsPeriod period) {
        return periodSnapshot(uuid, period).map(PeriodStatsSnapshot::profile);
    }

    public Optional<PeriodStatsSnapshot> periodSnapshot(UUID uuid, StatsPeriod period) {
        Objects.requireNonNull(period);
        historyLock.readLock().lock();
        try {
            PlayerRecord record = players.get(uuid);
            if (record == null) {
                return Optional.empty();
            }
            synchronized (record) {
                long now = clock.millis();
                return Optional.of(record.periodSnapshot(uuid, period.window(now, historySettings.zone()), now, historySettings));
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public Optional<PlayerStatsSnapshot> find(String nameOrUuid) {
        return find(nameOrUuid, StatsPeriod.LIFETIME);
    }

    public Optional<PlayerStatsSnapshot> find(String nameOrUuid, StatsPeriod period) {
        String query = Objects.requireNonNull(nameOrUuid).trim();
        try {
            return snapshot(UUID.fromString(query), period);
        } catch (IllegalArgumentException ignored) {
            return findByName(query, period);
        }
    }

    public List<DashboardDataPlayer> dashboardPlayers() {
        return readLocked(() -> {
            List<DashboardDataPlayer> result = new ArrayList<>(players.size());
            for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
                PlayerRecord record = entry.getValue();
                synchronized (record) {
                    result.add(new DashboardDataPlayer(entry.getKey(), record.name, record.updatedAt, record.recordedSince));
                }
            }
            return List.copyOf(result);
        });
    }

    public List<PlayerStatsSnapshot> snapshots() {
        return snapshots(StatsPeriod.LIFETIME.window(clock.millis(), historySettings.zone()));
    }

    public List<PlayerStatsSnapshot> snapshots(PeriodWindow window) {
        historyLock.readLock().lock();
        try {
            List<PlayerStatsSnapshot> result = new ArrayList<>(players.size());
            long now = clock.millis();
            for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
                synchronized (entry.getValue()) {
                    result.add(entry.getValue().periodSnapshot(entry.getKey(), window, now, historySettings).profile());
                }
            }
            return result;
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public List<PeriodStatsSnapshot> history(UUID uuid, StatsPeriod period) {
        Objects.requireNonNull(period);
        if (period == StatsPeriod.LIFETIME) {
            return periodSnapshot(uuid, period).map(List::of).orElseGet(List::of);
        }
        return readLocked(() -> {
            PlayerRecord record = players.get(uuid);
            if (record == null) {
                return List.of();
            }
            synchronized (record) {
                return record.history.snapshots(record.snapshot(uuid, clock.millis(), historySettings), period, historySettings);
            }
        });
    }

    public List<PlayerSession> sessions(UUID uuid) {
        return readLocked(() -> {
            PlayerRecord record = players.get(uuid);
            if (record == null) {
                return List.of();
            }
            synchronized (record) {
                record.checkpoint(clock.millis(), historySettings);
                return record.history.sessions();
            }
        });
    }

    public Optional<PersonalBestSnapshot> personalBests(UUID uuid) {
        Objects.requireNonNull(uuid);
        return readLocked(() -> {
            PlayerRecord record = players.get(uuid);
            if (record == null) {
                return Optional.empty();
            }
            synchronized (record) {
                long now = clock.millis();
                record.checkpoint(now, historySettings);
                return Optional.of(record.history.personalBests(uuid, record.name, historySettings, now));
            }
        });
    }

    public void activity(UUID uuid) {
        readLocked(() -> {
            PlayerRecord record = players.get(uuid);
            if (record != null) {
                synchronized (record) {
                    long now = clock.millis();
                    record.checkpoint(now, historySettings);
                    record.history.activity(now);
                }
            }
            return null;
        });
    }

    public long activityRecordedSince(UUID uuid) {
        PlayerRecord record = players.get(uuid);
        if (record == null) {
            return 0;
        }
        synchronized (record) {
            return record.history.recordedSince();
        }
    }

    public ZoneId historyZone() {
        return historySettings.zone();
    }

    public void validateHistorySettings(HistorySettings candidate) {
        Objects.requireNonNull(candidate);
        if (historyExists && !historySettings.zone().normalized().equals(candidate.zone().normalized())) {
            throw new IllegalArgumentException("History time zone is fixed after activity history is recorded");
        }
    }

    public void configureHistory(HistorySettings candidate) {
        historyLock.writeLock().lock();
        try {
            validateHistorySettings(candidate);
            long now = clock.millis();
            for (PlayerRecord record : players.values()) {
                synchronized (record) {
                    record.checkpoint(now, historySettings);
                }
            }
            historySettings = candidate;
            for (PlayerRecord record : players.values()) {
                synchronized (record) {
                    record.checkpoint(now, historySettings);
                }
            }
        } finally {
            historyLock.writeLock().unlock();
        }
    }

    public int playerCount() {
        return players.size();
    }

    String name(UUID uuid) {
        PlayerRecord record = players.get(uuid);
        if (record == null) {
            return uuid.toString();
        }
        synchronized (record) {
            return record.name;
        }
    }

    public void validateMilestoneSettings(MilestoneSettings candidate) {
        Objects.requireNonNull(candidate);
        historyLock.readLock().lock();
        try {
            Set<MilestoneDefinition> combined = new HashSet<>(milestoneDefinitions);
            combined.addAll(candidate.definitions());
            if (combined.size() > MilestoneSettings.MAX_RECORDED_DEFINITIONS) {
                throw new IllegalArgumentException("Statistics storage supports at most 4096 distinct milestone definitions across configuration changes");
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public void configureMilestones(MilestoneSettings settings) {
        historyLock.writeLock().lock();
        try {
            validateMilestoneSettings(settings);
            long now = clock.millis();
            for (PlayerRecord record : players.values()) {
                synchronized (record) {
                    record.checkpoint(now, historySettings);
                }
            }
            milestoneDefinitions.addAll(settings.definitions());
            milestoneSettings = settings;
            for (PlayerRecord record : players.values()) {
                synchronized (record) {
                    record.milestones.configure(settings, record.values, now);
                }
            }
        } finally {
            historyLock.writeLock().unlock();
        }
    }

    public List<MilestoneProgress> milestones(UUID uuid) {
        historyLock.readLock().lock();
        try {
            PlayerRecord record = players.get(uuid);
            if (record == null) {
                return List.of();
            }
            synchronized (record) {
                record.checkpoint(clock.millis(), historySettings);
                return record.milestones.progress(record.values);
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public List<MilestoneCompletion> milestoneCompletions(UUID uuid) {
        historyLock.readLock().lock();
        try {
            PlayerRecord record = players.get(uuid);
            if (record == null) {
                return List.of();
            }
            synchronized (record) {
                record.checkpoint(clock.millis(), historySettings);
                return record.milestones.completions();
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public void checkpoint(UUID uuid) {
        historyLock.readLock().lock();
        try {
            PlayerRecord record = players.get(uuid);
            if (record != null) {
                synchronized (record) {
                    record.checkpoint(clock.millis(), historySettings);
                }
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    public boolean hasPendingMilestoneWrites() {
        return pendingMilestoneWrites.get() > 0;
    }

    public Path file() {
        return file;
    }

    SeasonEngine seasonEngine() {
        return seasons;
    }

    List<SeasonCompletionEvent> takeSavedSeasonEvents() {
        List<SeasonCompletionEvent> events = new ArrayList<>();
        SeasonCompletionEvent event;
        while ((event = savedSeasonEvents.poll()) != null) {
            events.add(event);
        }
        return List.copyOf(events);
    }

    public synchronized List<MilestoneCompletionEvent> save() throws IOException {
        PersistenceCapture capture = readLocked(this::capturePersistent);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "players-", ".tmp");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(GSON.toJson(capture.root()));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        for (Map.Entry<PlayerRecord, List<MilestoneLedger.Notice>> entry : capture.pending().entrySet()) {
            synchronized (entry.getKey()) {
                pendingMilestoneWrites.addAndGet(-entry.getKey().milestones.acknowledge(entry.getValue()));
            }
        }
        seasons.acknowledge(capture.seasons().events());
        savedSeasonEvents.addAll(capture.seasons().events());
        return capture.events();
    }

    private void disconnect(UUID uuid, boolean countQuit, SessionEndReason reason) {
        historyLock.readLock().lock();
        try {
            PlayerRecord record = players.get(uuid);
            if (record == null) {
                return;
            }
            synchronized (record) {
                if (!record.history.online()) {
                    return;
                }
                long now = clock.millis();
                record.checkpoint(now, historySettings);
                record.history.disconnect(reason, historySettings);
                if (countQuit) {
                    record.add(StatKey.QUITS, 1, now, historySettings);
                }
            }
        } finally {
            historyLock.readLock().unlock();
        }
    }

    private Optional<PlayerStatsSnapshot> findByName(String query, StatsPeriod period) {
        historyLock.readLock().lock();
        try {
            UUID newest = null;
            long newestUpdate = Long.MIN_VALUE;
            long now = clock.millis();
            for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
                PlayerRecord candidate = entry.getValue();
                synchronized (candidate) {
                    if (!candidate.name.equalsIgnoreCase(query)) {
                        continue;
                    }
                    candidate.checkpoint(now, historySettings);
                    if (newest == null || candidate.updatedAt > newestUpdate
                            || candidate.updatedAt == newestUpdate && entry.getKey().compareTo(newest) < 0) {
                        newest = entry.getKey();
                        newestUpdate = candidate.updatedAt;
                    }
                }
            }
            return newest == null ? Optional.empty() : snapshot(newest, period);
        } finally {
            historyLock.readLock().unlock();
        }
    }

    private PersistenceCapture capturePersistent() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray registered = new JsonArray();
        List<MilestoneDefinition> ordered = new ArrayList<>(milestoneDefinitions);
        ordered.sort(Comparator.comparing(MilestoneDefinition::statistic).thenComparingDouble(MilestoneDefinition::threshold));
        for (MilestoneDefinition definition : ordered) {
            JsonObject item = new JsonObject();
            item.addProperty("statistic", definition.statistic().id());
            item.addProperty("threshold", definition.threshold());
            registered.add(item);
        }
        root.add("milestone-definitions", registered);
        Map<PlayerRecord, List<MilestoneLedger.Notice>> pending = new HashMap<>();
        List<MilestoneCompletionEvent> events = new ArrayList<>();
        JsonArray records = new JsonArray();
        List<UUID> ids = new ArrayList<>(players.keySet());
        ids.sort(Comparator.naturalOrder());
        long now = clock.millis();
        for (UUID uuid : ids) {
            PlayerRecord record = players.get(uuid);
            synchronized (record) {
                JsonObject data = encode(record.snapshot(uuid, now, historySettings));
                data.add("details", record.details.encode());
                data.add("milestones", record.milestones.encode());
                List<MilestoneLedger.Notice> completions = record.milestones.pending();
                if (!completions.isEmpty()) {
                    pending.put(record, completions);
                    for (MilestoneLedger.Notice notice : completions) {
                        events.add(new MilestoneCompletionEvent(uuid, record.name, notice.completion(), notice.sessionId()));
                    }
                }
                if (record.history.recordedSince() > 0) {
                    data.add("history", record.history.encode());
                    root.addProperty("history-zone", historySettings.zone().getId());
                }
                records.add(data);
            }
        }
        root.add("players", records);
        SeasonEngine.Capture seasonCapture = seasons.capture();
        root.add("seasons", seasonCapture.data());
        return new PersistenceCapture(root, Map.copyOf(pending), List.copyOf(events), seasonCapture);
    }

    private PlayerRecord createRecord(PlayerStatsSnapshot snapshot) {
        PlayerRecord record = new PlayerRecord(snapshot);
        record.milestones.configure(milestoneSettings, record.values, clock.millis());
        return record;
    }

    private <T> T readLocked(Supplier<T> operation) {
        historyLock.readLock().lock();
        try {
            return operation.get();
        } finally {
            historyLock.readLock().unlock();
        }
    }

    private static PlayerStatsSnapshot decode(JsonObject data) {
        UUID uuid = UUID.fromString(data.get("uuid").getAsString());
        String name = data.get("name").getAsString();
        long updatedAt = data.get("updated-at").getAsLong();
        long recordedSince = data.has("recorded-since") ? data.get("recorded-since").getAsLong() : 0;
        EnumMap<StatKey, Double> values = new EnumMap<>(StatKey.class);
        for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject("values").entrySet()) {
            values.put(StatKey.parse(entry.getKey()), entry.getValue().getAsDouble());
        }
        return new PlayerStatsSnapshot(uuid, name, values, updatedAt, recordedSince);
    }

    private static JsonObject encode(PlayerStatsSnapshot snapshot) {
        JsonObject data = new JsonObject();
        data.addProperty("uuid", snapshot.uuid().toString());
        data.addProperty("name", snapshot.name());
        data.addProperty("updated-at", snapshot.updatedAt());
        data.addProperty("recorded-since", snapshot.recordedSince());
        JsonObject values = new JsonObject();
        for (Map.Entry<StatKey, Double> entry : snapshot.values().entrySet()) {
            values.addProperty(entry.getKey().id(), entry.getValue());
        }
        data.add("values", values);
        return data;
    }

    public record Connection(UUID uuid, String name, boolean tracking, boolean countJoin) {
        public Connection {
            Objects.requireNonNull(uuid);
            Objects.requireNonNull(name);
        }
    }

    private final class PlayerRecord {
        private final EnumMap<StatKey, Double> values = new EnumMap<>(StatKey.class);
        private final long recordedSince;
        private String name;
        private long updatedAt;
        private PlayerHistory history = new PlayerHistory();
        private final MilestoneLedger milestones = new MilestoneLedger();
        private final DetailLedger details = new DetailLedger();

        private PlayerRecord(PlayerStatsSnapshot snapshot) {
            name = snapshot.name();
            recordedSince = snapshot.recordedSince();
            updatedAt = snapshot.updatedAt();
            values.putAll(snapshot.values());
        }

        private void add(StatKey key, double amount, long now, HistorySettings settings) {
            increment(key, amount, now);
            history.add(key, amount, now, settings);
        }

        private void increment(StatKey key, double amount, long now) {
            if (amount == 0) {
                return;
            }
            double total = values.getOrDefault(key, 0D) + amount;
            if (!Double.isFinite(total)) {
                throw new IllegalArgumentException("Statistic overflow for " + key.id());
            }
            values.put(key, total);
            updatedAt = Math.max(updatedAt, now);
            int earned = milestones.observe(key, total, now, history.sessionId());
            if (earned > 0) {
                pendingMilestoneWrites.addAndGet(earned);
            }
        }

        private void checkpoint(long now, HistorySettings settings) {
            PlayerHistory.Durations elapsed = history.checkpoint(now, settings);
            increment(StatKey.ONLINE_TIME, elapsed.active() + elapsed.afk(), now);
            increment(StatKey.ACTIVE_TIME, elapsed.active(), now);
            increment(StatKey.AFK_TIME, elapsed.afk(), now);
        }

        private PlayerStatsSnapshot snapshot(UUID uuid, long now, HistorySettings settings) {
            checkpoint(now, settings);
            return new PlayerStatsSnapshot(uuid, name, values, updatedAt, recordedSince);
        }

        private PeriodStatsSnapshot periodSnapshot(UUID uuid, PeriodWindow window, long now, HistorySettings settings) {
            PlayerStatsSnapshot lifetime = snapshot(uuid, now, settings);
            return window.period() == StatsPeriod.LIFETIME
                    ? new PeriodStatsSnapshot(window, lifetime, recordedSince, settings.zone())
                    : history.snapshot(lifetime, window, settings);
        }
    }

    private record PersistenceCapture(JsonObject root, Map<PlayerRecord, List<MilestoneLedger.Notice>> pending,
                                      List<MilestoneCompletionEvent> events, SeasonEngine.Capture seasons) { }
}
