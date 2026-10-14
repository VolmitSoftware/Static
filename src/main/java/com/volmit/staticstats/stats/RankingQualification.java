package com.volmit.staticstats.stats;

import java.util.Objects;

public record RankingQualification(StatKey numeratorKey, double numerator, StatKey denominatorKey,
                                   double denominator, double minimum, boolean qualified) {
    public RankingQualification {
        Objects.requireNonNull(numeratorKey);
        Objects.requireNonNull(denominatorKey);
        if (numeratorKey.derived() || denominatorKey.derived() || !Double.isFinite(numerator) || numerator < 0
                || !Double.isFinite(denominator) || denominator < 0 || !Double.isFinite(minimum) || minimum < 0
                || qualified != (denominator > 0 && denominator >= minimum)) {
            throw new IllegalArgumentException("Invalid ranking qualification");
        }
    }
}
