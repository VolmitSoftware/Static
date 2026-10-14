package com.volmit.staticstats.participation;

import com.volmit.staticstats.stats.PlayerSession;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ParticipationProfile(UUID uuid, long firstConnectionAt, boolean firstConnectionKnown,
                                   boolean retentionKnown, long discardedThrough, List<PlayerSession> sessions) {
    public ParticipationProfile {
        Objects.requireNonNull(uuid);
        sessions = List.copyOf(sessions);
        if (firstConnectionAt < -1 || discardedThrough < -1 || firstConnectionAt == -1 && !sessions.isEmpty()) {
            throw new IllegalArgumentException("Invalid participation connection history");
        }
        for (PlayerSession session : sessions) {
            if (session.startedAt() < firstConnectionAt) {
                throw new IllegalArgumentException("Participation connection origin follows a retained session");
            }
        }
    }
}
