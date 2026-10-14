package com.volmit.staticstats.dashboard;

import java.util.Objects;
import java.util.UUID;

public record DashboardDataPlayer(UUID uuid, String name, long updatedAt, long recordedSince) {
    public DashboardDataPlayer {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(name);
    }
}
