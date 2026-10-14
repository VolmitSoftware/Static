package com.volmit.staticstats.season;

public record SeasonSnapshot(SeasonDefinition definition, SeasonStatus status, long recordedSince,
                             long completedAt, int participantCount) { }
