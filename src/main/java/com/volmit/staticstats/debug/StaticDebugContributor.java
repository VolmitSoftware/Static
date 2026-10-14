package com.volmit.staticstats.debug;

import art.arcane.volmlib.util.config.TomlCodec;
import art.arcane.volmlib.util.diagnostics.DebugDumpContributor;
import com.volmit.staticstats.StaticPlugin;

public final class StaticDebugContributor implements DebugDumpContributor {
    private final StaticPlugin plugin;

    public StaticDebugContributor(StaticPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Report capture() {
        String report = "Static services\n"
                + "Scheduler: " + plugin.schedulerName() + "\n"
                + "Java bytecode target: 25\n"
                + "Minecraft support: 26.1-26.3\n"
                + "Player profiles: " + plugin.getStatsService().playerCount() + "\n"
                + "Dashboard: " + (plugin.getDashboard() == null ? "unavailable" : plugin.getDashboard().status()) + "\n"
                + "Last successful save (epoch ms): " + plugin.getStatsService().lastSaveAt() + "\n"
                + "Last save failure: " + plugin.getStatsService().lastSaveFailure() + "\n"
                + "Language: " + plugin.getConfigService().runtime().language() + "\n"
                + "Available locales: " + plugin.getLanguageService().availableLocales() + "\n"
                + "Language source: " + plugin.getLanguageService().remoteCatalogReference().orElse("unavailable") + "\n"
                + "PlaceholderAPI: " + plugin.getIntegrations().placeholderEnabled() + "\n"
                + "Votifier: " + plugin.getIntegrations().votesEnabled() + "\n"
                + "\nEffective configuration\n"
                + TomlCodec.toToml(plugin.getConfigService().editableCopy(), "static");
        return () -> report;
    }
}
