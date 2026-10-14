package com.volmit.staticstats.season;

import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.RankingRules;

import java.util.EnumMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record SeasonRules(StatKey statistic, double minimumSample, Set<String> excludedWorlds,
                          boolean trackCreative, boolean trackSpectator, long afkMillis) {
    public SeasonRules {
        Objects.requireNonNull(statistic);
        if (!Double.isFinite(minimumSample) || minimumSample < 0 || afkMillis < 1) {
            throw new IllegalArgumentException("Season sample minimum and AFK timeout must be valid positive values");
        }
        Set<String> normalized = new HashSet<>();
        for (String world : excludedWorlds) {
            normalized.add(Objects.requireNonNull(world).toLowerCase(Locale.ROOT));
        }
        excludedWorlds = Set.copyOf(normalized);
    }

    public boolean accepts(SeasonContext context) {
        return context == null || !excludedWorlds.contains(context.world().toLowerCase(Locale.ROOT))
                && (!context.gameMode().equals("CREATIVE") || trackCreative)
                && (!context.gameMode().equals("SPECTATOR") || trackSpectator);
    }

    public RankingRules rankingRules() {
        Map<StatKey, Double> thresholds = new EnumMap<>(RankingRules.defaults().minimums());
        if (thresholds.containsKey(statistic)) {
            thresholds.put(statistic, minimumSample);
        }
        return new RankingRules(thresholds);
    }
}
