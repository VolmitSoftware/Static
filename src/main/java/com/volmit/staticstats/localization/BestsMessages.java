package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import com.volmit.staticstats.records.PersonalBestPeriod;

import java.util.List;

public final class BestsMessages {
    public static final TextKey TITLE = TextKey.of("bests.title", "&#663D12Personal bests &8| &#333333{player}");
    public static final TextKey HEADER = TextKey.of("bests.header", "<gradient:#FFB454:#FFE39C>{player}'s personal bests</gradient> &7({period}, page {page}/{pages})");
    public static final TextKey OPEN_HINT = TextKey.of("bests.open_hint", "&7Right-click for recorded personal bests.");
    public static final TextKey SCOPE = TextKey.of("bests.scope", "&#FFE39CScope: {period}");
    public static final TextKey SCOPE_HINT = TextKey.of("bests.scope_hint", "&7Click to cycle day, week, month, and session records.");
    public static final TextKey DAY = TextKey.of("bests.period.day", "Day");
    public static final TextKey WEEK = TextKey.of("bests.period.week", "Week");
    public static final TextKey MONTH = TextKey.of("bests.period.month", "Month");
    public static final TextKey SESSION = TextKey.of("bests.period.session", "Session");
    public static final TextKey EMPTY = TextKey.of("bests.empty", "&7No positive personal bests have been recorded for this scope.");
    public static final TextKey TRACKING_SINCE = TextKey.of("bests.tracking_since", "&7Personal-best tracking began: {date}");
    public static final TextKey TRACKING_UNKNOWN = TextKey.of("bests.tracking_unknown", "&7Personal-best tracking has not recorded an observation yet.");
    public static final TextKey COVERAGE = TextKey.of("bests.coverage", "&7Earlier bests use retained history only; older activity may be missing.");
    public static final TextKey RETENTION = TextKey.of("bests.retention", "&7Saved bests remain when source history is pruned.");
    public static final TextKey RECOVERED = TextKey.of("bests.recovered", "&7Recovered from available history when record tracking began.");
    public static final TextKey OBSERVED = TextKey.of("bests.observed", "&7Record value observed: {date}");
    public static final TextKey IN_PROGRESS = TextKey.of("bests.in_progress", "&7The source window or session is ongoing; this best may increase.");
    public static final TextKey COMPLETE = TextKey.of("bests.complete", "&7The source window or session is closed.");
    public static final TextKey SESSION_START = TextKey.of("bests.session_start", "&7Session started: {date}");
    public static final TextKey SESSION_END = TextKey.of("bests.session_end", "&7Last recorded session time: {date}");
    public static final TextKey CALENDAR_ELIGIBLE = TextKey.of("bests.calendar_eligible", "&7Base counters only; ratios and detailed counters are excluded.");
    public static final TextKey SESSION_ELIGIBLE = TextKey.of("bests.session_eligible", "&7Session records cover recorded online, active, and AFK durations.");
    public static final TextKey BACK = TextKey.of("bests.back", "&#FFE39CBack to lifetime profile");
    public static final TextKey UNKNOWN_PERIOD = TextKey.of("bests.unknown_period", "{prefix}&cUnknown record scope: {period}. Choose day, week, month, or session.");
    public static final TextKey INVALID_PAGE = TextKey.of("bests.invalid_page", "{prefix}&cThat page is outside the available personal bests.");
    public static final TextKey FAILED = TextKey.of("bests.failed", "{prefix}&cPersonal bests could not be loaded. Details are in the console.");
    public static final TextKey DESCRIPTION = TextKey.of("command.description.bests", "View recorded personal bests by day, week, month, or session");
    public static final TextKey PARAM_PERIOD = TextKey.of("command.parameter.best_period", "Record scope: day, week, month, or session");
    public static final TextKey PARAM_PAGE = TextKey.of("command.parameter.best_page", "One-based personal bests page");

    private BestsMessages() {
    }

    public static TextKey period(PersonalBestPeriod period) {
        return switch (period) {
            case DAY -> DAY;
            case WEEK -> WEEK;
            case MONTH -> MONTH;
            case SESSION -> SESSION;
        };
    }

    public static List<MessageKey> keys() {
        return List.of(TITLE, HEADER, OPEN_HINT, SCOPE, SCOPE_HINT, DAY, WEEK, MONTH, SESSION, EMPTY,
                TRACKING_SINCE, TRACKING_UNKNOWN, COVERAGE, RETENTION, RECOVERED, OBSERVED, IN_PROGRESS, COMPLETE,
                SESSION_START, SESSION_END, CALENDAR_ELIGIBLE, SESSION_ELIGIBLE, BACK, UNKNOWN_PERIOD, INVALID_PAGE,
                FAILED, DESCRIPTION, PARAM_PERIOD, PARAM_PAGE);
    }
}
