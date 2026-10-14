package com.volmit.staticstats.stats;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PlayerStatsSnapshot(UUID uuid, String name, Map<StatKey, Double> values, long updatedAt, long recordedSince) {
    public PlayerStatsSnapshot {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(name);
        if (recordedSince < 0) {
            throw new IllegalArgumentException("Recording start cannot be negative");
        }
        EnumMap<StatKey, Double> copied = new EnumMap<>(StatKey.class);
        for (Map.Entry<StatKey, Double> entry : Objects.requireNonNull(values).entrySet()) {
            if (entry.getKey().derived() || !Double.isFinite(entry.getValue()) || entry.getValue() < 0) {
                throw new IllegalArgumentException("Invalid statistic value for " + entry.getKey());
            }
            copied.put(entry.getKey(), entry.getValue());
        }
        values = Collections.unmodifiableMap(copied);
    }

    public double value(StatKey key) {
        return Objects.requireNonNull(key).value(this);
    }

    public double baseValue(StatKey key) {
        return values.getOrDefault(key, 0D);
    }
}
