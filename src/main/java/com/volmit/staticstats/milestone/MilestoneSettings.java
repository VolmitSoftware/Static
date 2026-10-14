package com.volmit.staticstats.milestone;

import com.volmit.staticstats.stats.StatKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MilestoneSettings {
    public static final int MAX_THRESHOLDS_PER_STATISTIC = 16;
    public static final int MAX_DEFINITIONS = 512;
    public static final int MAX_RECORDED_DEFINITIONS = 4_096;

    private final boolean enabled;
    private final Map<StatKey, List<Double>> thresholds;
    private final List<MilestoneDefinition> definitions;
    private final Map<StatKey, List<MilestoneDefinition>> definitionIndex;

    public MilestoneSettings(boolean enabled, Map<StatKey, List<Double>> thresholds) {
        Objects.requireNonNull(thresholds);
        Map<StatKey, List<Double>> copied = new EnumMap<>(StatKey.class);
        int count = 0;
        for (Map.Entry<StatKey, List<Double>> entry : thresholds.entrySet()) {
            StatKey statistic = Objects.requireNonNull(entry.getKey());
            List<Double> ladder = List.copyOf(entry.getValue());
            if (statistic.derived() || ladder.size() > MAX_THRESHOLDS_PER_STATISTIC) {
                throw new IllegalArgumentException("Milestone ladders require a base statistic and at most 16 thresholds");
            }
            double previous = 0;
            for (double threshold : ladder) {
                if (!Double.isFinite(threshold) || threshold <= previous) {
                    throw new IllegalArgumentException("Milestone thresholds must be finite, positive, unique, and ascending");
                }
                previous = threshold;
            }
            copied.put(statistic, ladder);
            count += ladder.size();
        }
        if (count > MAX_DEFINITIONS) {
            throw new IllegalArgumentException("At most 512 milestone definitions may be enabled");
        }
        this.enabled = enabled;
        this.thresholds = Collections.unmodifiableMap(copied);
        List<MilestoneDefinition> all = new ArrayList<>(count);
        Map<StatKey, List<MilestoneDefinition>> indexed = new EnumMap<>(StatKey.class);
        for (Map.Entry<StatKey, List<Double>> entry : copied.entrySet()) {
            List<MilestoneDefinition> ladder = new ArrayList<>(entry.getValue().size());
            for (double threshold : entry.getValue()) {
                MilestoneDefinition definition = new MilestoneDefinition(entry.getKey(), threshold);
                all.add(definition);
                ladder.add(definition);
            }
            indexed.put(entry.getKey(), List.copyOf(ladder));
        }
        definitions = List.copyOf(all);
        definitionIndex = Collections.unmodifiableMap(indexed);
    }

    public static MilestoneSettings defaults() {
        return new MilestoneSettings(true, Map.of(
                StatKey.BLOCKS_BROKEN, List.of(100D, 1_000D, 10_000D),
                StatKey.BLOCKS_PLACED, List.of(100D, 1_000D, 10_000D),
                StatKey.ACTIVE_TIME, List.of(3_600_000D, 36_000_000D, 360_000_000D)));
    }

    public List<MilestoneDefinition> definitions() {
        return definitions;
    }

    public boolean enabled() {
        return enabled;
    }

    public Map<StatKey, List<Double>> thresholds() {
        return thresholds;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MilestoneSettings settings && enabled == settings.enabled && thresholds.equals(settings.thresholds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, thresholds);
    }

    Map<StatKey, List<MilestoneDefinition>> definitionIndex() {
        return definitionIndex;
    }
}
