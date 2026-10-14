package com.volmit.staticstats.participation;

import com.volmit.staticstats.stats.HistorySettings;
import com.volmit.staticstats.stats.PeriodWindow;
import com.volmit.staticstats.stats.StatsPeriod;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Objects;

public record ParticipationQuery(StatsPeriod period, int offset) {
    public ParticipationQuery {
        Objects.requireNonNull(period);
        if (period == StatsPeriod.LIFETIME || offset < 0) {
            throw new IllegalArgumentException("Participation requires a day, week, or month and a nonnegative offset");
        }
    }

    public PeriodWindow window(long now, HistorySettings settings) {
        if (offset >= settings.retention(period)) {
            throw new IllegalArgumentException("Participation offset is outside the configured history retention");
        }
        PeriodWindow current = period.window(now, settings.zone());
        LocalDate start = Instant.ofEpochMilli(current.startedAt()).atZone(settings.zone()).toLocalDate();
        LocalDate previous = switch (period) {
            case DAY -> start.minusDays(offset);
            case WEEK -> start.minusWeeks(offset);
            case MONTH -> start.minusMonths(offset);
            case LIFETIME -> throw new IllegalArgumentException("Lifetime participation has no calendar window");
        };
        return period.window(previous.atStartOfDay(settings.zone()).toInstant().toEpochMilli(), settings.zone());
    }
}
