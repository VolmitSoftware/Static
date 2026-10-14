package com.volmit.staticstats.records;

import com.volmit.staticstats.stats.StatKey;

import java.util.Objects;

public record PersonalBest(PersonalBestPeriod period, StatKey statistic, double value, long startedAt,
                           long endsAt, long recordedSince, long observedAt, boolean inProgress, boolean recovered) {
    public PersonalBest {
        Objects.requireNonNull(period);
        Objects.requireNonNull(statistic);
        if (!period.supports(statistic) || !Double.isFinite(value) || value <= 0
                || period == PersonalBestPeriod.SESSION && startedAt < 0
                || recordedSince < 0 || recordedSince < startedAt || observedAt < recordedSince
                || (endsAt == 0 ? period != PersonalBestPeriod.SESSION || !inProgress : endsAt < observedAt)
                || (period == PersonalBestPeriod.SESSION && inProgress != (endsAt == 0))) {
            throw new IllegalArgumentException("Invalid personal best");
        }
    }
}
