package com.volmit.staticstats.stats;

import java.util.Objects;

public record PeriodWindow(StatsPeriod period, String id, long startedAt, long endsAt) {
    public PeriodWindow {
        Objects.requireNonNull(period);
        Objects.requireNonNull(id);
        if (endsAt <= startedAt) {
            throw new IllegalArgumentException("Period end must follow its start");
        }
    }
}
