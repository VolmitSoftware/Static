package com.volmit.staticstats.stats;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.Locale;

public enum StatsPeriod {
    LIFETIME,
    DAY,
    WEEK,
    MONTH;

    public static StatsPeriod parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public PeriodWindow window(long epochMillis, ZoneId zone) {
        if (this == LIFETIME) {
            return new PeriodWindow(this, "lifetime", 0, Long.MAX_VALUE);
        }
        LocalDate date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
        LocalDate start = switch (this) {
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            default -> date;
        };
        LocalDate end = switch (this) {
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
            default -> start.plusDays(1);
        };
        String id = switch (this) {
            case WEEK -> String.format(Locale.ROOT, "%04d-W%02d", start.get(WeekFields.ISO.weekBasedYear()), start.get(WeekFields.ISO.weekOfWeekBasedYear()));
            case MONTH -> String.format(Locale.ROOT, "%04d-%02d", start.getYear(), start.getMonthValue());
            default -> start.toString();
        };
        return new PeriodWindow(this, id, start.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli());
    }

    long oldestStart(long now, HistorySettings settings) {
        if (this == LIFETIME) {
            return 0;
        }
        LocalDate current = Instant.ofEpochMilli(window(now, settings.zone()).startedAt()).atZone(settings.zone()).toLocalDate();
        int previous = settings.retention(this) - 1;
        LocalDate oldest = switch (this) {
            case DAY -> current.minusDays(previous);
            case WEEK -> current.minusWeeks(previous);
            case MONTH -> current.minusMonths(previous);
            default -> current;
        };
        return oldest.atStartOfDay(settings.zone()).toInstant().toEpochMilli();
    }
}
