package com.volmit.staticstats.detailed;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DetailLedger {
    private final Map<DetailKey, Observation> observations = new EnumMap<>(DetailKey.class);

    public void add(DetailKey key, long amount, long now) {
        Objects.requireNonNull(key);
        if (amount < 0 || now <= 0) {
            throw new IllegalArgumentException("Detailed counters require nonnegative increments and a positive observation time");
        }
        if (amount == 0) {
            return;
        }
        Observation previous = observations.get(key);
        long total;
        try {
            total = Math.addExact(previous == null ? 0 : previous.value(), amount);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Detailed counter overflow for " + key.id(), exception);
        }
        observations.put(key, new Observation(total, previous == null ? now : previous.first(),
                previous == null ? now : Math.max(now, previous.last())));
    }

    public DetailSnapshot snapshot(UUID uuid, String name) {
        Map<DetailKey, Long> values = new EnumMap<>(DetailKey.class);
        Map<DetailKey, Long> first = new EnumMap<>(DetailKey.class);
        Map<DetailKey, Long> last = new EnumMap<>(DetailKey.class);
        long updatedAt = 0;
        for (Map.Entry<DetailKey, Observation> entry : observations.entrySet()) {
            values.put(entry.getKey(), entry.getValue().value());
            first.put(entry.getKey(), entry.getValue().first());
            last.put(entry.getKey(), entry.getValue().last());
            updatedAt = Math.max(updatedAt, entry.getValue().last());
        }
        return new DetailSnapshot(uuid, name, values, first, last, updatedAt);
    }

    public JsonObject encode() {
        JsonObject data = new JsonObject();
        for (Map.Entry<DetailKey, Observation> entry : observations.entrySet()) {
            JsonObject observation = new JsonObject();
            observation.addProperty("value", entry.getValue().value());
            observation.addProperty("first-observed-at", entry.getValue().first());
            observation.addProperty("last-observed-at", entry.getValue().last());
            data.add(entry.getKey().id(), observation);
        }
        return data;
    }

    public void load(JsonObject data) {
        Map<DetailKey, Observation> loaded = new EnumMap<>(DetailKey.class);
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            DetailKey key = DetailKey.find(entry.getKey()).orElseThrow(() -> new IllegalArgumentException("Unknown detailed counter " + entry.getKey()));
            JsonObject value = entry.getValue().getAsJsonObject();
            Observation observation = new Observation(exactLong(value, "value"), exactLong(value, "first-observed-at"),
                    exactLong(value, "last-observed-at"));
            if (observation.value() <= 0 || observation.first() <= 0 || observation.last() < observation.first() || loaded.put(key, observation) != null) {
                throw new IllegalArgumentException("Invalid detailed counter observation for " + key.id());
            }
        }
        observations.clear();
        observations.putAll(loaded);
    }

    private static long exactLong(JsonObject data, String key) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Detailed counter " + key + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Detailed counter " + key + " is outside the integer range", exception);
        }
    }

    private record Observation(long value, long first, long last) { }
}
