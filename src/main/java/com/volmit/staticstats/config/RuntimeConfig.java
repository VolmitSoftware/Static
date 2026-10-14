package com.volmit.staticstats.config;

import com.volmit.staticstats.stats.HistorySettings;
import com.volmit.staticstats.stats.RankingRules;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.milestone.MilestoneSettings;
import com.volmit.staticstats.season.SeasonDefinition;
import com.volmit.staticstats.season.SeasonRules;
import com.volmit.staticstats.season.SeasonSettings;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailSettings;
import com.volmit.staticstats.dashboard.DashboardSettings;

import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record RuntimeConfig(
        StaticConfig source,
        boolean enabled,
        String language,
        int autosaveSeconds,
        int leaderboardLimit,
        Set<String> excludedWorlds,
        boolean trackCreative,
        boolean trackSpectator,
        boolean hotReloadEnabled,
        long hotReloadPollMillis,
        long hotReloadCooldownMillis,
        boolean hotReloadNotifyOperators,
        boolean splashScreen,
        boolean commandSounds,
        boolean debugUploadEnabled,
        RankingRules rankingRules,
        HistorySettings historySettings,
        MilestoneSettings milestoneSettings,
        boolean milestoneNotifications,
        SeasonSettings seasonSettings,
        DetailSettings detailSettings,
        DashboardSettings dashboardSettings
) {
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z0-9_-]{2,32}");

    public RuntimeConfig {
        source = source.copy();
        excludedWorlds = Set.copyOf(excludedWorlds);
        Objects.requireNonNull(rankingRules);
        Objects.requireNonNull(historySettings);
        Objects.requireNonNull(milestoneSettings);
        Objects.requireNonNull(seasonSettings);
        Objects.requireNonNull(detailSettings);
        Objects.requireNonNull(dashboardSettings);
    }

    @Override
    public StaticConfig source() {
        return source.copy();
    }

    public static RuntimeConfig from(StaticConfig input) {
        StaticConfig config = Objects.requireNonNull(input, "configuration").copy();
        String language = Objects.requireNonNull(config.general.language, "general.language").trim();
        if (!LOCALE.matcher(language).matches()) {
            throw new IllegalArgumentException("general.language must be a locale such as en_US");
        }
        config.general.language = language;
        DashboardSettings dashboardSettings = new DashboardSettings(config.dashboard.enabled,
                config.dashboard.port, config.dashboard.publicOrigin, config.dashboard.publicProfilesEnabled);
        config.dashboard.publicOrigin = dashboardSettings.publicOrigin();
        ZoneId historyZone = ZoneId.of(Objects.requireNonNull(config.history.timeZone, "history.timeZone").trim());
        config.history.timeZone = historyZone.getId();
        config.history.afkSeconds = Math.max(5, Math.min(86400, config.history.afkSeconds));
        config.history.retainedDays = Math.max(1, Math.min(366, config.history.retainedDays));
        config.history.retainedWeeks = Math.max(1, Math.min(104, config.history.retainedWeeks));
        config.history.retainedMonths = Math.max(1, Math.min(120, config.history.retainedMonths));
        config.history.sessionsPerPlayer = Math.max(1, Math.min(100, config.history.sessionsPerPlayer));
        HistorySettings historySettings = new HistorySettings(historyZone, config.history.afkSeconds * 1000L,
                config.history.retainedDays, config.history.retainedWeeks, config.history.retainedMonths,
                config.history.sessionsPerPlayer);
        config.tracking.autosaveSeconds = Math.max(10, Math.min(3600, config.tracking.autosaveSeconds));
        config.tracking.leaderboardLimit = Math.max(1, Math.min(25, config.tracking.leaderboardLimit));
        config.hotReload.pollIntervalMillis = Math.max(250L, Math.min(60000L, config.hotReload.pollIntervalMillis));
        config.hotReload.cooldownMillis = Math.max(250L, Math.min(60000L, config.hotReload.cooldownMillis));
        config.ranking.minPvpDeaths = threshold(config.ranking.minPvpDeaths, "minPvpDeaths");
        config.ranking.minMeleeDamageTaken = threshold(config.ranking.minMeleeDamageTaken, "minMeleeDamageTaken");
        config.ranking.minMeleeHitsTaken = threshold(config.ranking.minMeleeHitsTaken, "minMeleeHitsTaken");
        config.ranking.minArrowDamageTaken = threshold(config.ranking.minArrowDamageTaken, "minArrowDamageTaken");
        config.ranking.minArrowHitsTaken = threshold(config.ranking.minArrowHitsTaken, "minArrowHitsTaken");
        config.ranking.minArrowsFired = threshold(config.ranking.minArrowsFired, "minArrowsFired");
        config.ranking.minOnlineHours = threshold(config.ranking.minOnlineHours, "minOnlineHours");
        RankingRules rankingRules = new RankingRules(Map.of(
                StatKey.KILL_DEATH_RATIO, config.ranking.minPvpDeaths,
                StatKey.MELEE_DAMAGE_RATIO, config.ranking.minMeleeDamageTaken,
                StatKey.MELEE_HIT_RATIO, config.ranking.minMeleeHitsTaken,
                StatKey.ARROW_DAMAGE_RATIO, config.ranking.minArrowDamageTaken,
                StatKey.ARROW_HIT_RATIO, config.ranking.minArrowHitsTaken,
                StatKey.ARROW_ACCURACY, config.ranking.minArrowsFired,
                StatKey.VOTES_PER_DAY, config.ranking.minOnlineHours * 3_600_000D));
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        for (String world : Objects.requireNonNull(config.tracking.excludedWorlds, "tracking.excludedWorlds")) {
            String name = Objects.requireNonNull(world, "excluded world").trim();
            if (!name.isEmpty()) {
                excluded.add(name.toLowerCase(Locale.ROOT));
            }
        }
        config.tracking.excludedWorlds = new ArrayList<>(excluded);
        Map<StatKey, List<Double>> ladders = new EnumMap<>(StatKey.class);
        for (Map.Entry<String, List<Double>> entry : config.milestones.thresholds.entrySet()) {
            String id = Objects.requireNonNull(entry.getKey(), "milestone statistic");
            StatKey statistic = StatKey.parse(id);
            if (!statistic.id().equals(id)) {
                throw new IllegalArgumentException("milestones.thresholds requires canonical statistic IDs: " + id);
            }
            ladders.put(statistic, entry.getValue());
        }
        MilestoneSettings milestoneSettings = new MilestoneSettings(config.milestones.enabled, ladders);
        config.milestones.thresholds = new LinkedHashMap<>();
        for (StatKey statistic : StatKey.values()) {
            List<Double> thresholds = milestoneSettings.thresholds().get(statistic);
            if (thresholds != null) {
                config.milestones.thresholds.put(statistic.id(), new ArrayList<>(thresholds));
            }
        }
        return new RuntimeConfig(config, config.general.enabled, language, config.tracking.autosaveSeconds,
                config.tracking.leaderboardLimit, excluded, config.tracking.trackCreative,
                config.tracking.trackSpectator, config.hotReload.enabled, config.hotReload.pollIntervalMillis,
                config.hotReload.cooldownMillis, config.hotReload.notifyOperators,
                config.presentation.splashScreen, config.presentation.commandSounds, config.debug.uploadEnabled, rankingRules, historySettings,
                milestoneSettings, config.milestones.notifyPlayers, seasons(config), details(config), dashboardSettings);
    }

    private static DetailSettings details(StaticConfig config) {
        Set<DetailKey> selected = new LinkedHashSet<>();
        for (String id : config.details.selectedKeys) {
            DetailKey key = DetailKey.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown detailed counter: " + id));
            if (!key.id().equals(id) || !selected.add(key)) {
                throw new IllegalArgumentException("Detailed counters require unique canonical identifiers: " + id);
            }
        }
        return new DetailSettings(config.details.enabled, selected);
    }

    private static SeasonSettings seasons(StaticConfig config) {
        List<SeasonDefinition> events = new ArrayList<>();
        for (StaticConfig.Season event : config.seasons.events) {
            StatKey statistic = StatKey.parse(event.statistic);
            if (!statistic.id().equals(event.statistic)) {
                throw new IllegalArgumentException("Season statistic must use its canonical ID");
            }
            if (event.afkSeconds < 5 || event.afkSeconds > 86400) {
                throw new IllegalArgumentException("Season afkSeconds must be between 5 and 86400");
            }
            Set<String> excluded = new LinkedHashSet<>();
            for (String world : event.excludedWorlds) {
                String name = Objects.requireNonNull(world).trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    excluded.add(name);
                }
            }
            event.excludedWorlds = new ArrayList<>(excluded);
            events.add(new SeasonDefinition(event.id, event.name, Instant.parse(event.startsAt), Instant.parse(event.endsAt),
                    new SeasonRules(statistic, event.minimumSample, excluded, event.trackCreative, event.trackSpectator,
                            event.afkSeconds * 1000L)));
        }
        return new SeasonSettings(events);
    }

    private static double threshold(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("ranking." + name + " must be finite");
        }
        return Math.max(0, Math.min(1_000_000_000D, value));
    }
}
