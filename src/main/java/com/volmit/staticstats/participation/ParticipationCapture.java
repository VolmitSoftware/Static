package com.volmit.staticstats.participation;

import com.volmit.staticstats.stats.HistorySettings;

import java.util.List;
import java.util.Objects;

public record ParticipationCapture(long capturedAt, HistorySettings settings, List<ParticipationProfile> profiles) {
    public ParticipationCapture {
        Objects.requireNonNull(settings);
        profiles = List.copyOf(profiles);
        if (capturedAt < 0) {
            throw new IllegalArgumentException("Invalid participation capture time");
        }
    }
}
