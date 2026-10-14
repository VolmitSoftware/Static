package com.volmit.staticstats.season;

import com.volmit.staticstats.stats.PlayerStatsSnapshot;

public record SeasonProfileSnapshot(SeasonSnapshot season, PlayerStatsSnapshot profile) { }
