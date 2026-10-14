package com.volmit.staticstats.command;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.presentation.ChatMenuStyle;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.stats.StatsPeriod;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

@Director(name = "static", description = "Player statistics and Static administration", descriptionKey = "command.description.root")
public final class StaticCommands {
    private final StaticPlugin plugin;
    private DebugCommands debug;
    private final PrivacyCommand privacy;

    public StaticCommands(StaticPlugin plugin) {
        this.plugin = plugin;
        debug = new DebugCommands(plugin);
        privacy = new PrivacyCommand(plugin);
    }

    @Director(name = "version", hidden = true, sync = true, description = "Show the installed Static version", descriptionKey = "command.description.version")
    public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
        debug.version(sender);
    }

    @Director(name = "privacy", sync = true, description = "Choose who can view your statistics", descriptionKey = "privacy.description")
    public void privacy(@Param(name = "mode", defaultValue = "current", description = "Visibility: private, server, or public; omit to view", descriptionKey = "privacy.parameter.mode") String mode,
                        @Param(name = "player", defaultValue = "self", description = "Recorded player name or UUID; self by default", descriptionKey = "privacy.parameter.player") String player,
                        @Param(name = "sender", contextual = true) CommandSender sender) {
        privacy.execute(sender, mode, player);
    }

    @Director(name = "stats", sync = true, description = "View a recorded player's statistics", descriptionKey = "command.description.stats")
    public void stats(@Param(name = "player", defaultValue = "self", description = "Recorded player name or UUID; self by default", descriptionKey = "command.parameter.player") String player,
                      @Param(name = "period", defaultValue = "lifetime", description = "Calendar period: lifetime, day, week, or month", descriptionKey = "command.parameter.period") String period,
                      @Param(name = "sender", contextual = true) CommandSender sender) {
        period(sender, period).ifPresent(selected -> plugin.getStatsGui().show(sender, player, selected));
    }

    @Director(name = "top", sync = true, description = "Rank recorded players by a statistic", descriptionKey = "command.description.top")
    public void top(@Param(name = "stat", defaultValue = "online_time", description = "Statistic identifier", descriptionKey = "command.parameter.stat") String stat,
                    @Param(name = "page", defaultValue = "1", description = "One-based ranking page", descriptionKey = "command.parameter.page") int page,
                    @Param(name = "period", defaultValue = "lifetime", description = "Calendar period: lifetime, day, week, or month", descriptionKey = "command.parameter.period") String period,
                    @Param(name = "sender", contextual = true) CommandSender sender) {
        period(sender, period).ifPresent(selected -> plugin.getStatsGui().top(sender, stat, page, selected));
    }

    @Director(name = "sessions", sync = true, description = "View a recorded player's recent sessions", descriptionKey = "command.description.sessions")
    public void sessions(@Param(name = "player", defaultValue = "self", description = "Recorded player name or UUID; self by default", descriptionKey = "command.parameter.player") String player,
                         @Param(name = "page", defaultValue = "1", description = "One-based recent sessions page", descriptionKey = "command.parameter.session_page") int page,
                         @Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getStatsGui().sessions(sender, player, page);
    }

    @Director(name = "milestones", sync = true, description = "View lifetime milestone progress and badges", descriptionKey = "command.description.milestones")
    public void milestones(@Param(name = "player", defaultValue = "self", description = "Recorded player name or UUID; self by default", descriptionKey = "command.parameter.player") String player,
                           @Param(name = "page", defaultValue = "1", description = "One-based milestone page", descriptionKey = "command.parameter.milestone_page") int page,
                           @Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getMilestonesGui().show(sender, player, page);
    }

    @Director(name = "seasons", sync = true, description = "Browse scheduled events and archived seasons", descriptionKey = "command.description.seasons")
    public void seasons(@Param(name = "page", defaultValue = "1", description = "One-based season page", descriptionKey = "command.parameter.season_page") int page,
                        @Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getSeasonsGui().catalog(sender, page);
    }

    @Director(name = "details", sync = true, description = "View lifetime material, entity, and activity counters", descriptionKey = "command.description.details")
    public void details(@Param(name = "player", defaultValue = "self", description = "Recorded player name or UUID; self by default", descriptionKey = "command.parameter.player") String player,
                        @Param(name = "category", defaultValue = "all", description = "Detailed counter category; all by default", descriptionKey = "command.parameter.detail_category") String category,
                        @Param(name = "page", defaultValue = "1", description = "One-based detailed counters page", descriptionKey = "command.parameter.detail_page") int page,
                        @Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getDetailGui().show(sender, player, category, page);
    }

    @Director(name = "season", sync = true, description = "View a player's progress in a named event", descriptionKey = "command.description.season")
    public void season(@Param(name = "id", description = "Season or event identifier", descriptionKey = "command.parameter.season") String id,
                       @Param(name = "player", defaultValue = "self", description = "Recorded player name or UUID; self by default", descriptionKey = "command.parameter.player") String player,
                       @Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getSeasonsGui().profile(sender, id, player);
    }

    @Director(name = "bests", sync = true, description = "View recorded personal bests by day, week, month, or session", descriptionKey = "command.description.bests")
    public void bests(@Param(name = "player", defaultValue = "self", description = "Recorded player name or UUID; self by default", descriptionKey = "command.parameter.player") String player,
                      @Param(name = "period", defaultValue = "day", description = "Record scope: day, week, month, or session", descriptionKey = "command.parameter.best_period") String period,
                      @Param(name = "page", defaultValue = "1", description = "One-based personal bests page", descriptionKey = "command.parameter.best_page") int page,
                      @Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getBestsGui().show(sender, player, period, page);
    }

    @Director(name = "detailtop", sync = true, description = "Show lifetime rankings for one detailed counter", descriptionKey = "command.description.detailtop")
    public void detailtop(@Param(name = "counter", description = "Detailed counter identifier", descriptionKey = "command.parameter.detail_counter") String counter,
                          @Param(name = "page", defaultValue = "1", description = "One-based ranking page", descriptionKey = "command.parameter.page") int page,
                          @Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getDetailGui().top(sender, counter, page);
    }

    @Director(name = "participation", sync = true, description = "Summarize retained server participation by calendar window", descriptionKey = "command.description.participation")
    public void participation(@Param(name = "period", defaultValue = "day", description = "Participation period: day, week, or month", descriptionKey = "command.parameter.participation_period") String period,
                              @Param(name = "offset", defaultValue = "0", description = "Windows before the current one; zero by default", descriptionKey = "command.parameter.participation_offset") int offset,
                              @Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getParticipationCommand().show(sender, period, offset);
    }

    @Director(name = "seasontop", sync = true, description = "View live or archived event rankings", descriptionKey = "command.description.seasontop")
    public void seasontop(@Param(name = "id", description = "Season or event identifier", descriptionKey = "command.parameter.season") String id,
                          @Param(name = "page", defaultValue = "1", description = "One-based season page", descriptionKey = "command.parameter.season_page") int page,
                          @Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getSeasonsGui().top(sender, id, page);
    }

    @Director(name = "config", sync = true, description = "Open the complete in-game configuration editor", descriptionKey = "command.description.config")
    public void config(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getPresentationService().command(sender, StaticMessages.PLAYER_ONLY, FeedbackTone.FAILURE);
            return;
        }
        plugin.getConfigEditor().open(player);
    }

    @Director(name = "language", sync = true, description = "Select an available Static language", descriptionKey = "command.description.language")
    public void language(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getLanguageSwitcher().open(sender);
    }

    @Director(name = "status", sync = true, description = "Show Static runtime status", descriptionKey = "command.description.status")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getLanguageService().send(sender, StaticMessages.STATUS_HEADER);
        plugin.getLanguageService().send(sender, StaticMessages.STATUS_COMPATIBILITY,
                MessageArgs.builder().untrusted("scheduler", plugin.schedulerName()).build());
        plugin.getLanguageService().send(sender, StatsMessages.STATUS, MessageArgs.builder()
                .untrusted("enabled", Boolean.toString(plugin.getConfigService().runtime().enabled()))
                .untrusted("players", Integer.toString(plugin.getStatsService().playerCount()))
                .untrusted("seconds", Long.toString(plugin.getConfigService().runtime().autosaveSeconds())).build());
    }



    private Optional<StatsPeriod> period(CommandSender sender, String value) {
        try {
            return Optional.of(StatsPeriod.parse(value));
        } catch (IllegalArgumentException exception) {
            plugin.getPresentationService().command(sender, StatsMessages.UNKNOWN_PERIOD,
                    MessageArgs.builder().untrusted("period", value).build(), FeedbackTone.FAILURE);
            return Optional.empty();
        }
    }

    @Director(name = "debug", description = "Static diagnostic tools", descriptionKey = "command.description.debug")
    public static final class DebugCommands {
        private final StaticPlugin plugin;

        private DebugCommands(StaticPlugin plugin) { this.plugin = plugin; }

        @Director(name = "version", sync = true, description = "Show the installed Static version", descriptionKey = "command.description.version")
        public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
            ComponentMessenger.sendMarkup(sender, DirectorMiniMenu.version(
                    "Static", plugin.getDescription().getVersion(), ChatMenuStyle.theme()));
        }

        @Director(name = "dump", sync = true, description = "Create a diagnostic report", descriptionKey = "command.description.debug_dump")
        public void dump(@Param(name = "upload", defaultValue = "false", description = "Upload the report when public uploads are enabled", descriptionKey = "command.parameter.upload") boolean upload,
                         @Param(name = "sender", contextual = true) CommandSender sender) {
            plugin.getDebugDump().request(sender, upload);
        }
    }
}
