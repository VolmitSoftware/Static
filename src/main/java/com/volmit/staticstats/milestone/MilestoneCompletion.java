package com.volmit.staticstats.milestone;

import java.util.Objects;

public record MilestoneCompletion(MilestoneDefinition definition, long completedAt, double observedValue, boolean historical) {
    public MilestoneCompletion {
        Objects.requireNonNull(definition);
        if (completedAt < 0 || !Double.isFinite(observedValue) || observedValue < definition.threshold()) {
            throw new IllegalArgumentException("Milestone completion requires a valid observation date and a reached threshold");
        }
    }
}
