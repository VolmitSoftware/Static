package com.volmit.staticstats.command;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.localization.FormatMessages;
import com.volmit.staticstats.localization.ParticipationMessages;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.StatsFormatter;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.participation.ParticipationSnapshot;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.stats.StatsPeriod;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class ParticipationCommand implements Listener {
    private final StaticPlugin plugin;
    private final Map<UUID, Object> requests = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ParticipationCommand(StaticPlugin plugin) {
        this.plugin = plugin;
    }

    public void close() {
        closed = true;
        requests.clear();
    }

    public void show(CommandSender sender, String period, int offset) {
        if (!permitted(sender)) {
            return;
        }
        StatsPeriod selected;
        try {
            selected = StatsPeriod.parse(period);
            if (selected == StatsPeriod.LIFETIME) {
                throw new IllegalArgumentException("Participation requires a calendar period");
            }
        } catch (IllegalArgumentException exception) {
            failure(sender, ParticipationMessages.UNKNOWN_PERIOD, args("period", period));
            return;
        }
        if (!validOffset(sender, selected, offset)) {
            return;
        }
        try {
            request(sender, selected, offset);
        } catch (IllegalArgumentException exception) {
            if (validOffset(sender, selected, offset)) {
                plugin.getLogger().log(Level.SEVERE, "Could not request participation summary for " + selected.id() + " offset " + offset, exception);
                failure(sender, ParticipationMessages.FAILED, MessageArgs.empty());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        requests.remove(event.getPlayer().getUniqueId());
    }

    private void request(CommandSender sender, StatsPeriod selected, int offset) {
        if (sender instanceof RemoteConsoleCommandSender) {
            Optional<ParticipationSnapshot> cached = plugin.getStatsService().cachedParticipation(selected, offset);
            if (cached.isPresent()) {
                render(sender, cached.get(), offset);
            } else {
                send(sender, ParticipationMessages.WARMING, MessageArgs.empty());
            }
            return;
        }
        Consumer<Runnable> dispatch = dispatcher(sender);
        Object request = new Object();
        if (sender instanceof Player player) {
            requests.put(player.getUniqueId(), request);
        }
        CompletableFuture<ParticipationSnapshot> result = plugin.getStatsService().participation(selected, offset);
        if (!result.isDone()) {
            send(sender, ParticipationMessages.LOADING, MessageArgs.empty());
        }
        result.whenComplete((snapshot, error) -> dispatch.accept(() -> {
            if (!accept(sender, request) || !validOffset(sender, selected, offset)) {
                return;
            }
            if (error != null) {
                failure(sender, ParticipationMessages.FAILED, MessageArgs.empty());
                return;
            }
            render(sender, snapshot, offset);
        }));
    }

    private boolean validOffset(CommandSender sender, StatsPeriod period, int offset) {
        int maximum = plugin.getConfigService().runtime().historySettings().retention(period) - 1;
        if (offset < 0 || offset > maximum) {
            failure(sender, ParticipationMessages.INVALID_OFFSET, args("max", number(sender, maximum)));
            return false;
        }
        return true;
    }

    private boolean accept(CommandSender sender, Object request) {
        if (sender instanceof Player player && (!player.isOnline() || !requests.remove(player.getUniqueId(), request))) {
            return false;
        }
        return permitted(sender);
    }

    private boolean permitted(CommandSender sender) {
        if (closed) {
            return false;
        }
        if (!sender.hasPermission("static.participation")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return false;
        }
        return true;
    }

    private void render(CommandSender sender, ParticipationSnapshot snapshot, int offset) {
        send(sender, ParticipationMessages.HEADER, MessageArgs.builder()
                .untrusted("period", plain(sender, ParticipationMessages.period(snapshot.window().period())))
                .untrusted("offset", number(sender, offset)).build());
        send(sender, StatsMessages.PERIOD_WINDOW, MessageArgs.builder()
                .untrusted("start", date(sender, snapshot.window().startedAt()))
                .untrusted("end", date(sender, snapshot.window().endsAt())).build());
        send(sender, StatsMessages.PERIOD_ZONE, args("zone", snapshot.historyZone().getId()));
        send(sender, ParticipationMessages.RETAINED, MessageArgs.empty());
        if (snapshot.retentionLimited()) {
            send(sender, ParticipationMessages.RETENTION_LIMITED, MessageArgs.empty());
        }
        if (snapshot.uniquePlayers() == 0) {
            send(sender, ParticipationMessages.EMPTY, MessageArgs.empty());
        } else {
            renderCounts(sender, snapshot);
        }
        send(sender, StatsMessages.TOP_UPDATED, args("date", date(sender, snapshot.refreshedAt())));
    }

    private void renderCounts(CommandSender sender, ParticipationSnapshot snapshot) {
        send(sender, ParticipationMessages.PLAYERS, args("players", number(sender, snapshot.uniquePlayers())));
        send(sender, ParticipationMessages.COHORTS, MessageArgs.builder()
                .untrusted("new", number(sender, snapshot.newPlayers()))
                .untrusted("returning", number(sender, snapshot.returningPlayers()))
                .untrusted("unknown", number(sender, snapshot.unknownCohortPlayers())).build());
        send(sender, ParticipationMessages.BASIS, MessageArgs.empty());
        if (snapshot.medianSessionMillis().isPresent()) {
            String seconds = StatsFormatter.decimal(snapshot.medianSessionMillis().getAsDouble() / 1000,
                    plugin.getLanguageService(), sender);
            String duration = ComponentText.markup(plugin.getLanguageService().render(sender,
                    FormatMessages.SECONDS, args("value", seconds))).plain();
            send(sender, ParticipationMessages.MEDIAN, MessageArgs.builder()
                    .untrusted("count", number(sender, snapshot.completedSessions())).untrusted("duration", duration).build());
            send(sender, ParticipationMessages.MEDIAN_SCOPE, MessageArgs.empty());
        } else {
            send(sender, ParticipationMessages.MEDIAN_EMPTY, MessageArgs.empty());
        }
        send(sender, ParticipationMessages.OTHER_SESSIONS, MessageArgs.builder()
                .untrusted("open", number(sender, snapshot.openSessions()))
                .untrusted("shutdown", number(sender, snapshot.shutdownSessions()))
                .untrusted("interrupted", number(sender, snapshot.interruptedSessions())).build());
        if (snapshot.earliestObservation() > 0) {
            send(sender, ParticipationMessages.EARLIEST, args("date", date(sender, snapshot.earliestObservation())));
        }
    }

    private Consumer<Runnable> dispatcher(CommandSender sender) {
        if (sender instanceof Entity entity) {
            return action -> FoliaScheduler.runEntity(plugin, entity, action);
        }
        if (sender instanceof BlockCommandSender block) {
            Location location = block.getBlock().getLocation();
            return action -> FoliaScheduler.runRegion(plugin, location, action);
        }
        return Runnable::run;
    }

    private String number(CommandSender sender, int value) {
        return StatsFormatter.number(value, plugin.getLanguageService(), sender);
    }

    private String date(CommandSender sender, long value) {
        return StatsFormatter.date(value, plugin.getLanguageService(), sender);
    }

    private String plain(CommandSender sender, TextKey key) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key)).plain();
    }

    private void failure(CommandSender sender, TextKey key, MessageArgs arguments) {
        plugin.getPresentationService().command(sender, key, arguments, FeedbackTone.FAILURE);
    }

    private void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        plugin.getLanguageService().send(sender, key, arguments);
    }

    private MessageArgs args(String key, String value) {
        return MessageArgs.builder().untrusted(key, value).build();
    }
}
