package com.volmit.staticstats.season;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record SeasonSettings(List<SeasonDefinition> events) {
    public static final int MAX_SEASONS = 64;
    public static final int MAX_OVERLAP = 8;

    public SeasonSettings {
        events = List.copyOf(events);
        if (events.size() > MAX_SEASONS) {
            throw new IllegalArgumentException("At most 64 seasons may be retained");
        }
        Set<String> ids = new HashSet<>();
        for (SeasonDefinition event : events) {
            if (!ids.add(event.id())) {
                throw new IllegalArgumentException("Duplicate season ID " + event.id());
            }
            int overlapping = 0;
            for (SeasonDefinition candidate : events) {
                if (!candidate.startsAt().isAfter(event.startsAt()) && candidate.endsAt().isAfter(event.startsAt())) {
                    overlapping++;
                }
            }
            if (overlapping > MAX_OVERLAP) {
                throw new IllegalArgumentException("At most 8 seasons may overlap");
            }
        }
    }

    public static SeasonSettings defaults() {
        return new SeasonSettings(List.of());
    }
}
