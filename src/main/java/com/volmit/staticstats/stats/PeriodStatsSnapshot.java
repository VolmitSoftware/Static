package com.volmit.staticstats.stats;

import java.util.Objects;
import java.time.ZoneId;

public record PeriodStatsSnapshot(PeriodWindow window, PlayerStatsSnapshot profile, long recordedSince, ZoneId historyZone) {
    public PeriodStatsSnapshot {
        Objects.requireNonNull(window);
        Objects.requireNonNull(profile);
        Objects.requireNonNull(historyZone);
        if (recordedSince < 0) {
            throw new IllegalArgumentException("History coverage cannot be negative");
        }
    }
}
