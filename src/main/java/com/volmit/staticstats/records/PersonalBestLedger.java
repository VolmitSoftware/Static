package com.volmit.staticstats.records;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.volmit.staticstats.stats.PeriodWindow;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PersonalBestLedger {
    private final Map<PersonalBestPeriod, Map<StatKey, Candidate>> records = new EnumMap<>(PersonalBestPeriod.class);
    private long trackingSince;

    public PersonalBestLedger() {
        for (PersonalBestPeriod period : PersonalBestPeriod.values()) {
            records.put(period, new EnumMap<>(StatKey.class));
        }
    }

    public void load(JsonObject data, ZoneId zone) {
        long since = number(data, "tracking-since");
        if (since < 0) {
            throw new IllegalArgumentException("Invalid personal-best tracking date");
        }
        Map<PersonalBestPeriod, Map<StatKey, Candidate>> loaded = new EnumMap<>(PersonalBestPeriod.class);
        for (PersonalBestPeriod period : PersonalBestPeriod.values()) {
            loaded.put(period, new EnumMap<>(StatKey.class));
        }
        for (JsonElement element : data.getAsJsonArray("records")) {
            Candidate candidate = decode(element.getAsJsonObject(), zone);
            PersonalBest record = candidate.record();
            if (loaded.get(record.period()).put(record.statistic(), candidate) != null) {
                throw new IllegalArgumentException("Duplicate personal best");
            }
        }
        trackingSince = since;
        records.clear();
        records.putAll(loaded);
    }

    public void begin(long now) {
        if (now < 0) {
            throw new IllegalArgumentException("Invalid personal-best observation date");
        }
        if (trackingSince == 0) {
            trackingSince = now;
        }
    }

    public void observe(PeriodWindow window, StatKey statistic, double value, long recordedSince, long observedAt) {
        update(PersonalBestPeriod.from(window.period()), statistic, value,
                new Source(window.startedAt(), window.endsAt(), recordedSince, observedAt, observedAt, null, false));
    }

    public void recover(PeriodWindow window, Map<StatKey, Double> values, long recordedSince, long observedAt) {
        Source source = new Source(window.startedAt(), window.endsAt(), recordedSince, observedAt, observedAt, null, true);
        for (Map.Entry<StatKey, Double> entry : values.entrySet()) {
            update(PersonalBestPeriod.from(window.period()), entry.getKey(), entry.getValue(), source);
        }
    }

    public void session(PlayerSession session, boolean recovered) {
        Source source = new Source(session.startedAt(), session.endedAt(), session.startedAt(), session.checkpointAt(),
                session.checkpointAt(), session.id(), recovered);
        update(PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME, session.eligibleMillis(), source);
        update(PersonalBestPeriod.SESSION, StatKey.ACTIVE_TIME, session.activeMillis(), source);
        update(PersonalBestPeriod.SESSION, StatKey.AFK_TIME, session.afkMillis(), source);
    }

    public PersonalBestSnapshot snapshot(UUID uuid, String name, ZoneId zone, long now) {
        List<PersonalBest> result = new ArrayList<>();
        for (Map<StatKey, Candidate> byStatistic : records.values()) {
            for (Candidate candidate : byStatistic.values()) {
                PersonalBest record = candidate.record();
                boolean inProgress = record.period() == PersonalBestPeriod.SESSION ? record.endsAt() == 0
                        : now >= record.startedAt() && now < record.endsAt();
                result.add(new PersonalBest(record.period(), record.statistic(), record.value(), record.startedAt(),
                        record.endsAt(), record.recordedSince(), record.observedAt(), inProgress, record.recovered()));
            }
        }
        return new PersonalBestSnapshot(uuid, name, zone, trackingSince, result);
    }

    public JsonObject encode() {
        JsonObject data = new JsonObject();
        data.addProperty("tracking-since", trackingSince);
        JsonArray entries = new JsonArray();
        for (Map<StatKey, Candidate> byStatistic : records.values()) {
            for (Candidate candidate : byStatistic.values()) {
                PersonalBest record = candidate.record();
                JsonObject entry = new JsonObject();
                entry.addProperty("period", record.period().id());
                entry.addProperty("statistic", record.statistic().id());
                entry.addProperty("value", record.value());
                entry.addProperty("started-at", record.startedAt());
                entry.addProperty("ends-at", record.endsAt());
                entry.addProperty("recorded-since", record.recordedSince());
                entry.addProperty("observed-at", record.observedAt());
                entry.addProperty("checkpoint-at", candidate.checkpointAt());
                entry.addProperty("recovered", record.recovered());
                if (candidate.sessionId() != null) {
                    entry.addProperty("session-id", candidate.sessionId().toString());
                }
                entries.add(entry);
            }
        }
        data.add("records", entries);
        return data;
    }

    private void update(PersonalBestPeriod period, StatKey statistic, double value, Source source) {
        if (value == 0) {
            return;
        }
        if (!period.supports(statistic) || !Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Invalid personal-best statistic");
        }
        Map<StatKey, Candidate> byStatistic = records.get(period);
        Candidate previous = byStatistic.get(statistic);
        boolean sameSource = previous != null && previous.record().startedAt() == source.startedAt()
                && Objects.equals(previous.sessionId(), source.sessionId());
        if (previous != null && (value < previous.record().value()
                || value == previous.record().value() && !sameSource && source.startedAt() >= previous.record().startedAt())) {
            return;
        }
        boolean equalPlateau = sameSource && value == previous.record().value();
        if (equalPlateau && source.endsAt() == previous.record().endsAt()
                && source.checkpointAt() <= previous.checkpointAt() && source.recordedSince() >= previous.record().recordedSince()) {
            return;
        }
        long observedAt = equalPlateau ? previous.record().observedAt() : source.observedAt();
        boolean recovered = equalPlateau ? previous.record().recovered() : source.recovered();
        long recordedSince = sameSource ? Math.min(previous.record().recordedSince(), source.recordedSince()) : source.recordedSince();
        PersonalBest record = new PersonalBest(period, statistic, value, source.startedAt(), source.endsAt(),
                recordedSince, observedAt, source.endsAt() == 0 || source.observedAt() < source.endsAt(), recovered);
        byStatistic.put(statistic, new Candidate(record, source.sessionId(), source.checkpointAt()));
    }

    private Candidate decode(JsonObject data, ZoneId zone) {
        PersonalBestPeriod period = PersonalBestPeriod.parse(data.get("period").getAsString());
        StatKey statistic = StatKey.parse(data.get("statistic").getAsString());
        long startedAt = number(data, "started-at");
        long endsAt = number(data, "ends-at");
        long recordedSince = number(data, "recorded-since");
        long observedAt = number(data, "observed-at");
        long checkpointAt = number(data, "checkpoint-at");
        UUID sessionId = data.has("session-id") ? UUID.fromString(data.get("session-id").getAsString()) : null;
        if (checkpointAt < observedAt || period == PersonalBestPeriod.SESSION != (sessionId != null)) {
            throw new IllegalArgumentException("Invalid personal-best source");
        }
        if (period == PersonalBestPeriod.SESSION) {
            if (recordedSince != startedAt || endsAt != 0 && endsAt != checkpointAt) {
                throw new IllegalArgumentException("Invalid personal-best session interval");
            }
            endsAt = checkpointAt;
        } else {
            PeriodWindow window = StatsPeriod.valueOf(period.name()).window(startedAt, zone);
            if (startedAt != window.startedAt() || endsAt != window.endsAt() || checkpointAt > endsAt
                    || recordedSince >= endsAt) {
                throw new IllegalArgumentException("Invalid personal-best calendar interval");
            }
        }
        PersonalBest record = new PersonalBest(period, statistic, data.get("value").getAsDouble(), startedAt,
                endsAt, recordedSince, observedAt, false, data.get("recovered").getAsBoolean());
        return new Candidate(record, sessionId, checkpointAt);
    }

    private long number(JsonObject data, String key) {
        try {
            return data.get(key).getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid personal-best " + key, exception);
        }
    }

    private record Source(long startedAt, long endsAt, long recordedSince, long observedAt,
                          long checkpointAt, UUID sessionId, boolean recovered) { }

    private record Candidate(PersonalBest record, UUID sessionId, long checkpointAt) { }
}
