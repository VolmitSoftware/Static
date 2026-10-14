package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import com.volmit.staticstats.stats.StatsPeriod;

import java.util.List;

public final class ParticipationMessages {
    public static final TextKey HEADER = TextKey.of("participation.header", "<gradient:#FFB454:#FFE39C>Server participation · {period}</gradient> &7(offset {offset})");
    public static final TextKey DAY = TextKey.of("participation.period.day", "Day");
    public static final TextKey WEEK = TextKey.of("participation.period.week", "Week");
    public static final TextKey MONTH = TextKey.of("participation.period.month", "Month");
    public static final TextKey PLAYERS = TextKey.of("participation.players", "&#FFE39CUnique players with retained sessions: {players}");
    public static final TextKey COHORTS = TextKey.of("participation.cohorts", "&7First observed in window: {new} · Returning: {returning} · Unknown history: {unknown}");
    public static final TextKey MEDIAN = TextKey.of("participation.median", "&7Quit sessions ending in window: {count} · Median eligible duration: &#FFE39C{duration}");
    public static final TextKey MEDIAN_EMPTY = TextKey.of("participation.median_empty", "&7No retained completed quit sessions ended in this window.");
    public static final TextKey OTHER_SESSIONS = TextKey.of("participation.other_sessions", "&7Overlapping sessions · Open: {open} · Shutdown: {shutdown} · Interrupted: {interrupted}");
    public static final TextKey EARLIEST = TextKey.of("participation.earliest", "&7Earliest retained observation in window: {date}");
    public static final TextKey EMPTY = TextKey.of("participation.empty", "&7No retained sessions overlap this window; this does not prove there were no players.");
    public static final TextKey BASIS = TextKey.of("participation.basis", "&7Participation counts connections, including time excluded from statistics.");
    public static final TextKey RETAINED = TextKey.of("participation.retained", "&7Retained session history only; older activity may be missing.");
    public static final TextKey RETENTION_LIMITED = TextKey.of("participation.retention_limited", "&7Session retention can limit this window.");
    public static final TextKey MEDIAN_SCOPE = TextKey.of("participation.median_scope", "&7Median uses each retained quit session's full eligible duration.");
    public static final TextKey LOADING = TextKey.of("participation.loading", "{prefix}&7Loading participation summary...");
    public static final TextKey WARMING = TextKey.of("participation.warming", "{prefix}&7Participation summary is loading. Run this command again shortly.");
    public static final TextKey FAILED = TextKey.of("participation.failed", "{prefix}&cParticipation summary could not be loaded. Try again; details are in the console.");
    public static final TextKey UNKNOWN_PERIOD = TextKey.of("participation.unknown_period", "{prefix}&cUnknown participation period: {period}. Choose day, week, or month.");
    public static final TextKey INVALID_OFFSET = TextKey.of("participation.invalid_offset", "{prefix}&cOffset must be between 0 and {max} for this period's configured retention.");
    public static final TextKey DESCRIPTION = TextKey.of("command.description.participation", "Summarize retained server participation by calendar window");
    public static final TextKey PARAM_PERIOD = TextKey.of("command.parameter.participation_period", "Participation period: day, week, or month");
    public static final TextKey PARAM_OFFSET = TextKey.of("command.parameter.participation_offset", "Windows before the current one; zero by default");

    private ParticipationMessages() {
    }

    public static TextKey period(StatsPeriod period) {
        return switch (period) {
            case DAY -> DAY;
            case WEEK -> WEEK;
            case MONTH -> MONTH;
            case LIFETIME -> throw new IllegalArgumentException("Participation requires a calendar period");
        };
    }

    public static List<MessageKey> keys() {
        return List.of(HEADER, DAY, WEEK, MONTH, PLAYERS, COHORTS, MEDIAN, MEDIAN_EMPTY, OTHER_SESSIONS,
                EARLIEST, EMPTY, BASIS, RETAINED, RETENTION_LIMITED, MEDIAN_SCOPE, LOADING, WARMING, FAILED,
                UNKNOWN_PERIOD, INVALID_OFFSET, DESCRIPTION, PARAM_PERIOD, PARAM_OFFSET);
    }
}
