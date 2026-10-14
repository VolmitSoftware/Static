package com.volmit.staticstats.stats;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record RankingRules(Map<StatKey, Double> minimums) {
    private static final Map<StatKey, RankingRequirement> DEFAULT_REQUIREMENTS = Map.of(
            StatKey.KILL_DEATH_RATIO, new RankingRequirement(StatKey.PVP_KILLS, StatKey.PVP_DEATHS, 10),
            StatKey.MELEE_DAMAGE_RATIO, new RankingRequirement(StatKey.MELEE_DAMAGE_DEALT, StatKey.MELEE_DAMAGE_TAKEN, 100),
            StatKey.MELEE_HIT_RATIO, new RankingRequirement(StatKey.MELEE_HITS_DEALT, StatKey.MELEE_HITS_TAKEN, 25),
            StatKey.ARROW_DAMAGE_RATIO, new RankingRequirement(StatKey.ARROW_DAMAGE_DEALT, StatKey.ARROW_DAMAGE_TAKEN, 100),
            StatKey.ARROW_HIT_RATIO, new RankingRequirement(StatKey.ARROW_HITS_DEALT, StatKey.ARROW_HITS_TAKEN, 25),
            StatKey.ARROW_ACCURACY, new RankingRequirement(StatKey.ARROW_HITS_DEALT, StatKey.ARROWS_FIRED, 50),
            StatKey.VOTES_PER_DAY, new RankingRequirement(StatKey.VOTES, StatKey.ONLINE_TIME, 3_600_000));

    public RankingRules {
        Objects.requireNonNull(minimums);
        if (!minimums.keySet().equals(DEFAULT_REQUIREMENTS.keySet())) {
            throw new IllegalArgumentException("Ranking minimums must contain exactly the seven ratio statistics");
        }
        EnumMap<StatKey, Double> copied = new EnumMap<>(StatKey.class);
        for (Map.Entry<StatKey, Double> entry : minimums.entrySet()) {
            double minimum = Objects.requireNonNull(entry.getValue());
            if (!Double.isFinite(minimum) || minimum < 0) {
                throw new IllegalArgumentException("Ranking minimum must be finite and nonnegative for " + entry.getKey().id());
            }
            copied.put(entry.getKey(), minimum == 0 ? 0D : minimum);
        }
        minimums = Collections.unmodifiableMap(copied);
    }

    public static RankingRules defaults() {
        EnumMap<StatKey, Double> minimums = new EnumMap<>(StatKey.class);
        for (Map.Entry<StatKey, RankingRequirement> entry : DEFAULT_REQUIREMENTS.entrySet()) {
            minimums.put(entry.getKey(), entry.getValue().minimum());
        }
        return new RankingRules(minimums);
    }

    public Optional<RankingRequirement> requirement(StatKey statistic) {
        RankingRequirement descriptor = DEFAULT_REQUIREMENTS.get(Objects.requireNonNull(statistic));
        return descriptor == null ? Optional.empty()
                : Optional.of(new RankingRequirement(descriptor.numeratorKey(), descriptor.denominatorKey(), minimums.get(statistic)));
    }

    public Optional<RankingQualification> evaluate(StatKey statistic, PlayerStatsSnapshot profile) {
        Objects.requireNonNull(profile);
        return requirement(statistic).map(requirement -> requirement.evaluate(profile));
    }
}
