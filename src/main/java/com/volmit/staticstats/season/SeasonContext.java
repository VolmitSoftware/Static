package com.volmit.staticstats.season;

import java.util.Objects;

public record SeasonContext(String world, String gameMode) {
    public SeasonContext {
        Objects.requireNonNull(world);
        Objects.requireNonNull(gameMode);
    }
}
