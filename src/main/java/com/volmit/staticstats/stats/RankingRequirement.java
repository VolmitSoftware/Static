package com.volmit.staticstats.stats;

import java.util.Objects;

public record RankingRequirement(StatKey numeratorKey, StatKey denominatorKey, double minimum) {
    public RankingRequirement {
        Objects.requireNonNull(numeratorKey);
        Objects.requireNonNull(denominatorKey);
        if (numeratorKey.derived() || denominatorKey.derived() || !Double.isFinite(minimum) || minimum < 0) {
            throw new IllegalArgumentException("Invalid ranking requirement");
        }
    }

    public RankingQualification evaluate(PlayerStatsSnapshot profile) {
        Objects.requireNonNull(profile);
        double denominator = profile.baseValue(denominatorKey);
        return new RankingQualification(numeratorKey, profile.baseValue(numeratorKey), denominatorKey,
                denominator, minimum, denominator > 0 && denominator >= minimum);
    }
}
