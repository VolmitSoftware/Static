package com.volmit.staticstats.milestone;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MilestoneCompletionEvent(UUID playerId, String playerName, MilestoneCompletion completion, Optional<UUID> sessionId) {
    public MilestoneCompletionEvent {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(playerName);
        Objects.requireNonNull(completion);
        Objects.requireNonNull(sessionId);
    }
}
