package com.volmit.staticstats.detailed;

import java.util.Objects;

public record RankedDetailPlayer(DetailSnapshot profile, int position, int rank, long value, long gapToNext) {
    public RankedDetailPlayer {
        Objects.requireNonNull(profile);
        if (position < 1 || rank < 1 || rank > position || value <= 0 || gapToNext < 0) {
            throw new IllegalArgumentException("Invalid detailed ranking entry");
        }
    }
}
