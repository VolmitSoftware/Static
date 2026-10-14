package com.volmit.staticstats.integration;

import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.stats.StatKey;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;

public final class IntegrationService implements Listener, AutoCloseable {
    private final StaticPlugin plugin;
    private StaticExpansion expansion;
    private Listener voteListener;
    private Plugin votePlugin;

    public IntegrationService(StaticPlugin plugin) { this.plugin = plugin; }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        attachAvailable();
    }

    @Override
    public void close() {
        if (expansion != null) { expansion.unregister(); expansion = null; }
        if (voteListener != null) { HandlerList.unregisterAll(voteListener); voteListener = null; }
        votePlugin = null;
        HandlerList.unregisterAll(this);
    }

    public boolean placeholderEnabled() { return expansion != null; }
    public boolean votesEnabled() { return voteListener != null; }

    @EventHandler
    public void onEnable(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        if (name.equals("PlaceholderAPI") || name.equals("Votifier") || name.equals("NuVotifier")) { attachAvailable(); }
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI") && expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        if (event.getPlugin() == votePlugin && voteListener != null) {
            HandlerList.unregisterAll(voteListener);
            voteListener = null;
            votePlugin = null;
        }
    }

    private void attachAvailable() {
        if (expansion == null && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                StaticExpansion candidate = new StaticExpansion(plugin);
                if (candidate.register()) { expansion = candidate; }
                else { plugin.getLogger().warning("Static PlaceholderAPI registration was declined"); }
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().log(Level.WARNING, "Static PlaceholderAPI integration is unavailable", exception);
            }
        }
        if (voteListener == null) {
            Plugin candidate = plugin.getServer().getPluginManager().getPlugin("Votifier");
            if (candidate == null) { candidate = plugin.getServer().getPluginManager().getPlugin("NuVotifier"); }
            if (candidate != null && candidate.isEnabled()) { attachVotes(candidate); }
        }
    }

    private void attachVotes(Plugin dependency) {
        try {
            ClassLoader loader = dependency.getClass().getClassLoader();
            Class<? extends Event> type = Class.forName("com.vexsoftware.votifier.model.VotifierEvent", true, loader).asSubclass(Event.class);
            Method getVote = type.getMethod("getVote");
            Method getUsername = getVote.getReturnType().getMethod("getUsername");
            Listener listener = new VoteListener();
            plugin.getServer().getPluginManager().registerEvent(type, listener, EventPriority.MONITOR, (registered, event) -> {
                if (!type.isInstance(event) || !plugin.getConfigService().runtime().enabled()) { return; }
                try {
                    String username = (String) getUsername.invoke(getVote.invoke(event));
                    if (username != null) {
                        plugin.getStatsService().find(username).ifPresent(profile ->
                                plugin.getStatsService().add(profile.uuid(), StatKey.VOTES, 1D));
                    }
                } catch (IllegalAccessException | InvocationTargetException exception) {
                    throw new EventException(exception);
                }
            }, plugin, true);
            votePlugin = dependency;
            voteListener = listener;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError exception) {
            plugin.getLogger().log(Level.WARNING, "Static vote integration is unavailable on this Votifier version", exception);
        }
    }

    private static final class VoteListener implements Listener {
    }
}
