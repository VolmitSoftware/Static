package com.volmit.staticstats.milestone;

import java.util.Objects;
import java.util.Optional;

public record MilestoneProgress(MilestoneDefinition definition, double value, Optional<MilestoneCompletion> completion) {
    public MilestoneProgress {
        Objects.requireNonNull(definition);
        Objects.requireNonNull(completion);
        if (!Double.isFinite(value) || value < 0 || completion.isPresent() && !completion.get().definition().equals(definition)) {
            throw new IllegalArgumentException("Invalid milestone progress");
        }
    }
}
