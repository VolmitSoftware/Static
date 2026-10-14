package com.volmit.staticstats.records;

import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;

import java.util.Locale;
import java.util.Objects;

public enum PersonalBestPeriod {
    DAY,
    WEEK,
    MONTH,
    SESSION;

    public static PersonalBestPeriod parse(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }

    public static PersonalBestPeriod from(StatsPeriod period) {
        return switch (period) {
            case DAY -> DAY;
            case WEEK -> WEEK;
            case MONTH -> MONTH;
            case LIFETIME -> throw new IllegalArgumentException("Lifetime has no personal-best window");
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean supports(StatKey statistic) {
        Objects.requireNonNull(statistic);
        return this == SESSION ? statistic == StatKey.ONLINE_TIME || statistic == StatKey.ACTIVE_TIME
                || statistic == StatKey.AFK_TIME : !statistic.derived();
    }
}
