package com.volmit.staticstats.command;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.dashboard.DashboardSettings;
import com.volmit.staticstats.localization.PrivacyMessages;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.privacy.Visibility;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class PrivacyCommand {
    private final StaticPlugin plugin;

    public PrivacyCommand(StaticPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String mode, String target) {
        Consumer<Runnable> dispatch = dispatcher(sender);
        if (!sender.hasPermission("static.privacy")) {
            send(sender, StaticMessages.NO_PERMISSION);
            return;
        }
        Visibility requested;
        try {
            requested = mode.equalsIgnoreCase("current") ? null : Visibility.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            send(sender, PrivacyMessages.INVALID);
            return;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        if (target.equalsIgnoreCase("self") && viewer == null) {
            send(sender, StaticMessages.PLAYER_ONLY);
            return;
        }
        plugin.executeAsync(() -> find(sender, new Request(requested, target, viewer), dispatch));
    }

    private void find(CommandSender sender, Request request, Consumer<Runnable> dispatch) {
        Optional<PlayerStatsSnapshot> selected = request.target().equalsIgnoreCase("self")
                ? plugin.getStatsService().snapshot(request.viewer()) : plugin.getStatsService().find(request.target());
        plugin.getProfileVisibility().ready().whenComplete((ignored, error) ->
                dispatch.accept(() -> resolved(sender, request, selected, error, dispatch)));
    }

    private void resolved(CommandSender sender, Request request, Optional<PlayerStatsSnapshot> selected,
                          Throwable error, Consumer<Runnable> dispatch) {
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }
        if (selected.isEmpty()) {
            plugin.getLanguageService().send(sender, StatsMessages.UNKNOWN_PLAYER,
                    MessageArgs.builder().untrusted("player", request.target()).build());
            return;
        }
        PlayerStatsSnapshot profile = selected.get();
        if (!authorized(sender, profile.uuid())) {
            send(sender, StaticMessages.NO_PERMISSION);
            return;
        }
        if (error != null || !plugin.getProfileVisibility().available()) {
            send(sender, PrivacyMessages.FAILED);
            return;
        }
        if (request.visibility() == null) {
            report(sender, PrivacyMessages.STATUS, profile, plugin.getProfileVisibility().visibility(profile.uuid()));
            return;
        }
        plugin.getProfileVisibility().set(profile.uuid(), request.visibility()).whenComplete((saved, failure) ->
                dispatch.accept(() -> saved(sender, profile, saved, failure)));
    }

    private void saved(CommandSender sender, PlayerStatsSnapshot profile, Visibility visibility, Throwable failure) {
        if (sender instanceof Player player && !player.isOnline() || !authorized(sender, profile.uuid())) {
            return;
        }
        if (failure != null) {
            send(sender, PrivacyMessages.FAILED);
            return;
        }
        report(sender, PrivacyMessages.CHANGED, profile, visibility);
    }

    private boolean authorized(CommandSender sender, UUID target) {
        return sender.hasPermission("static.privacy") && (sender instanceof Player player && player.getUniqueId().equals(target)
                || sender.hasPermission("static.privacy.others"));
    }

    private void report(CommandSender sender, TextKey key, PlayerStatsSnapshot profile, Visibility visibility) {
        plugin.getLanguageService().send(sender, key, MessageArgs.builder().untrusted("player", profile.name())
                .untrusted("mode", plugin.getLanguageService().render(sender, PrivacyMessages.label(visibility))).build());
        DashboardSettings settings = plugin.getConfigService().runtime().dashboardSettings();
        if (visibility == Visibility.PUBLIC && settings.enabled() && settings.publicProfilesEnabled()
                && plugin.getProfileVisibility().isPublic(profile.uuid())) {
            plugin.getLanguageService().send(sender, PrivacyMessages.LINK, MessageArgs.builder()
                    .untrusted("url", settings.publicOrigin() + "/player/" + profile.uuid()).build());
        }
    }

    private void send(CommandSender sender, TextKey key) {
        plugin.getLanguageService().send(sender, key);
    }

    private Consumer<Runnable> dispatcher(CommandSender sender) {
        if (sender instanceof Entity entity) {
            return task -> FoliaScheduler.runEntity(plugin, entity, task);
        }
        if (sender instanceof BlockCommandSender block) {
            Location location = block.getBlock().getLocation();
            return task -> FoliaScheduler.runRegion(plugin, location, task);
        }
        return Runnable::run;
    }
    private record Request(Visibility visibility, String target, UUID viewer) { }
}
