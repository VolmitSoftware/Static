package com.volmit.staticstats.milestone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.volmit.staticstats.stats.StatKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MilestoneLedger {
    private final Map<MilestoneDefinition, MilestoneCompletion> completed = new HashMap<>();
    private final Map<MilestoneDefinition, Notice> pending = new HashMap<>();
    private Map<StatKey, List<MilestoneDefinition>> definitions = Map.of();
    private boolean enabled;

    public void configure(MilestoneSettings settings, Map<StatKey, Double> values, long now) {
        definitions = settings.definitionIndex();
        enabled = settings.enabled();
        if (enabled) {
            for (StatKey statistic : definitions.keySet()) {
                evaluate(statistic, values.getOrDefault(statistic, 0D), now, true, null);
            }
        }
    }

    public int observe(StatKey statistic, double value, long now, UUID sessionId) {
        if (enabled) {
            return evaluate(statistic, value, now, false, sessionId);
        }
        return 0;
    }

    public List<MilestoneProgress> progress(Map<StatKey, Double> values) {
        if (!enabled) {
            return List.of();
        }
        List<MilestoneProgress> progress = new ArrayList<>();
        for (Map.Entry<StatKey, List<MilestoneDefinition>> entry : definitions.entrySet()) {
            double value = values.getOrDefault(entry.getKey(), 0D);
            for (MilestoneDefinition definition : entry.getValue()) {
                progress.add(new MilestoneProgress(definition, value, Optional.ofNullable(completed.get(definition))));
            }
        }
        return List.copyOf(progress);
    }

    public List<MilestoneCompletion> completions() {
        List<MilestoneCompletion> result = new ArrayList<>(completed.values());
        result.sort(Comparator.comparingLong(MilestoneCompletion::completedAt).reversed()
                .thenComparing(completion -> completion.definition().statistic())
                .thenComparingDouble(completion -> completion.definition().threshold()));
        return List.copyOf(result);
    }

    public List<Notice> pending() {
        return List.copyOf(pending.values());
    }

    public int acknowledge(List<Notice> written) {
        int acknowledged = 0;
        for (Notice notice : written) {
            if (pending.remove(notice.completion().definition(), notice)) {
                acknowledged++;
            }
        }
        return acknowledged;
    }

    public JsonArray encode() {
        JsonArray result = new JsonArray();
        for (MilestoneCompletion completion : completions()) {
            JsonObject item = new JsonObject();
            item.addProperty("statistic", completion.definition().statistic().id());
            item.addProperty("threshold", completion.definition().threshold());
            item.addProperty("completed-at", completion.completedAt());
            item.addProperty("observed-value", completion.observedValue());
            item.addProperty("historical", completion.historical());
            result.add(item);
        }
        return result;
    }

    public void load(JsonArray data) {
        if (data.size() > MilestoneSettings.MAX_RECORDED_DEFINITIONS) {
            throw new IllegalArgumentException("Too many recorded milestone completions");
        }
        for (JsonElement element : data) {
            JsonObject item = element.getAsJsonObject();
            MilestoneDefinition definition = new MilestoneDefinition(StatKey.parse(item.get("statistic").getAsString()),
                    item.get("threshold").getAsDouble());
            MilestoneCompletion completion = new MilestoneCompletion(definition, item.get("completed-at").getAsLong(),
                    item.get("observed-value").getAsDouble(), item.get("historical").getAsBoolean());
            if (completed.putIfAbsent(definition, completion) != null) {
                throw new IllegalArgumentException("Duplicate milestone completion " + definition.id());
            }
        }
    }

    private int evaluate(StatKey statistic, double value, long now, boolean historical, UUID sessionId) {
        int added = 0;
        for (MilestoneDefinition definition : definitions.getOrDefault(statistic, List.of())) {
            if (value < definition.threshold()) {
                break;
            }
            if (completed.containsKey(definition)) {
                continue;
            }
            MilestoneCompletion completion = new MilestoneCompletion(definition, now, value, historical);
            completed.put(definition, completion);
            added++;
            if (!historical) {
                pending.put(definition, new Notice(completion, Optional.ofNullable(sessionId)));
            }
        }
        return added;
    }

    public record Notice(MilestoneCompletion completion, Optional<UUID> sessionId) { }
}
