package com.volmit.staticstats.milestone;

import com.volmit.staticstats.stats.StatKey;

import java.math.BigDecimal;
import java.util.Objects;

public record MilestoneDefinition(StatKey statistic, double threshold) {
    public MilestoneDefinition {
        Objects.requireNonNull(statistic);
        if (statistic.derived() || !Double.isFinite(threshold) || threshold <= 0) {
            throw new IllegalArgumentException("Milestones require a cumulative base statistic and a finite positive threshold");
        }
    }

    public String id() {
        return statistic.id() + ":" + BigDecimal.valueOf(threshold).stripTrailingZeros().toPlainString();
    }
}
