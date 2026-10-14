package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

public final class SeasonMessages {
    public static final TextKey TITLE = TextKey.of("seasons.title", "&#663D12Seasons and events");
    public static final TextKey HEADER = TextKey.of("seasons.header", "<gradient:#FFB454:#FFE39C>Seasons and events</gradient> &7(page {page}/{pages})");
    public static final TextKey NAME = TextKey.of("seasons.name", "&#FFE39C{name} &7({id})");
    public static final TextKey STATUS = TextKey.of("seasons.status", "&7Status: &#F2E7D5{status}");
    public static final TextKey SCHEDULED = TextKey.of("seasons.scheduled", "Scheduled");
    public static final TextKey ACTIVE = TextKey.of("seasons.active", "Active");
    public static final TextKey ARCHIVED = TextKey.of("seasons.archived", "Archived results");
    public static final TextKey SCORE = TextKey.of("seasons.score", "&7Scoring statistic: &#F2E7D5{stat}");
    public static final TextKey PARTICIPANTS = TextKey.of("seasons.participants", "&7Recorded participants: {count}");
    public static final TextKey COVERAGE = TextKey.of("seasons.coverage", "&7Observed since: {date}");
    public static final TextKey NO_COVERAGE = TextKey.of("seasons.no_coverage", "&7Recording has not started.");
    public static final TextKey FROZEN = TextKey.of("seasons.frozen", "&7Results archived: {date} · rules and totals are frozen.");
    public static final TextKey RULES = TextKey.of("seasons.rules", "&7Event rules are fixed when the event begins.");
    public static final TextKey HINT = TextKey.of("seasons.hint", "&7Left-click your progress; right-click rankings.");
    public static final TextKey OPEN_HINT = TextKey.of("seasons.open_hint", "&7Right-click to browse seasons and events.");
    public static final TextKey EMPTY = TextKey.of("seasons.empty", "&7No seasons or events have been configured.");
    public static final TextKey UNKNOWN = TextKey.of("seasons.unknown", "{prefix}&cNo season or event matches {id}.");
    public static final TextKey NO_PROFILE = TextKey.of("seasons.no_profile", "{prefix}&7No event activity is recorded for {player}.");
    public static final TextKey PROFILE = TextKey.of("seasons.profile", "<gradient:#FFB454:#FFE39C>{player} · {name}</gradient>");
    public static final TextKey WINNERS = TextKey.of("seasons.winners", "&#FFE39CFirst place: {players}");
    public static final TextKey NO_WINNERS = TextKey.of("seasons.no_winners", "&7No participants qualified for a winning result.");
    public static final TextKey INVALID_PAGE = TextKey.of("seasons.invalid_page", "{prefix}&cThat page is outside the available seasons.");
    public static final TextKey FAILED = TextKey.of("seasons.failed", "{prefix}&cSeason data could not be loaded. Try again; details are in the console.");
    public static final TextKey DESC_LIST = TextKey.of("command.description.seasons", "Browse scheduled events and archived seasons");
    public static final TextKey DESC_PROFILE = TextKey.of("command.description.season", "View a player's progress in a named event");
    public static final TextKey DESC_TOP = TextKey.of("command.description.seasontop", "View live or archived event rankings");
    public static final TextKey PARAM_ID = TextKey.of("command.parameter.season", "Season or event identifier");
    public static final TextKey PARAM_PAGE = TextKey.of("command.parameter.season_page", "One-based season page");
    public static final TextKey EDITOR = TextKey.of("seasons.editor", "&#663D12Season configuration");
    public static final TextKey CATEGORY = TextKey.of("gui.category.seasons", "&#FFB454Seasons and events&r");
    public static final TextKey ADD = TextKey.of("seasons.add", "&#FFE39CAdd an event draft");
    public static final TextKey EDIT_HINT = TextKey.of("seasons.edit_hint", "&7Click to edit; changes apply only when saved.");
    public static final TextKey LOCKED = TextKey.of("seasons.locked", "&7This event has begun; its definition is fixed.");
    public static final TextKey SAVE = TextKey.of("seasons.save", "&#FFE39CSave event definition");
    public static final TextKey SAVED = TextKey.of("seasons.saved", "{prefix}&aSaved event {id}.");
    public static final TextKey REMOVE = TextKey.of("seasons.remove", "&cRemove this scheduled event");
    public static final TextKey REMOVE_HINT = TextKey.of("seasons.remove_hint", "&7Shift-right-click to remove the saved definition.");
    public static final TextKey REMOVED = TextKey.of("seasons.removed", "{prefix}&aRemoved scheduled event {id}.");
    public static final TextKey DRAFT = TextKey.of("seasons.draft", "&7Unsaved changes stay in this editor until you leave.");
    public static final TextKey DATE_HINT = TextKey.of("seasons.date_hint", "&7Use an ISO instant, for example 2027-01-01T00:00:00Z.");
    public static final TextKey SAMPLE_HINT = TextKey.of("seasons.sample_hint", "&7Ratio denominator minimum; online-time samples use milliseconds.");
    public static final TextKey ID = TextKey.of("seasons.field.id", "Event identifier");
    public static final TextKey DISPLAY_NAME = TextKey.of("seasons.field.name", "Event name");
    public static final TextKey START = TextKey.of("seasons.field.starts_at", "Starts at");
    public static final TextKey END = TextKey.of("seasons.field.ends_at", "Ends at");
    public static final TextKey STATISTIC = TextKey.of("seasons.field.statistic", "Scoring statistic identifier");
    public static final TextKey MINIMUM = TextKey.of("seasons.field.minimum_sample", "Minimum ratio sample");

    private SeasonMessages() {
    }

    public static List<MessageKey> keys() {
        return List.of(TITLE, HEADER, NAME, STATUS, SCHEDULED, ACTIVE, ARCHIVED, SCORE, PARTICIPANTS,
                COVERAGE, NO_COVERAGE, FROZEN, RULES, HINT, OPEN_HINT, EMPTY, UNKNOWN, NO_PROFILE, PROFILE,
                WINNERS, NO_WINNERS, INVALID_PAGE, FAILED, DESC_LIST, DESC_PROFILE, DESC_TOP, PARAM_ID,
                PARAM_PAGE, EDITOR, CATEGORY, ADD, EDIT_HINT, LOCKED, SAVE, SAVED, REMOVE, REMOVE_HINT,
                REMOVED, DRAFT, DATE_HINT, SAMPLE_HINT, ID, DISPLAY_NAME, START, END, STATISTIC, MINIMUM);
    }
}
