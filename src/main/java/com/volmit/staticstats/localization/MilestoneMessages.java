package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

public final class MilestoneMessages {
    public static final TextKey TITLE = TextKey.of("milestones.title", "&#663D12Milestones &8| &#333333{player}");
    public static final TextKey HEADER = TextKey.of("milestones.header", "<gradient:#FFB454:#FFE39C>{player}'s milestones</gradient> &7(page {page}/{pages})");
    public static final TextKey ENTRY = TextKey.of("milestones.entry", "&#FFE39C{stat} · {target}");
    public static final TextKey PROGRESS = TextKey.of("milestones.progress", "&7Progress: &#F2E7D5{value} &7/ &#FFE39C{target}");
    public static final TextKey COMPLETE = TextKey.of("milestones.complete", "&#FFE39CComplete · recorded {date}");
    public static final TextKey IN_PROGRESS = TextKey.of("milestones.in_progress", "&7In progress");
    public static final TextKey NEXT_TARGET = TextKey.of("milestones.next_target", "&#FFE39CNext target in this statistic");
    public static final TextKey HISTORICAL = TextKey.of("milestones.historical", "&7Already met when first checked.");
    public static final TextKey ARCHIVED = TextKey.of("milestones.archived", "&7Target removed; the badge is retained.");
    public static final TextKey EMPTY = TextKey.of("milestones.empty", "&7No milestone targets or badges to show.");
    public static final TextKey PAUSED = TextKey.of("milestones.paused", "&7Milestone tracking is paused.");
    public static final TextKey SUMMARY = TextKey.of("milestones.summary", "&#FFE39CBadges: {completed} &7· Active targets: {targets}");
    public static final TextKey SCOPE = TextKey.of("milestones.scope", "&7Lifetime totals · cosmetic badges");
    public static final TextKey OPEN = TextKey.of("milestones.open", "&#FFE39CMilestones and badges");
    public static final TextKey OPEN_HINT = TextKey.of("milestones.open_hint", "&7Click to view lifetime milestone progress.");
    public static final TextKey BACK = TextKey.of("milestones.back", "&#FFE39CBack to statistics");
    public static final TextKey REFRESH = TextKey.of("milestones.refresh", "&#FFE39CRefresh milestones");
    public static final TextKey INVALID_PAGE = TextKey.of("milestones.invalid_page", "{prefix}&cThat page is outside the available milestones.");
    public static final TextKey FAILED = TextKey.of("milestones.failed", "{prefix}&cMilestones could not be loaded. Try again; details are in the console.");
    public static final TextKey NOTIFICATION = TextKey.of("milestones.notification", "{prefix}&#FFE39CMilestone complete: &#F2E7D5{stat} · {target}");
    public static final TextKey DESCRIPTION = TextKey.of("command.description.milestones", "View lifetime milestone progress and badges");
    public static final TextKey PARAM_PAGE = TextKey.of("command.parameter.milestone_page", "One-based milestone page");
    public static final TextKey CATEGORY = TextKey.of("gui.category.milestones", "&#FFB454Milestones&r");
    public static final TextKey ENABLED = TextKey.of("setting.milestones.enabled", "Track cosmetic milestones");
    public static final TextKey NOTIFY = TextKey.of("setting.milestones.notify_players", "Private completion messages");
    public static final TextKey TARGET_HINT = TextKey.of("milestones.target_hint", "&7Comma-separated positive targets; none removes this ladder.");
    public static final TextKey TIME_HINT = TextKey.of("milestones.time_hint", "&7Time targets use milliseconds: 3600000 = 1 hour.");
    public static final TextKey MILLISECONDS = TextKey.of("milestones.milliseconds", "{value} ms");

    private MilestoneMessages() {
    }

    public static List<MessageKey> keys() {
        return List.of(TITLE, HEADER, ENTRY, PROGRESS, COMPLETE, IN_PROGRESS, NEXT_TARGET, HISTORICAL, ARCHIVED,
                EMPTY, PAUSED, SUMMARY, SCOPE, OPEN, OPEN_HINT, BACK, REFRESH, INVALID_PAGE, FAILED,
                NOTIFICATION, DESCRIPTION, PARAM_PAGE, CATEGORY, ENABLED, NOTIFY, TARGET_HINT, TIME_HINT, MILLISECONDS);
    }
}
