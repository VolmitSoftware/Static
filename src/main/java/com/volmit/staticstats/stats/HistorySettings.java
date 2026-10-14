package com.volmit.staticstats.stats;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

public record HistorySettings(ZoneId zone, long afkMillis, int retainedDays, int retainedWeeks,
                              int retainedMonths, int sessionsPerPlayer) {
    public HistorySettings {
        Objects.requireNonNull(zone);
        if (afkMillis < 1 || retainedDays < 1 || retainedWeeks < 1 || retainedMonths < 1 || sessionsPerPlayer < 1
                || retainedDays > 3660 || retainedWeeks > 520 || retainedMonths > 120 || sessionsPerPlayer > 1000) {
            throw new IllegalArgumentException("Invalid activity history settings");
        }
    }

    public static HistorySettings defaults() {
        return new HistorySettings(ZoneOffset.UTC, 300_000, 35, 12, 12, 20);
    }

    public int retention(StatsPeriod period) {
        return switch (period) {
            case DAY -> retainedDays;
            case WEEK -> retainedWeeks;
            case MONTH -> retainedMonths;
            case LIFETIME -> 1;
        };
    }
}
