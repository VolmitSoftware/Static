package com.volmit.staticstats.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.volmit.staticstats.records.PersonalBestLedger;
import com.volmit.staticstats.records.PersonalBestSnapshot;
import com.volmit.staticstats.participation.ParticipationProfile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

final class PlayerHistory {
    private static final List<StatsPeriod> PERIODS = List.of(StatsPeriod.DAY, StatsPeriod.WEEK, StatsPeriod.MONTH);

    private final Map<StatsPeriod, NavigableMap<Long, Bucket>> buckets = new EnumMap<>(StatsPeriod.class);
    private final Deque<PlayerSession> sessions = new ArrayDeque<>();
    private final Map<StatsPeriod, PeriodWindow> currentWindows = new EnumMap<>(StatsPeriod.class);
    private final Map<StatsPeriod, Long> cutoffs = new EnumMap<>(StatsPeriod.class);
    private final PersonalBestLedger personalBests = new PersonalBestLedger();
    private HistorySettings calendarSettings;
    private PeriodWindow calendarDay;
    private long recordedSince;
    private long checkpointAt;
    private long lastActivity;
    private boolean online;
    private boolean tracking;
    private Session session;
    private long firstConnectionAt = -1;
    private boolean firstConnectionKnown = true;
    private boolean sessionRetentionKnown = true;
    private long discardedSessionThrough = -1;

    PlayerHistory() {
        for (StatsPeriod period : PERIODS) {
            buckets.put(period, new TreeMap<>());
        }
    }

    static PlayerHistory decode(JsonObject data, HistorySettings settings, long now) {
        PlayerHistory history = new PlayerHistory();
        history.recordedSince = data.get("recorded-since").getAsLong();
        if (history.recordedSince < 0) {
            throw new IllegalArgumentException("Invalid activity history coverage");
        }
        for (JsonElement element : data.getAsJsonArray("periods")) {
            JsonObject entry = element.getAsJsonObject();
            StatsPeriod period = StatsPeriod.parse(entry.get("period").getAsString());
            long start = entry.get("started-at").getAsLong();
            PeriodWindow window = period.window(start, settings.zone());
            if (period == StatsPeriod.LIFETIME || window.startedAt() != start) {
                throw new IllegalArgumentException("Invalid history period start");
            }
            Bucket bucket = new Bucket(window, entry.get("recorded-since").getAsLong());
            bucket.updatedAt = entry.get("updated-at").getAsLong();
            if (bucket.recordedSince < start || bucket.recordedSince >= window.endsAt()
                    || bucket.updatedAt < bucket.recordedSince || bucket.updatedAt > window.endsAt()) {
                throw new IllegalArgumentException("Invalid history period coverage");
            }
            for (Map.Entry<String, JsonElement> value : entry.getAsJsonObject("values").entrySet()) {
                StatKey key = StatKey.parse(value.getKey());
                double amount = value.getValue().getAsDouble();
                if (key.derived() || !Double.isFinite(amount) || amount < 0) {
                    throw new IllegalArgumentException("Invalid period statistic");
                }
                bucket.values.put(key, amount);
            }
            if (history.buckets.get(period).put(start, bucket) != null) {
                throw new IllegalArgumentException("Duplicate history period");
            }
        }
        for (JsonElement element : data.getAsJsonArray("sessions")) {
            JsonObject entry = element.getAsJsonObject();
            SessionEndReason reason = SessionEndReason.valueOf(entry.get("end-reason").getAsString());
            long checkpoint = entry.get("checkpoint-at").getAsLong();
            boolean interrupted = reason == SessionEndReason.OPEN;
            history.sessions.addLast(new PlayerSession(UUID.fromString(entry.get("id").getAsString()),
                    entry.get("started-at").getAsLong(), interrupted ? checkpoint : entry.get("ended-at").getAsLong(),
                    checkpoint, entry.get("eligible-millis").getAsLong(), entry.get("active-millis").getAsLong(),
                    entry.get("afk-millis").getAsLong(), interrupted ? SessionEndReason.INTERRUPTED : reason));
        }
        if (data.has("participation")) {
            history.loadParticipation(data.getAsJsonObject("participation"));
        } else {
            history.unknownConnectionHistory();
            for (PlayerSession retained : history.sessions) {
                history.observeConnection(retained.startedAt());
            }
        }
        if (data.has("personal-bests")) {
            history.personalBests.load(data.getAsJsonObject("personal-bests"), settings.zone());
        } else {
            history.personalBests.begin(now);
            for (NavigableMap<Long, Bucket> retained : history.buckets.values()) {
                for (Bucket bucket : retained.values()) {
                    history.personalBests.recover(bucket.window, bucket.values, bucket.recordedSince, bucket.updatedAt);
                }
            }
            for (PlayerSession retained : history.sessions) {
                history.personalBests.session(retained, true);
            }
        }
        history.prune(now, settings);
        return history;
    }

    void connect(boolean eligible, long now, HistorySettings settings) {
        observe(now);
        observeConnection(now);
        online = true;
        tracking = eligible;
        checkpointAt = now;
        lastActivity = now;
        session = new Session(now);
        prune(now, settings);
        trimSessions(settings);
    }

    void tracking(boolean eligible, long now) {
        if (online && tracking != eligible) {
            tracking = eligible;
            if (eligible) {
                lastActivity = Math.max(checkpointAt, now);
            }
        }
    }

    void activity(long now) {
        if (online && tracking) {
            lastActivity = Math.max(lastActivity, Math.max(checkpointAt, now));
        }
    }

    void disconnect(SessionEndReason reason, HistorySettings settings) {
        if (!online) {
            return;
        }
        online = false;
        tracking = false;
        PlayerSession completed = session.snapshot(checkpointAt, reason);
        personalBests.session(completed, false);
        sessions.addFirst(completed);
        session = null;
        prune(checkpointAt, settings);
        trimSessions(settings);
    }

    Durations checkpoint(long now, HistorySettings settings) {
        if (!online) {
            prune(now, settings);
            return new Durations(0, 0);
        }
        prune(checkpointAt, settings);
        long end = Math.max(checkpointAt, now);
        long active = 0;
        long afk = 0;
        if (tracking && end > checkpointAt) {
            long activeUntil = lastActivity > Long.MAX_VALUE - settings.afkMillis() ? Long.MAX_VALUE : lastActivity + settings.afkMillis();
            long split = Math.max(checkpointAt, Math.min(end, activeUntil));
            active = split - checkpointAt;
            afk = end - split;
            duration(checkpointAt, split, StatKey.ACTIVE_TIME, settings);
            duration(split, end, StatKey.AFK_TIME, settings);
            session.active += active;
            session.afk += afk;
        }
        checkpointAt = end;
        session.checkpointAt = end;
        personalBests.session(session.snapshot(0, SessionEndReason.OPEN), false);
        prune(end, settings);
        return new Durations(active, afk);
    }

    void add(StatKey key, double amount, long now, HistorySettings settings) {
        prune(now, settings);
        observe(now);
        for (StatsPeriod period : PERIODS) {
            Bucket bucket = bucket(period, now, settings);
            bucket.add(key, amount, now);
            recordBest(bucket, key);
        }
        prune(now, settings);
    }

    List<PeriodStatsSnapshot> snapshots(PlayerStatsSnapshot lifetime, StatsPeriod period, HistorySettings settings) {
        List<PeriodStatsSnapshot> result = new ArrayList<>();
        for (Bucket bucket : buckets.get(period).descendingMap().values()) {
            result.add(bucket.snapshot(lifetime, settings));
        }
        return List.copyOf(result);
    }

    PeriodStatsSnapshot snapshot(PlayerStatsSnapshot lifetime, PeriodWindow window, HistorySettings settings) {
        Bucket bucket = buckets.get(window.period()).get(window.startedAt());
        if (bucket != null) {
            return bucket.snapshot(lifetime, settings);
        }
        return new PeriodStatsSnapshot(window, new PlayerStatsSnapshot(lifetime.uuid(), lifetime.name(), Map.of(),
                lifetime.updatedAt(), 0), 0, settings.zone());
    }

    List<PlayerSession> sessions() {
        List<PlayerSession> result = new ArrayList<>(sessions.size() + 1);
        if (session != null) {
            result.add(session.snapshot(0, SessionEndReason.OPEN));
        }
        result.addAll(sessions);
        return List.copyOf(result);
    }

    long recordedSince() {
        return recordedSince;
    }

    PersonalBestSnapshot personalBests(UUID uuid, String name, HistorySettings settings, long now) {
        return personalBests.snapshot(uuid, name, settings.zone(), now);
    }

    ParticipationProfile participation(UUID uuid) {
        return new ParticipationProfile(uuid, firstConnectionAt, firstConnectionKnown, sessionRetentionKnown,
                discardedSessionThrough, sessions());
    }

    void unknownConnectionHistory() {
        firstConnectionKnown = false;
        sessionRetentionKnown = false;
    }

    boolean online() {
        return online;
    }

    UUID sessionId() {
        return session == null ? null : session.id;
    }

    JsonObject encode() {
        JsonObject data = new JsonObject();
        data.addProperty("recorded-since", recordedSince);
        data.add("personal-bests", personalBests.encode());
        JsonObject participation = new JsonObject();
        participation.addProperty("first-connection-at", firstConnectionAt);
        participation.addProperty("first-connection-known", firstConnectionKnown);
        participation.addProperty("retention-known", sessionRetentionKnown);
        participation.addProperty("discarded-through", discardedSessionThrough);
        data.add("participation", participation);
        JsonArray periods = new JsonArray();
        for (StatsPeriod period : PERIODS) {
            for (Bucket bucket : buckets.get(period).values()) {
                JsonObject item = new JsonObject();
                item.addProperty("period", period.id());
                item.addProperty("started-at", bucket.window.startedAt());
                item.addProperty("recorded-since", bucket.recordedSince);
                item.addProperty("updated-at", bucket.updatedAt);
                JsonObject values = new JsonObject();
                for (Map.Entry<StatKey, Double> entry : bucket.values.entrySet()) {
                    values.addProperty(entry.getKey().id(), entry.getValue());
                }
                item.add("values", values);
                periods.add(item);
            }
        }
        data.add("periods", periods);
        JsonArray encodedSessions = new JsonArray();
        for (PlayerSession current : sessions()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", current.id().toString());
            item.addProperty("started-at", current.startedAt());
            item.addProperty("ended-at", current.endedAt());
            item.addProperty("checkpoint-at", current.checkpointAt());
            item.addProperty("eligible-millis", current.eligibleMillis());
            item.addProperty("active-millis", current.activeMillis());
            item.addProperty("afk-millis", current.afkMillis());
            item.addProperty("end-reason", current.endReason().name());
            encodedSessions.add(item);
        }
        data.add("sessions", encodedSessions);
        return data;
    }

    private void duration(long start, long end, StatKey kind, HistorySettings settings) {
        if (start == end) {
            return;
        }
        observe(start);
        for (StatsPeriod period : PERIODS) {
            long retainedFrom = end >= calendarDay.startedAt() && end < calendarDay.endsAt()
                    ? cutoffs.get(period) : period.oldestStart(end, settings);
            long cursor = start;
            while (cursor < end) {
                Bucket bucket = bucket(period, cursor, settings);
                long until = Math.min(end, bucket.window.endsAt());
                double elapsed = until - cursor;
                bucket.add(StatKey.ONLINE_TIME, elapsed, until);
                bucket.add(kind, elapsed, until);
                recordBest(bucket, StatKey.ONLINE_TIME);
                recordBest(bucket, kind);
                if (until == bucket.window.endsAt() && bucket.window.startedAt() < retainedFrom) {
                    buckets.get(period).remove(bucket.window.startedAt());
                }
                cursor = until;
            }
        }
    }

    private Bucket bucket(StatsPeriod period, long now, HistorySettings settings) {
        PeriodWindow cached = currentWindows.get(period);
        PeriodWindow window = cached != null && now >= cached.startedAt() && now < cached.endsAt()
                ? cached : period.window(now, settings.zone());
        Bucket bucket = buckets.get(period).computeIfAbsent(window.startedAt(), ignored -> new Bucket(window, now));
        bucket.recordedSince = Math.min(bucket.recordedSince, now);
        return bucket;
    }

    private void observe(long now) {
        personalBests.begin(now);
        if (recordedSince == 0) {
            recordedSince = now;
        }
    }

    private void observeConnection(long now) {
        firstConnectionAt = firstConnectionAt < 0 ? now : Math.min(firstConnectionAt, now);
    }

    private void loadParticipation(JsonObject data) {
        firstConnectionAt = exactParticipationDate(data, "first-connection-at");
        discardedSessionThrough = exactParticipationDate(data, "discarded-through");
        firstConnectionKnown = participationFlag(data, "first-connection-known");
        sessionRetentionKnown = participationFlag(data, "retention-known");
        if (firstConnectionAt < -1 || discardedSessionThrough < -1
                || discardedSessionThrough >= 0 && (firstConnectionAt < 0 || discardedSessionThrough < firstConnectionAt)) {
            throw new IllegalArgumentException("Invalid participation history metadata");
        }
        for (PlayerSession retained : sessions) {
            if (firstConnectionAt < 0 || retained.startedAt() < firstConnectionAt) {
                throw new IllegalArgumentException("Invalid participation first connection date");
            }
        }
    }

    private long exactParticipationDate(JsonObject data, String key) {
        try {
            return data.get(key).getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid participation date " + key, exception);
        }
    }

    private boolean participationFlag(JsonObject data, String key) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Invalid participation flag " + key);
        }
        return value.getAsBoolean();
    }

    private void recordBest(Bucket bucket, StatKey key) {
        personalBests.observe(bucket.window, key, bucket.values.getOrDefault(key, 0D), bucket.recordedSince, bucket.updatedAt);
    }

    private void prune(long now, HistorySettings settings) {
        if (settings.equals(calendarSettings) && calendarDay != null
                && now >= calendarDay.startedAt() && now < calendarDay.endsAt()) {
            return;
        }
        calendarSettings = settings;
        calendarDay = StatsPeriod.DAY.window(now, settings.zone());
        for (StatsPeriod period : PERIODS) {
            currentWindows.put(period, period == StatsPeriod.DAY ? calendarDay : period.window(now, settings.zone()));
            long cutoff = period.oldestStart(now, settings);
            cutoffs.put(period, cutoff);
            buckets.get(period).headMap(cutoff, false).clear();
        }
        trimSessions(settings);
    }

    private void trimSessions(HistorySettings settings) {
        int completedLimit = settings.sessionsPerPlayer() - (session == null ? 0 : 1);
        while (sessions.size() > completedLimit) {
            PlayerSession discarded = sessions.removeLast();
            discardedSessionThrough = Math.max(discardedSessionThrough, discarded.checkpointAt());
        }
    }

    record Durations(long active, long afk) { }

    private static final class Session {
        private final UUID id = UUID.randomUUID();
        private final long startedAt;
        private long checkpointAt;
        private long active;
        private long afk;

        private Session(long now) {
            startedAt = now;
            checkpointAt = now;
        }

        private PlayerSession snapshot(long end, SessionEndReason reason) {
            return new PlayerSession(id, startedAt, end, checkpointAt, active + afk, active, afk, reason);
        }
    }

    private static final class Bucket {
        private final PeriodWindow window;
        private long recordedSince;
        private final Map<StatKey, Double> values = new EnumMap<>(StatKey.class);
        private long updatedAt;

        private Bucket(PeriodWindow window, long recordedSince) {
            this.window = window;
            this.recordedSince = recordedSince;
            updatedAt = recordedSince;
        }

        private void add(StatKey key, double amount, long now) {
            double total = values.getOrDefault(key, 0D) + amount;
            if (!Double.isFinite(total)) {
                throw new IllegalArgumentException("History statistic overflow for " + key.id());
            }
            values.put(key, total);
            updatedAt = Math.max(updatedAt, now);
        }

        private PeriodStatsSnapshot snapshot(PlayerStatsSnapshot lifetime, HistorySettings settings) {
            return new PeriodStatsSnapshot(window, new PlayerStatsSnapshot(lifetime.uuid(), lifetime.name(), values,
                    updatedAt, recordedSince), recordedSince, settings.zone());
        }
    }
}
