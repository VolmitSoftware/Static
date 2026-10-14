package com.volmit.staticstats.participation;

import com.volmit.staticstats.stats.PeriodWindow;
import com.volmit.staticstats.stats.PlayerSession;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

public record ParticipationSnapshot(PeriodWindow window, ZoneId historyZone, long refreshedAt,
                                    int uniquePlayers, int newPlayers, int returningPlayers, int unknownCohortPlayers,
                                    int completedSessions, OptionalDouble medianSessionMillis, int openSessions,
                                    int interruptedSessions, int shutdownSessions, long earliestObservation,
                                    boolean retentionLimited) {
    public ParticipationSnapshot {
        Objects.requireNonNull(window);
        Objects.requireNonNull(historyZone);
        Objects.requireNonNull(medianSessionMillis);
        if (refreshedAt < 0 || uniquePlayers < 0 || newPlayers < 0 || returningPlayers < 0 || unknownCohortPlayers < 0
                || (long) newPlayers + returningPlayers + unknownCohortPlayers != uniquePlayers || completedSessions < 0
                || openSessions < 0 || interruptedSessions < 0 || shutdownSessions < 0 || earliestObservation < 0
                || medianSessionMillis.isPresent() != (completedSessions > 0)
                || medianSessionMillis.isPresent() && (!Double.isFinite(medianSessionMillis.getAsDouble()) || medianSessionMillis.getAsDouble() < 0)) {
            throw new IllegalArgumentException("Invalid participation summary");
        }
    }

    public static ParticipationSnapshot create(ParticipationQuery query, ParticipationCapture capture) {
        PeriodWindow window = query.window(capture.capturedAt(), capture.settings());
        Totals totals = new Totals();
        Set<UUID> identities = new HashSet<>();
        for (ParticipationProfile profile : capture.profiles()) {
            if (!identities.add(profile.uuid())) {
                throw new IllegalArgumentException("Duplicate participation profile " + profile.uuid());
            }
            totals.include(profile, window);
        }
        return new ParticipationSnapshot(window, capture.settings().zone(), capture.capturedAt(), totals.unique,
                totals.newPlayers, totals.returningPlayers, totals.unknown, totals.durations.size(), totals.median(),
                totals.open, totals.interrupted, totals.shutdown, totals.earliest == Long.MAX_VALUE ? 0 : totals.earliest,
                totals.limited);
    }

    private static final class Totals {
        private final List<Long> durations = new ArrayList<>();
        private int unique;
        private int newPlayers;
        private int returningPlayers;
        private int unknown;
        private int open;
        private int interrupted;
        private int shutdown;
        private long earliest = Long.MAX_VALUE;
        private boolean limited;

        private void include(ParticipationProfile profile, PeriodWindow window) {
            limited |= !profile.retentionKnown() || profile.discardedThrough() >= 0 && profile.discardedThrough() >= window.startedAt()
                    && profile.firstConnectionAt() >= 0 && profile.firstConnectionAt() < window.endsAt();
            boolean participates = false;
            for (PlayerSession session : profile.sessions()) {
                if (session.startedAt() >= window.endsAt() || session.checkpointAt() < window.startedAt()) {
                    continue;
                }
                participates = true;
                earliest = Math.min(earliest, Math.max(session.startedAt(), window.startedAt()));
                include(session, window);
            }
            if (!participates) {
                return;
            }
            unique++;
            if (profile.firstConnectionAt() < window.startedAt()) {
                returningPlayers++;
            } else if (profile.firstConnectionKnown()) {
                newPlayers++;
            } else {
                unknown++;
            }
        }

        private void include(PlayerSession session, PeriodWindow window) {
            switch (session.endReason()) {
                case OPEN -> open++;
                case INTERRUPTED -> interrupted++;
                case SHUTDOWN -> shutdown++;
                case QUIT -> {
                    if (session.endedAt() >= window.startedAt() && session.endedAt() < window.endsAt()) {
                        durations.add(session.eligibleMillis());
                    }
                }
            }
        }

        private OptionalDouble median() {
            if (durations.isEmpty()) {
                return OptionalDouble.empty();
            }
            durations.sort(Long::compare);
            int middle = durations.size() / 2;
            return OptionalDouble.of(durations.size() % 2 == 1 ? durations.get(middle)
                    : durations.get(middle - 1) / 2D + durations.get(middle) / 2D);
        }
    }
}
