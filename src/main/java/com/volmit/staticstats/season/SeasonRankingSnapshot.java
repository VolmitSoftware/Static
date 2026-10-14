package com.volmit.staticstats.season;

import com.volmit.staticstats.stats.RankedPlayer;
import com.volmit.staticstats.stats.RankingSnapshot;

import java.util.List;

public record SeasonRankingSnapshot(SeasonSnapshot season, RankingSnapshot ranking, List<RankedPlayer> winners) {
    public SeasonRankingSnapshot {
        winners = List.copyOf(winners);
    }
}
