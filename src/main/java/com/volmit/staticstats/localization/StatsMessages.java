package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import com.volmit.staticstats.stats.StatKey;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class StatsMessages {
    public static final TextKey PROFILE = TextKey.of("stats.profile", "<gradient:#FFB454:#FFE39C>{player}'s statistics</gradient>");
    public static final TextKey PROFILE_TITLE = TextKey.of("stats.title", "&#663D12Static &8| &#333333{player}");
    public static final TextKey ENTRY = TextKey.of("stats.entry", "&#F2E7D5{stat}&8: &#FFE39C{value}");
    public static final TextKey UNKNOWN_PLAYER = TextKey.of("stats.unknown_player", "{prefix}&cNo recorded player matches {player}.");
    public static final TextKey UNKNOWN_STAT = TextKey.of("stats.unknown_stat", "{prefix}&cUnknown statistic: {stat}. Use tab completion to choose one.");
    public static final TextKey EMPTY = TextKey.of("stats.empty", "{prefix}&7No player profiles have been recorded yet.");
    public static final TextKey INVALID_PAGE = TextKey.of("stats.invalid_page", "{prefix}&cThat page is outside the available rankings.");
    public static final TextKey TOP_HEADER = TextKey.of("stats.top.header", "<gradient:#FFB454:#FFE39C>{stat} rankings</gradient> &7(page {page}/{pages})");
    public static final TextKey TOP_ENTRY = TextKey.of("stats.top.entry", "&#FFB454#{rank} &#F2E7D5{player}&8: &#FFE39C{value}");
    public static final TextKey TOP_HINT = TextKey.of("stats.top.hint", "&7Click to view rankings for this statistic.");
    public static final TextKey TOP_UPDATED = TextKey.of("stats.top.updated", "&7Recorded totals · Updated {date}");
    public static final TextKey TOP_PERSONAL = TextKey.of("stats.top.personal", "&#FFE39CYour rank: #{rank} &7of {players} players &8| &#F2E7D5{value}");
    public static final TextKey TOP_GAP = TextKey.of("stats.top.gap", "&7Gap to the next higher score: &#FFE39C{value}");
    public static final TextKey TOP_LEADING = TextKey.of("stats.top.leading", "&#FFE39CYou share or hold the highest score.");
    public static final TextKey TOP_UNRANKED = TextKey.of("stats.top.unranked", "&7Your profile is not in this ranking snapshot yet.");
    public static final TextKey TOP_NEARBY = TextKey.of("stats.top.nearby", "&#FFE39CPlayers near your position");
    public static final TextKey TOP_FAILED = TextKey.of("stats.top.failed", "{prefix}&cRankings could not be loaded. Try again; details are in the console.");
    public static final TextKey RATIO_NUMERATOR = TextKey.of("stats.ratio.numerator", "&7Numerator · {stat}: &#F2E7D5{value}");
    public static final TextKey RATIO_DENOMINATOR = TextKey.of("stats.ratio.denominator", "&7Denominator · {stat}: &#F2E7D5{value}");
    public static final TextKey RATIO_REQUIREMENT = TextKey.of("stats.ratio.requirement", "&7Ranking minimum · {stat}: &#FFE39C{value}");
    public static final TextKey RATIO_POSITIVE = TextKey.of("stats.ratio.positive", "&7The denominator must also be greater than zero.");
    public static final TextKey RATIO_QUALIFIED = TextKey.of("stats.ratio.qualified", "&#FFE39CQualified for this ranking");
    public static final TextKey RATIO_UNQUALIFIED = TextKey.of("stats.ratio.unqualified", "&7Not qualified for this ranking yet");
    public static final TextKey TOP_EXCLUDED = TextKey.of("stats.top.excluded", "&7Qualified: {qualified}/{total} profiles · Excluded: {excluded}");
    public static final TextKey TOP_NO_QUALIFIED = TextKey.of("stats.top.no_qualified", "&7No recorded profiles meet this ranking's requirements yet.");
    public static final TextKey TOP_PERSONAL_UNQUALIFIED = TextKey.of("stats.top.personal_unqualified", "&#FFE39CYou are not qualified for this ranking yet.");
    public static final TextKey TOP_PERSONAL_PROGRESS = TextKey.of("stats.top.personal_progress", "&7Your {stat}: &#F2E7D5{value} &7/ minimum &#FFE39C{minimum}");
    public static final TextKey PERIOD_LIFETIME = TextKey.of("stats.period.lifetime", "Lifetime");
    public static final TextKey PERIOD_DAY = TextKey.of("stats.period.day", "Today");
    public static final TextKey PERIOD_WEEK = TextKey.of("stats.period.week", "This week");
    public static final TextKey PERIOD_MONTH = TextKey.of("stats.period.month", "This month");
    public static final TextKey PERIOD_SELECT = TextKey.of("stats.period.select", "&#FFE39CPeriod: {period}");
    public static final TextKey PERIOD_HINT = TextKey.of("stats.period.hint", "&7Click to view the next period.");
    public static final TextKey PERIOD_TOTALS = TextKey.of("stats.period.totals", "&#FFE39CRecorded totals · {period}");
    public static final TextKey PERIOD_WINDOW = TextKey.of("stats.period.window", "&7Window: {start} → {end}");
    public static final TextKey PERIOD_ZONE = TextKey.of("stats.period.zone", "&7Calendar time zone: {zone}");
    public static final TextKey PERIOD_PARTIAL = TextKey.of("stats.period.partial", "&7Partial coverage: recording began during this window.");
    public static final TextKey PERIOD_UNKNOWN = TextKey.of("stats.period.unknown", "&7Coverage is unknown; earlier activity is not reconstructed.");
    public static final TextKey PERIOD_COMPLETE = TextKey.of("stats.period.complete", "&7Recording covers the start of this window.");
    public static final TextKey UNKNOWN_PERIOD = TextKey.of("stats.period.invalid", "{prefix}&cUnknown period: {period}. Choose lifetime, day, week, or month.");
    public static final TextKey ACTIVITY_HINT = TextKey.of("stats.activity.hint", "&7Activity uses detected game input, not proof of attention.");
    public static final TextKey ACTIVITY_COVERAGE = TextKey.of("stats.activity.coverage", "&7Activity and AFK time begin with activity tracking.");
    public static final TextKey ACTIVITY_SINCE = TextKey.of("stats.activity.since", "&7Activity tracking since: {date}");
    public static final TextKey SESSIONS_HEADER = TextKey.of("stats.sessions.header", "<gradient:#FFB454:#FFE39C>{player}'s recent sessions</gradient> &7(page {page}/{pages})");
    public static final TextKey SESSIONS_ROW = TextKey.of("stats.sessions.row", "&#FFE39C#{number} &#F2E7D5{start} &7→ {end}");
    public static final TextKey SESSIONS_TIME = TextKey.of("stats.sessions.time", "&7Online: {online} · Active: {active} · AFK: {afk}");
    public static final TextKey SESSIONS_ONGOING = TextKey.of("stats.sessions.ongoing", "In progress");
    public static final TextKey SESSIONS_INTERRUPTED = TextKey.of("stats.sessions.interrupted", "&7End time is the last saved checkpoint; disconnect time is unknown.");
    public static final TextKey SESSIONS_EMPTY = TextKey.of("stats.sessions.empty", "{prefix}&7No recent sessions have been recorded for {player}.");
    public static final TextKey SESSIONS_INVALID_PAGE = TextKey.of("stats.sessions.invalid_page", "{prefix}&cThat page is outside the available sessions.");
    public static final TextKey SESSIONS_SCOPE = TextKey.of("stats.sessions.scope", "&7Recent retained sessions only; earlier sessions are not reconstructed.");
    public static final TextKey DESC_SESSIONS = TextKey.of("command.description.sessions", "View a recorded player's recent sessions");
    public static final TextKey PARAM_PERIOD = TextKey.of("command.parameter.period", "Calendar period: lifetime, day, week, or month");
    public static final TextKey PARAM_SESSION_PAGE = TextKey.of("command.parameter.session_page", "One-based recent sessions page");
    public static final TextKey CATEGORY = TextKey.of("stats.category", "&#FFE39CCategory: {category}");
    public static final TextKey CATEGORY_HINT = TextKey.of("stats.category.hint", "&7Click to show the next category.");
    public static final TextKey CATEGORY_ALL = TextKey.of("stats.category.all", "All statistics");
    public static final TextKey CATEGORY_BUILDING = TextKey.of("stats.category.building", "Building");
    public static final TextKey CATEGORY_CONNECTIONS = TextKey.of("stats.category.connections", "Connections");
    public static final TextKey CATEGORY_COMBAT = TextKey.of("stats.category.combat", "Combat");
    public static final TextKey CATEGORY_ARCHERY = TextKey.of("stats.category.archery", "Archery");
    public static final TextKey CATEGORY_SOCIAL = TextKey.of("stats.category.social", "Social");
    public static final TextKey CATEGORY_MOVEMENT = TextKey.of("stats.category.movement", "Movement");
    public static final TextKey FAVORITES = TextKey.of("stats.favorites", "&#FFE39CYour favorites &7({count}/{limit})");
    public static final TextKey FAVORITES_SHOW = TextKey.of("stats.favorites.show", "&7Click to show your favorite statistics.");
    public static final TextKey FAVORITES_BACK = TextKey.of("stats.favorites.back", "&7Click to return to all statistics.");
    public static final TextKey FAVORITES_ADD = TextKey.of("stats.favorites.add", "&7Right-click to add to your favorites.");
    public static final TextKey FAVORITES_REMOVE = TextKey.of("stats.favorites.remove", "&#FFE39CFavorite &7· Right-click to remove.");
    public static final TextKey FAVORITES_EMPTY = TextKey.of("stats.favorites.empty", "&#FFE39CNo favorite statistics yet");
    public static final TextKey FAVORITES_EMPTY_HINT = TextKey.of("stats.favorites.empty_hint", "&7Return to all statistics and right-click one.");
    public static final TextKey FAVORITES_LIMIT = TextKey.of("stats.favorites.limit", "{prefix}&cYou can save up to {limit} favorites. Remove one before adding another.");
    public static final TextKey FAVORITES_FAILED = TextKey.of("stats.favorites.failed", "{prefix}&cFavorites could not be saved. Your previous selection is unchanged; check the console.");
    public static final TextKey FAVORITES_UNAVAILABLE = TextKey.of("stats.favorites.unavailable", "&7Favorites are unavailable. Check the console.");
    public static final TextKey COVERAGE = TextKey.of("stats.coverage", "&#FFE39CRecorded statistics");
    public static final TextKey COVERAGE_SINCE = TextKey.of("stats.coverage.since", "&7Recorded since: &#F2E7D5{date}");
    public static final TextKey COVERAGE_UNKNOWN = TextKey.of("stats.coverage.unknown", "&7Recording start: unknown");
    public static final TextKey COVERAGE_SCOPE = TextKey.of("stats.coverage.scope", "&7Totals include activity recorded by Static.");
    public static final TextKey COVERAGE_HISTORY = TextKey.of("stats.coverage.history", "&7Earlier activity is not reconstructed.");
    public static final TextKey REFRESH = TextKey.of("stats.refresh", "&#FFE39CRefresh statistics");
    public static final TextKey CLOSE = TextKey.of("stats.close", "&cClose");
    public static final TextKey PREVIOUS = TextKey.of("stats.previous", "&#FFE39CPrevious page");
    public static final TextKey NEXT = TextKey.of("stats.next", "&#FFE39CNext page");
    public static final TextKey STATUS = TextKey.of("stats.status", "{prefix}&7Tracking: &f{enabled}&7. Profiles: &f{players}&7. Autosave: &f{seconds}s&7.");
    public static final TextKey RELOAD_OK = TextKey.of("stats.reload.success", "{prefix}&aReloaded Static configuration and language files.");
    public static final TextKey RELOAD_FAILED = TextKey.of("stats.reload.failed", "{prefix}&cReload was not applied. Check the console; the last valid settings remain active.");
    public static final TextKey DESC_STATS = TextKey.of("command.description.stats", "View a recorded player's statistics");
    public static final TextKey DESC_TOP = TextKey.of("command.description.top", "Rank recorded players by a statistic");
    public static final TextKey DESC_RELOAD = TextKey.of("command.description.reload", "Reload configuration and installed language files");
    public static final TextKey DESC_DUMP = TextKey.of("command.description.debug_dump", "Create a diagnostic report");
    public static final TextKey PARAM_PLAYER = TextKey.of("command.parameter.player", "Recorded player name or UUID; self by default");
    public static final TextKey PARAM_STAT = TextKey.of("command.parameter.stat", "Statistic identifier");
    public static final TextKey PARAM_PAGE = TextKey.of("command.parameter.page", "One-based ranking page");
    private static final Map<StatKey, TextKey> LABELS = labels();

    private StatsMessages() {
    }

    public static TextKey label(StatKey stat) {
        return LABELS.get(stat);
    }

    public static List<MessageKey> keys() {
        ArrayList<MessageKey> keys = new ArrayList<>(List.of(PROFILE, PROFILE_TITLE, ENTRY, UNKNOWN_PLAYER,
                UNKNOWN_STAT, EMPTY, INVALID_PAGE, TOP_HEADER, TOP_ENTRY, TOP_HINT, REFRESH, CLOSE, PREVIOUS,
                NEXT, STATUS, RELOAD_OK, RELOAD_FAILED, DESC_STATS, DESC_TOP, DESC_RELOAD, DESC_DUMP,
                PARAM_PLAYER, PARAM_STAT, PARAM_PAGE, TOP_UPDATED, TOP_PERSONAL, TOP_GAP, TOP_LEADING,
                TOP_UNRANKED, TOP_NEARBY, TOP_FAILED, CATEGORY, CATEGORY_HINT, CATEGORY_ALL,
                CATEGORY_BUILDING, CATEGORY_CONNECTIONS, CATEGORY_COMBAT, CATEGORY_ARCHERY,
                CATEGORY_SOCIAL, CATEGORY_MOVEMENT, FAVORITES, FAVORITES_SHOW, FAVORITES_BACK,
                FAVORITES_ADD, FAVORITES_REMOVE, FAVORITES_EMPTY, FAVORITES_EMPTY_HINT,
                FAVORITES_LIMIT, FAVORITES_FAILED, FAVORITES_UNAVAILABLE, COVERAGE, COVERAGE_SINCE,
                COVERAGE_UNKNOWN, COVERAGE_SCOPE, COVERAGE_HISTORY, RATIO_NUMERATOR, RATIO_DENOMINATOR,
                RATIO_REQUIREMENT, RATIO_POSITIVE, RATIO_QUALIFIED, RATIO_UNQUALIFIED, TOP_EXCLUDED,
                TOP_NO_QUALIFIED, TOP_PERSONAL_UNQUALIFIED, TOP_PERSONAL_PROGRESS, PERIOD_LIFETIME,
                PERIOD_DAY, PERIOD_WEEK, PERIOD_MONTH, PERIOD_SELECT, PERIOD_HINT, PERIOD_TOTALS,
                PERIOD_WINDOW, PERIOD_ZONE, PERIOD_PARTIAL, PERIOD_UNKNOWN, PERIOD_COMPLETE, UNKNOWN_PERIOD,
                ACTIVITY_HINT, ACTIVITY_COVERAGE, SESSIONS_HEADER, SESSIONS_ROW, SESSIONS_TIME,
                SESSIONS_ONGOING, SESSIONS_INTERRUPTED, SESSIONS_EMPTY, SESSIONS_SCOPE, DESC_SESSIONS, PARAM_PERIOD,
                ACTIVITY_SINCE, PARAM_SESSION_PAGE, SESSIONS_INVALID_PAGE));
        keys.addAll(LABELS.values());
        return List.copyOf(keys);
    }

    private static Map<StatKey, TextKey> labels() {
        EnumMap<StatKey, TextKey> labels = new EnumMap<>(StatKey.class);
        for (StatKey stat : StatKey.values()) {
            labels.put(stat, TextKey.of("stat." + stat.id(), stat.displayName()));
        }
        return Map.copyOf(labels);
    }
}
