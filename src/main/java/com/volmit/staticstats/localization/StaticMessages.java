package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.diagnostics.BukkitDebugMessages;
import art.arcane.volmlib.util.director.DirectorMessages;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

public final class StaticMessages {
    public static final TextKey PREFIX = TextKey.of("runtime.prefix", "<gradient:#FFB454:#FFE39C><bold>Static</bold></gradient> &8›&r ");
    public static final TextKey NO_PERMISSION = prefixed("runtime.permission.denied", "&cYou do not have permission to do that.&r");
    public static final TextKey PLAYER_ONLY = prefixed("runtime.player_only", "&cThis command can only be used by a player.&r");
    public static final TextKey HOT_RELOAD_SUCCESS = prefixed("runtime.hot_reload.success", "&aApplied configuration and language file changes.&r");
    public static final TextKey HOT_RELOAD_FAILED = prefixed("runtime.hot_reload.failed", "&cRejected file changes; the last known good settings remain active.&r");
    public static final TextKey COMMAND_CONFIG_OPENED = prefixed("command.feedback.config.opened", "&aOpened the complete in-game configuration editor.&r");
    public static final TextKey UNKNOWN_COMMAND = prefixed("command.unknown", "&cUnknown command, use /static for help!&r");
    public static final TextKey COMMAND_FAILED = prefixed("command.feedback.failed", "&cThe command could not be completed. See the console for details.&r");
    public static final TextKey CONFIG_SAVED = prefixed("command.feedback.config.saved", "&a{setting}&r &7changed from &f{old}&r &7to &f{new}&r&7.&r");
    public static final TextKey CONFIG_SAVE_FAILED = prefixed("command.feedback.config.save_failed", "&cCould not apply &f{setting}&r&c: {reason}&r");
    public static final TextKey STATUS_HEADER = prefixed("command.status.header", "&#FFB454&lStatic runtime&r");
    public static final TextKey STATUS_COMPATIBILITY = prefixed("command.status.compatibility", "&7Artifact:&r &fJava 25+ / Bukkit 26.1-26.3&r &8|&r &7Scheduler:&r &f{scheduler}&r");
    public static final TextKey GUI_ROOT_TITLE = TextKey.of("gui.title.root", "&#663D12Static Configuration&r");
    public static final TextKey GUI_CATEGORY_TITLE = TextKey.of("gui.title.category", "&#663D12Static&r &8›&#333333 {category}");
    public static final TextKey GUI_CATEGORY_GENERAL = TextKey.of("gui.category.general", "&#FFB454General&r");
    public static final TextKey GUI_CATEGORY_HOT_RELOAD = TextKey.of("gui.category.hot_reload", "&#FFB454Hot Reload&r");
    public static final TextKey GUI_CATEGORY_PRESENTATION = TextKey.of("gui.category.presentation", "&#FFB454Presentation&r");
    public static final TextKey GUI_CATEGORY_DEBUG = TextKey.of("gui.category.debug", "&#FFB454Diagnostics&r");
    public static final TextKey GUI_CATEGORY_LANGUAGES = TextKey.of("gui.category.languages", "&#FFB454Languages&r");
    public static final TextKey GUI_BACK = TextKey.of("gui.navigation.back", "&eBack&r");
    public static final TextKey GUI_CLOSE = TextKey.of("gui.navigation.close", "&cClose&r");
    public static final TextKey GUI_STATE = TextKey.of("gui.lore.state", "&7Current:&r {value}");
    public static final TextKey GUI_ENABLED = TextKey.of("gui.value.enabled", "&aEnabled&r");
    public static final TextKey GUI_DISABLED = TextKey.of("gui.value.disabled", "&cDisabled&r");
    public static final TextKey GUI_TOGGLE = TextKey.of("gui.lore.toggle", "&7Click to toggle.&r");
    public static final TextKey GUI_NUMBER = TextKey.of("gui.lore.number", "&7Left +1, right -1, shift ×10, drop to type.&r");
    public static final TextKey GUI_TEXT = TextKey.of("gui.lore.text", "&7Click to type a new value in chat.&r");
    public static final TextKey GUI_LANGUAGE_SELECT = TextKey.of("gui.lore.language", "&7Click to choose an available language.&r");
    public static final TextKey GUI_CATEGORY_OPEN = TextKey.of("gui.lore.category", "&7Click to edit every setting in this category.&r");
    public static final TextKey GUI_PROMPT = prefixed("gui.prompt.request", "&eType a new value for &f{setting}&r&e in chat.&r");
    public static final TextKey GUI_PROMPT_LIST = prefixed("gui.prompt.list", "&7Separate list entries with commas. Type &fnone&r&7 for an empty list.&r");
    public static final TextKey GUI_PROMPT_CANCEL = prefixed("gui.prompt.cancel", "&7Type &fcancel&r&7 to stop. This prompt expires in 60 seconds.&r");
    public static final TextKey GUI_PROMPT_CANCELLED = prefixed("gui.prompt.cancelled", "&eConfiguration edit cancelled.&r");
    public static final TextKey GUI_PROMPT_TIMEOUT = prefixed("gui.prompt.timeout", "&eConfiguration edit expired without changing anything.&r");
    public static final TextKey SETTING_GENERAL_ENABLED = setting("general.enabled", "Statistics tracking");
    public static final TextKey SETTING_GENERAL_LANGUAGE = setting("general.language", "Active language locale");
    public static final TextKey SETTING_HOT_RELOAD_ENABLED = setting("hot_reload.enabled", "Hot reload");
    public static final TextKey SETTING_HOT_RELOAD_POLL = setting("hot_reload.poll_interval_millis", "Poll interval milliseconds");
    public static final TextKey SETTING_HOT_RELOAD_COOLDOWN = setting("hot_reload.cooldown_millis", "Save cooldown milliseconds");
    public static final TextKey SETTING_HOT_RELOAD_NOTIFY = setting("hot_reload.notify_operators", "Notify operators");
    public static final TextKey SETTING_PRESENTATION_SPLASH = setting("presentation.splash_screen", "Startup splash screen");
    public static final TextKey SETTING_PRESENTATION_SOUNDS = setting("presentation.command_sounds", "Command sounds");
    public static final TextKey SETTING_DEBUG_UPLOAD = setting("debug.upload_enabled", "Upload debug reports to mclo.gs");
    public static final TextKey COMMAND_ROOT = TextKey.of("command.description.root", "Player statistics and Static administration");
    public static final TextKey COMMAND_CONFIG = TextKey.of("command.description.config", "Open the complete in-game configuration editor");
    public static final TextKey COMMAND_LANGUAGE = TextKey.of("command.description.language", "Select an available Static language");
    public static final TextKey COMMAND_DEBUG = TextKey.of("command.description.debug", "Static diagnostic tools");
    public static final TextKey COMMAND_VERSION = TextKey.of("command.description.version", "Show the installed Static version");
    public static final TextKey COMMAND_STATUS = TextKey.of("command.description.status", "Show Static runtime status");
    public static final TextKey PARAMETER_UPLOAD = TextKey.of("command.parameter.upload", "Upload the report when public uploads are enabled");
    public static final TextKey GUI_CATEGORY_TRACKING = TextKey.of("gui.category.tracking", "&#FFB454Tracking&r");
    public static final TextKey GUI_CATEGORY_RANKING = TextKey.of("gui.category.ranking", "&#FFB454Ranking minimums&r");
    public static final TextKey GUI_CATEGORY_HISTORY = TextKey.of("gui.category.history", "&#FFB454History and activity&r");
    public static final TextKey SETTING_HISTORY_ZONE = setting("history.time_zone", "Calendar time zone");
    public static final TextKey SETTING_HISTORY_AFK = setting("history.afk_seconds", "Seconds without input before AFK");
    public static final TextKey SETTING_HISTORY_DAYS = setting("history.retained_days", "Retained daily windows");
    public static final TextKey SETTING_HISTORY_WEEKS = setting("history.retained_weeks", "Retained weekly windows");
    public static final TextKey SETTING_HISTORY_MONTHS = setting("history.retained_months", "Retained monthly windows");
    public static final TextKey SETTING_HISTORY_SESSIONS = setting("history.sessions_per_player", "Retained sessions per player");
    public static final TextKey SETTING_RANKING_PVP_DEATHS = setting("ranking.min_pvp_deaths", "Minimum player combat deaths");
    public static final TextKey SETTING_RANKING_MELEE_DAMAGE = setting("ranking.min_melee_damage_taken", "Minimum melee damage taken");
    public static final TextKey SETTING_RANKING_MELEE_HITS = setting("ranking.min_melee_hits_taken", "Minimum melee hits taken");
    public static final TextKey SETTING_RANKING_ARROW_DAMAGE = setting("ranking.min_arrow_damage_taken", "Minimum arrow damage taken");
    public static final TextKey SETTING_RANKING_ARROW_HITS = setting("ranking.min_arrow_hits_taken", "Minimum arrow hits taken");
    public static final TextKey SETTING_RANKING_ARROWS_FIRED = setting("ranking.min_arrows_fired", "Minimum arrows fired");
    public static final TextKey SETTING_RANKING_ONLINE_HOURS = setting("ranking.min_online_hours", "Minimum online hours");
    public static final TextKey SETTING_TRACKING_AUTOSAVE = setting("tracking.autosave_seconds", "Autosave seconds");
    public static final TextKey SETTING_TRACKING_LEADERBOARD = setting("tracking.leaderboard_limit", "Leaderboard entries per page");
    public static final TextKey SETTING_TRACKING_EXCLUDED_WORLDS = setting("tracking.excluded_worlds", "Excluded worlds");
    public static final TextKey SETTING_TRACKING_CREATIVE = setting("tracking.track_creative", "Track creative mode");
    public static final TextKey SETTING_TRACKING_SPECTATOR = setting("tracking.track_spectator", "Track spectator mode");

    private StaticMessages() {
    }

    public static MessageCatalog catalog() {
        MessageCatalog.Builder builder = MessageCatalog.builder("en_US");
        builder.addAll(List.of(
                PREFIX, UNKNOWN_COMMAND, NO_PERMISSION, PLAYER_ONLY, HOT_RELOAD_SUCCESS, HOT_RELOAD_FAILED,
                COMMAND_CONFIG_OPENED, COMMAND_FAILED, CONFIG_SAVED, CONFIG_SAVE_FAILED, STATUS_HEADER,
                STATUS_COMPATIBILITY, GUI_ROOT_TITLE, GUI_CATEGORY_TITLE, GUI_CATEGORY_GENERAL, GUI_CATEGORY_HOT_RELOAD,
                GUI_CATEGORY_PRESENTATION, GUI_CATEGORY_DEBUG, GUI_CATEGORY_LANGUAGES, GUI_BACK, GUI_CLOSE,
                GUI_STATE, GUI_ENABLED, GUI_DISABLED, GUI_TOGGLE, GUI_NUMBER, GUI_TEXT, GUI_LANGUAGE_SELECT,
                GUI_CATEGORY_OPEN, GUI_PROMPT, GUI_PROMPT_LIST, GUI_PROMPT_CANCEL, GUI_PROMPT_CANCELLED,
                GUI_PROMPT_TIMEOUT, SETTING_GENERAL_ENABLED, SETTING_GENERAL_LANGUAGE,
                SETTING_HOT_RELOAD_ENABLED, SETTING_HOT_RELOAD_POLL, SETTING_HOT_RELOAD_COOLDOWN, SETTING_HOT_RELOAD_NOTIFY, SETTING_PRESENTATION_SPLASH,
                SETTING_PRESENTATION_SOUNDS, SETTING_DEBUG_UPLOAD, COMMAND_ROOT, COMMAND_CONFIG, COMMAND_LANGUAGE,
                COMMAND_DEBUG, COMMAND_VERSION, COMMAND_STATUS, PARAMETER_UPLOAD, GUI_CATEGORY_TRACKING, SETTING_TRACKING_AUTOSAVE,
                SETTING_TRACKING_LEADERBOARD, SETTING_TRACKING_EXCLUDED_WORLDS, SETTING_TRACKING_CREATIVE, SETTING_TRACKING_SPECTATOR,
                GUI_CATEGORY_RANKING, SETTING_RANKING_PVP_DEATHS, SETTING_RANKING_MELEE_DAMAGE,
                SETTING_RANKING_MELEE_HITS, SETTING_RANKING_ARROW_DAMAGE, SETTING_RANKING_ARROW_HITS,
                SETTING_RANKING_ARROWS_FIRED, SETTING_RANKING_ONLINE_HOURS,
                GUI_CATEGORY_HISTORY, SETTING_HISTORY_ZONE, SETTING_HISTORY_AFK, SETTING_HISTORY_DAYS,
                SETTING_HISTORY_WEEKS, SETTING_HISTORY_MONTHS, SETTING_HISTORY_SESSIONS
        ));
        builder.addAll(StatsMessages.keys());
        builder.addAll(MilestoneMessages.keys());
        builder.addAll(SeasonMessages.keys());
        builder.addAll(DetailMessages.keys());
        builder.addAll(BestsMessages.keys());
        builder.addAll(ParticipationMessages.keys());
        builder.addAll(DashboardMessages.keys());
        builder.addAll(PrivacyMessages.keys());
        builder.addAll(FormatMessages.keys());
        builder.addAll(DirectorMessages.keys());
        builder.addAll(BukkitLanguageMessages.keys());
        builder.addAll(BukkitDebugMessages.keys());
        return builder.build();
    }

    private static TextKey setting(String id, String english) {
        return TextKey.of("gui.setting." + id, "&#FFB454" + english + "&r");
    }

    private static TextKey prefixed(String id, String english) {
        return TextKey.ofOptional(id, "{prefix}" + english, "prefix");
    }
}
