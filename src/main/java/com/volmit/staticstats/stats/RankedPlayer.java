package com.volmit.staticstats.stats;

import java.util.Objects;

public record RankedPlayer(PlayerStatsSnapshot profile, int position, int rank, double value, double gapToNext) {
    public RankedPlayer {
        Objects.requireNonNull(profile);
        if (position < 1 || rank < 1 || rank > position || !Double.isFinite(value) || value < 0
                || !Double.isFinite(gapToNext) || gapToNext < 0) {
            throw new IllegalArgumentException("Invalid ranking entry");
        }
    }
}
