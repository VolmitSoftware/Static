package com.volmit.staticstats.detailed;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record DetailSnapshot(UUID uuid, String name, Map<DetailKey, Long> values,
                             Map<DetailKey, Long> recordedSince, Map<DetailKey, Long> lastObservedAt, long updatedAt) {
    public DetailSnapshot {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(name);
        values = immutable(values);
        recordedSince = immutable(recordedSince);
        lastObservedAt = immutable(lastObservedAt);
        if (!values.keySet().equals(recordedSince.keySet()) || !values.keySet().equals(lastObservedAt.keySet()) || updatedAt < 0) {
            throw new IllegalArgumentException("Detailed counter metadata does not match its values");
        }
        for (DetailKey key : values.keySet()) {
            if (values.get(key) <= 0 || recordedSince.get(key) <= 0 || lastObservedAt.get(key) < recordedSince.get(key)
                    || updatedAt < lastObservedAt.get(key)) {
                throw new IllegalArgumentException("Invalid detailed counter observation for " + key.id());
            }
        }
    }

    public long value(DetailKey key) {
        return values.getOrDefault(Objects.requireNonNull(key), 0L);
    }

    public long recordedSince(DetailKey key) {
        return recordedSince.getOrDefault(Objects.requireNonNull(key), 0L);
    }

    public long lastObservedAt(DetailKey key) {
        return lastObservedAt.getOrDefault(Objects.requireNonNull(key), 0L);
    }

    private static Map<DetailKey, Long> immutable(Map<DetailKey, Long> source) {
        Map<DetailKey, Long> copy = new EnumMap<>(DetailKey.class);
        copy.putAll(Objects.requireNonNull(source));
        return Collections.unmodifiableMap(copy);
    }
}
