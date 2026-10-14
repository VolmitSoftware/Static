package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.ArrayList;
import java.util.List;

public final class DashboardMessages {
    public static final TextKey SETTING_ENABLED = TextKey.of("dashboard.setting-enabled", "Web dashboard");
    public static final TextKey SETTING_PORT = TextKey.of("dashboard.setting-port", "Dashboard port");
    public static final TextKey SETTING_ORIGIN = TextKey.of("dashboard.setting-origin", "Dashboard public origin");
    public static final TextKey SETTING_PUBLIC_PROFILES = TextKey.of("dashboard.setting-public-profiles", "Public player pages");
    private static final List<MessageKey> LABELS = List.of(
            TextKey.of("dashboard.skip", "Skip to report"),
            TextKey.of("dashboard.operator", "Operator dashboard"),
            TextKey.of("dashboard.logout", "Sign out"),
            TextKey.of("dashboard.readOnly", "Read-only server reports"),
            TextKey.of("dashboard.signIn", "Open your dashboard"),
            TextKey.of("dashboard.tokenHint", "Enter the access token from your server's dashboard/access-token file."),
            TextKey.of("dashboard.token", "Access token"),
            TextKey.of("dashboard.tokenNote", "The token stays in this tab's memory. Closing or reloading the page signs you out."),
            TextKey.of("dashboard.connect", "Connect"),
            TextKey.of("dashboard.overview", "Participation"),
            TextKey.of("dashboard.players", "Players"),
            TextKey.of("dashboard.rankings", "Rankings"),
            TextKey.of("dashboard.refresh", "Refresh report"),
            TextKey.of("dashboard.period", "Period"),
            TextKey.of("dashboard.day", "Day"),
            TextKey.of("dashboard.week", "Week"),
            TextKey.of("dashboard.month", "Month"),
            TextKey.of("dashboard.session", "Session"),
            TextKey.of("dashboard.lifetime", "Lifetime"),
            TextKey.of("dashboard.offset", "Windows ago"),
            TextKey.of("dashboard.apply", "Apply"),
            TextKey.of("dashboard.search", "Player name or UUID"),
            TextKey.of("dashboard.searchAction", "Search players"),
            TextKey.of("dashboard.statistic", "Statistic"),
            TextKey.of("dashboard.previous", "Previous page"),
            TextKey.of("dashboard.next", "Next page"),
            TextKey.of("dashboard.footer", "Statistics collected by this server. Retention and tracking settings affect these reports."),
            TextKey.of("dashboard.loading", "Loading report..."),
            TextKey.of("dashboard.connecting", "Connecting to your server..."),
            TextKey.of("dashboard.expired", "Access was rejected or revoked. Enter a valid token to reconnect."),
            TextKey.of("dashboard.failed", "The report could not be loaded. Check the connection and refresh the report."),
            TextKey.of("dashboard.invalid", "These filters are outside the server's configured limits. Adjust them and try again."),
            TextKey.of("dashboard.limited", "Too many requests. Wait a moment, then refresh the report."),
            TextKey.of("dashboard.signedOut", "Signed out. Report data and the token have been cleared from this tab."),
            TextKey.of("dashboard.unique", "Unique players"),
            TextKey.of("dashboard.newPlayers", "First observed in this window"),
            TextKey.of("dashboard.returning", "Returning players"),
            TextKey.of("dashboard.unknown", "Unknown earlier history"),
            TextKey.of("dashboard.sessions", "Retained sessions"),
            TextKey.of("dashboard.median", "Median eligible duration"),
            TextKey.of("dashboard.samples", "Completed quit sessions"),
            TextKey.of("dashboard.open", "Open"),
            TextKey.of("dashboard.shutdown", "Shutdown"),
            TextKey.of("dashboard.interrupted", "Interrupted"),
            TextKey.of("dashboard.medianScope", "The median uses each retained quit session's full eligible duration when it ends in this window. Open, shutdown and interrupted sessions are excluded."),
            TextKey.of("dashboard.retained", "Retained session history only. Older activity may be missing. Connections count even when statistics tracking excludes their activity."),
            TextKey.of("dashboard.retentionLimited", "Session retention can limit this window. Pruning may reduce participant counts and median samples."),
            TextKey.of("dashboard.noParticipation", "No retained sessions overlap this window. This does not prove there were no players."),
            TextKey.of("dashboard.noSamples", "No completed quit sessions in this window"),
            TextKey.of("dashboard.savedProfiles", "Saved profiles"),
            TextKey.of("dashboard.refreshed", "Report refreshed"),
            TextKey.of("dashboard.timezone", "History time zone"),
            TextKey.of("dashboard.player", "Player"),
            TextKey.of("dashboard.recordedSince", "Recorded since"),
            TextKey.of("dashboard.updated", "Last statistic update"),
            TextKey.of("dashboard.rank", "Rank"),
            TextKey.of("dashboard.value", "Value"),
            TextKey.of("dashboard.gap", "Gap to next position"),
            TextKey.of("dashboard.noPlayers", "No saved players match this search. Try a different name or UUID."),
            TextKey.of("dashboard.noRankings", "No eligible entries for this statistic and period."),
            TextKey.of("dashboard.rankingScope", "Rankings use tracked statistics. Excluded profiles do not appear in the ranking."),
            TextKey.of("dashboard.excluded", "Excluded profiles"),
            TextKey.of("dashboard.total", "Total"),
            TextKey.of("dashboard.page", "Page"),
            TextKey.of("dashboard.profile", "Player profile"),
            TextKey.of("dashboard.back", "Back to players"),
            TextKey.of("dashboard.statistics", "Statistics"),
            TextKey.of("dashboard.details", "Detailed counters"),
            TextKey.of("dashboard.bests", "Personal bests"),
            TextKey.of("dashboard.history", "Period history"),
            TextKey.of("dashboard.started", "Started"),
            TextKey.of("dashboard.ended", "Ended"),
            TextKey.of("dashboard.eligible", "Eligible"),
            TextKey.of("dashboard.active", "Active"),
            TextKey.of("dashboard.afk", "AFK"),
            TextKey.of("dashboard.outcome", "End reason"),
            TextKey.of("dashboard.quit", "Quit"),
            TextKey.of("dashboard.empty", "No retained entries"),
            TextKey.of("dashboard.notFound", "This saved player could not be found. Return to the player directory."),
            TextKey.of("dashboard.historyLimit", "Only the newest retained sessions and history windows are shown, up to the report limit."),
            TextKey.of("dashboard.inProgress", "In progress"),
            TextKey.of("dashboard.recovered", "Recovered"),
            TextKey.of("dashboard.observation", "Observed"),
            TextKey.of("dashboard.seconds", "seconds"),
            TextKey.of("dashboard.noDate", "Not recorded"),
            TextKey.of("dashboard.publicProfile", "Public player profile"),
            TextKey.of("dashboard.publicUnavailable", "This public profile is unavailable."),
            TextKey.of("dashboard.publicScope", "Statistics shared by this player. Tracking settings affect recorded values."),
            TextKey.of("dashboard.publicLoading", "Loading public profile..."),
            TextKey.of("dashboard.publicRetry", "Reload profile"),
            TextKey.of("dashboard.publicFailed", "The profile could not be loaded. Wait a moment and try again."),
            TextKey.of("dashboard.publicEmpty", "This player has no recorded values to share yet."),
            TextKey.of("dashboard.detailRankings", "Detailed rankings"),
            TextKey.of("dashboard.seasons", "Seasons"),
            TextKey.of("dashboard.milestones", "Milestones"),
            TextKey.of("dashboard.comparison", "Compare activity"),
            TextKey.of("dashboard.playerUuid", "Player UUID"),
            TextKey.of("dashboard.otherUuid", "Second player UUID (optional)"),
            TextKey.of("dashboard.windows", "Windows"),
            TextKey.of("dashboard.selectPlayer", "Enter a player UUID, or open this report from a player profile."),
            TextKey.of("dashboard.target", "Target"),
            TextKey.of("dashboard.progress", "Progress"),
            TextKey.of("dashboard.completed", "Completed"),
            TextKey.of("dashboard.scheduled", "Scheduled"),
            TextKey.of("dashboard.archived", "Archived"),
            TextKey.of("dashboard.status", "Status"),
            TextKey.of("dashboard.participants", "Participants"),
            TextKey.of("dashboard.standings", "Standings"),
            TextKey.of("dashboard.winners", "Winners"),
            TextKey.of("dashboard.noSeasons", "No seasons have been configured."),
            TextKey.of("dashboard.missing", "Not retained"),
            TextKey.of("dashboard.partial", "Partial window"),
            TextKey.of("dashboard.comparisonScope", "Retained calendar observations only. Missing windows are gaps, not zero. Partial windows may cover only part of the period."),
            TextKey.of("dashboard.chart", "Recorded values by window"),
            TextKey.of("dashboard.chartTable", "Chart data"),
            TextKey.of("dashboard.noMilestones", "No milestone targets are available for this player."),
            TextKey.of("dashboard.historical", "Historical reconciliation")
    );

    private DashboardMessages() {
    }

    public static List<MessageKey> keys() {
        List<MessageKey> keys = new ArrayList<>(LABELS);
        keys.add(SETTING_ENABLED);
        keys.add(SETTING_PORT);
        keys.add(SETTING_ORIGIN);
        keys.add(SETTING_PUBLIC_PROFILES);
        return List.copyOf(keys);
    }
}
