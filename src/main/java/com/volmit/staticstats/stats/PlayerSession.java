package com.volmit.staticstats.stats;

import java.util.Objects;
import java.util.UUID;

public record PlayerSession(UUID id, long startedAt, long endedAt, long checkpointAt,
                            long eligibleMillis, long activeMillis, long afkMillis, SessionEndReason endReason) {
    public PlayerSession {
        Objects.requireNonNull(id);
        Objects.requireNonNull(endReason);
        if (startedAt < 0 || checkpointAt < startedAt || endedAt < 0 || eligibleMillis < 0 || activeMillis < 0
                || afkMillis < 0 || activeMillis > eligibleMillis || afkMillis != eligibleMillis - activeMillis
                || (endReason == SessionEndReason.OPEN ? endedAt != 0 : endedAt != checkpointAt)) {
            throw new IllegalArgumentException("Invalid player session");
        }
    }
}
