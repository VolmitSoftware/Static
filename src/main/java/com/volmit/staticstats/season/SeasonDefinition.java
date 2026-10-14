package com.volmit.staticstats.season;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record SeasonDefinition(String id, String name, Instant startsAt, Instant endsAt, SeasonRules rules) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    public SeasonDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(startsAt);
        Objects.requireNonNull(endsAt);
        Objects.requireNonNull(rules);
        if (!ID.matcher(id).matches() || name.isBlank() || name.length() > 80 || startsAt.toEpochMilli() < 0
                || !endsAt.isAfter(startsAt) || startsAt.getNano() % 1_000_000 != 0 || endsAt.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("Seasons need a unique lowercase ID, a name up to 80 characters, and ordered millisecond UTC instants");
        }
    }
}
