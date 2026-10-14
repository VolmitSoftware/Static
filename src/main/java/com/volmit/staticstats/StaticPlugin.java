package com.volmit.staticstats;

import art.arcane.volmlib.util.diagnostics.BukkitDebugDump;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.RemoteLanguageCatalog;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.volmit.staticstats.command.CommandService;
import com.volmit.staticstats.command.ParticipationCommand;
import com.volmit.staticstats.config.ConfigHotReloadService;
import com.volmit.staticstats.config.ConfigRepository;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.debug.StaticDebugContributor;
import com.volmit.staticstats.dashboard.DashboardData;
import com.volmit.staticstats.dashboard.DashboardServer;
import com.volmit.staticstats.privacy.ProfileVisibility;
import com.google.gson.JsonObject;
import com.volmit.staticstats.gui.ConfigEditorGui;
import com.volmit.staticstats.gui.StatsGui;
import com.volmit.staticstats.gui.MilestonesGui;
import com.volmit.staticstats.gui.SeasonsGui;
import com.volmit.staticstats.gui.SeasonEditorGui;
import com.volmit.staticstats.gui.DetailGui;
import com.volmit.staticstats.gui.BestsGui;
import com.volmit.staticstats.tracking.DetailListener;
import com.volmit.staticstats.integration.IntegrationService;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.presentation.ChatMenuStyle;
import com.volmit.staticstats.presentation.PresentationService;
import com.volmit.staticstats.stats.StatsService;
import com.volmit.staticstats.util.SplashScreen;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class StaticPlugin extends JavaPlugin {
    private final Set<String> pendingLanguages = ConcurrentHashMap.newKeySet();
    private ConfigService configService;
    private LanguageService languageService;
    private PresentationService presentationService;
    private ConfigHotReloadService hotReloadService;
    private ConfigEditorGui configEditor;
    private BukkitLanguageSwitcher languageSwitcher;
    private BukkitDebugDump debugDump;
    private StatsService statsService;
    private DashboardServer dashboard;
    private ProfileVisibility profileVisibility;
    private StatsGui statsGui;
    private MilestonesGui milestonesGui;
    private AutoCloseable milestoneSubscription;
    private SeasonsGui seasonsGui;
    private SeasonEditorGui seasonEditor;
    private DetailGui detailGui;
    private BestsGui bestsGui;
    private ParticipationCommand participationCommand;
    private DetailListener detailListener;
    private IntegrationService integrations;
    private ExecutorService operations;
    private volatile boolean stopping;

    static void requireSupportedServer(String bukkitVersion) {
        if (bukkitVersion == null || !bukkitVersion.split("-", 2)[0].matches("26\\.[1-3](?:\\.[0-9]+)?(?:\\.build\\.[0-9]+)?")) {
            throw new IllegalStateException("Static requires Minecraft 26.1 through 26.3; server reports " + bukkitVersion);
        }
    }

    @Override
    public void onEnable() {
        long started = System.nanoTime();
        try {
            requireSupportedServer(getServer().getBukkitVersion());
            operations = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Static-Operations");
                thread.setDaemon(true);
                return thread;
            });
            configService = new ConfigService(getDataFolder());
            languageService = new LanguageService(getDataFolder(), getLogger());
            ConfigRepository.PreparedConfig prepared = configService.prepareFromDisk();
            configService.install(prepared);
            try {
                languageService.install(languageService.prepare(prepared.runtime().language()));
            } catch (IOException | RuntimeException exception) {
                getLogger().log(Level.WARNING, "The configured language could not be loaded; using built-in English", exception);
                languageService.install(languageService.englishFallback(prepared.runtime().language()));
            }
            languageService.initializeSelections(() -> configService.runtime().language(), this::selectDefaultLanguage);
            presentationService = new PresentationService(new PresentationService.Dependencies(this, configService, languageService));
            statsService = new StatsService(this, () -> configService.runtime());
            profileVisibility = new ProfileVisibility(getDataFolder().toPath().resolve("storage/profile-visibility.json"), getLogger());
            statsService.start();
            dashboard = new DashboardServer(getDataFolder().toPath(),
                    new DashboardData(statsService, statsService::dashboardPlayers, profileVisibility::isPublic), this::dashboardMessages, getLogger());
            try {
                dashboard.start(configService.runtime().dashboardSettings());
            } catch (IOException exception) {
                getLogger().log(Level.SEVERE, "Static dashboard could not start; statistics remain available. Correct the dashboard settings and reload.", exception);
            }
            configService.setValidator(candidate -> {
                statsService.validateHistorySettings(candidate.historySettings());
                statsService.validateMilestoneSettings(candidate.milestoneSettings());
                statsService.validateSeasonSettings(candidate.seasonSettings());
            });
            configService.setCommitter((candidate, publish) -> {
                try {
                    dashboard.reconfigure(candidate.dashboardSettings(), () -> statsService.installConfiguration(candidate, publish));
                } catch (IOException exception) {
                    throw new UncheckedIOException("Dashboard configuration could not be installed", exception);
                }
            });
            milestonesGui = new MilestonesGui(this);
            getServer().getPluginManager().registerEvents(milestonesGui, this);
            milestoneSubscription = statsService.onMilestoneCompletion(milestonesGui::completed);
            seasonsGui = new SeasonsGui(this);
            seasonEditor = new SeasonEditorGui(this);
            detailGui = new DetailGui(this);
            bestsGui = new BestsGui(this);
            participationCommand = new ParticipationCommand(this);
            getServer().getPluginManager().registerEvents(seasonsGui, this);
            getServer().getPluginManager().registerEvents(seasonEditor, this);
            getServer().getPluginManager().registerEvents(detailGui, this);
            getServer().getPluginManager().registerEvents(bestsGui, this);
            getServer().getPluginManager().registerEvents(participationCommand, this);
            detailListener = new DetailListener(statsService);
            getServer().getPluginManager().registerEvents(detailListener, this);
            statsGui = new StatsGui(this);
            profileVisibility.onChanged(uuid -> {
                statsGui.visibilityChanged(uuid);
                detailGui.visibilityChanged(uuid);
                bestsGui.visibilityChanged(uuid);
                milestonesGui.visibilityChanged(uuid);
            });
            getServer().getPluginManager().registerEvents(statsGui, this);
            configEditor = new ConfigEditorGui(new ConfigEditorGui.Dependencies(this, configService, languageService, presentationService));
            getServer().getPluginManager().registerEvents(configEditor, this);
            languageSwitcher = BukkitLanguageSwitcher.register(this, languageService.selections(),
                    new BukkitLanguageSwitcher.Options("static", "static.config", ChatMenuStyle.theme(),
                            languageService.directorResolver(), languageService.editorOptions(),
                            (sender, change) -> ComponentText.markup(languageService.render(sender,
                                    StaticMessages.CONFIG_SAVED, MessageArgs.builder()
                                            .untrusted("setting", change.key())
                                            .untrusted("old", compact(change.before()))
                                            .untrusted("new", compact(change.after())).build()))));
            debugDump = BukkitDebugDump.create(this, new BukkitDebugDump.Options(
                    () -> configService.runtime().debugUploadEnabled(), new StaticDebugContributor(this),
                    new BukkitDebugDump.Presentation("/static debug dump", "/static debug", ChatMenuStyle.theme(),
                            (key, arguments) -> ComponentText.literal(languageService.directorResolver().resolve(key, arguments)))));
            new CommandService(this).register();
            integrations = new IntegrationService(this);
            integrations.start();
            hotReloadService = new ConfigHotReloadService(this, configService);
            hotReloadService.start();
            requestConfiguredLanguage();
            if (configService.runtime().splashScreen()) {
                SplashScreen.print(this);
            }
            getLogger().info("Static ready in " + (System.nanoTime() - started) / 1_000_000L + " ms with "
                    + schedulerName() + " scheduling and " + statsService.playerCount() + " player profiles.");
        } catch (Exception | LinkageError exception) {
            getLogger().log(Level.SEVERE, "Static could not enable safely", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        beginShutdown();
        pendingLanguages.clear();
        HandlerList.unregisterAll(this);
        if (hotReloadService != null) { hotReloadService.close(); }
        if (dashboard != null) { dashboard.close(); }
        if (configEditor != null) { configEditor.shutdown(); }
        if (statsGui != null) { statsGui.close(); }
        if (milestonesGui != null) { milestonesGui.close(); }
        if (seasonsGui != null) { seasonsGui.close(); }
        if (seasonEditor != null) { seasonEditor.close(); }
        if (detailGui != null) { detailGui.close(); }
        if (bestsGui != null) { bestsGui.close(); }
        if (profileVisibility != null) { profileVisibility.close(); }
        if (participationCommand != null) { participationCommand.close(); }
        if (detailListener != null) { detailListener.close(); }
        if (milestoneSubscription != null) {
            try {
                milestoneSubscription.close();
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Could not close the milestone notification subscription", exception);
            }
            milestoneSubscription = null;
        }
        if (presentationService != null) { presentationService.shutdown(); }
        if (languageSwitcher != null) { languageSwitcher.close(); }
        if (integrations != null) { integrations.close(); }
        if (debugDump != null) { debugDump.close(); }
        if (languageService != null) { languageService.close(); }
        if (operations != null) {
            operations.shutdown();
            try {
                if (!operations.awaitTermination(10, TimeUnit.SECONDS)) {
                    operations.shutdownNow();
                    getLogger().warning("Static operations did not finish within the shutdown window");
                }
            } catch (InterruptedException exception) {
                operations.shutdownNow();
                Thread.currentThread().interrupt();
                getLogger().log(Level.SEVERE, "Interrupted while stopping Static operations", exception);
            }
        }
        SchedulerUtils.cancelPluginTasks(this);
        if (statsService != null) { statsService.close(); }
    }

    public synchronized boolean reloadAll(boolean notifyConsole) {
        if (stopping) { return false; }
        try {
            ConfigRepository.PreparedConfig prepared = configService.prepareFromDisk();
            LanguageService.PreparedLanguage language = languageService.prepare(prepared.runtime().language());
            if (!language.selectionReady()) {
                requestLanguage(prepared.runtime().language());
                return false;
            }
            configService.install(prepared);
            languageService.install(language);
            languageService.reloadInstalledLocales();
            configurationInstalled();
            if (notifyConsole) { getLogger().info("Reloaded Static configuration and language files."); }
            return true;
        } catch (IOException | RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Static reload failed; the last valid settings remain active", exception);
            return false;
        }
    }

    public synchronized void applyConfigurationEdit(Consumer<StaticConfig> mutation,
                                                     LanguageService.PreparedLanguage preparedLanguage) throws IOException {
        if (stopping) { throw new IOException("Static is stopping"); }
        configService.update(mutation);
        if (preparedLanguage != null) { languageService.install(preparedLanguage); }
        configurationInstalled();
    }

    public synchronized void installPreparedLanguage(LanguageService.PreparedLanguage preparedLanguage) {
        if (stopping) { return; }
        languageService.install(preparedLanguage);
        configurationInstalled();
    }

    public void configurationInstalled() {
        if (hotReloadService != null) { hotReloadService.requestReconfigure(); }
        if (statsService != null) { statsService.refreshConfiguration(); }
        requestConfiguredLanguage();
    }

    public void executeAsync(Runnable operation) {
        if (stopping) { return; }
        try {
            operations.execute(() -> {
                try { operation.run(); }
                catch (RuntimeException exception) { getLogger().log(Level.SEVERE, "Static operation failed", exception); }
            });
        } catch (RejectedExecutionException exception) {
            if (!stopping) { getLogger().log(Level.SEVERE, "Static operation could not be scheduled", exception); }
        }
    }

    public String schedulerName() {
        return FoliaScheduler.isFoliaThreading(getServer()) ? "Folia region" : "Bukkit main-thread";
    }

    public ConfigService getConfigService() { return configService; }
    public LanguageService getLanguageService() { return languageService; }
    public PresentationService getPresentationService() { return presentationService; }
    public ConfigEditorGui getConfigEditor() { return configEditor; }
    public BukkitLanguageSwitcher getLanguageSwitcher() { return languageSwitcher; }
    public BukkitDebugDump getDebugDump() { return debugDump; }
    public StatsService getStatsService() { return statsService; }
    public DashboardServer getDashboard() { return dashboard; }
    public ProfileVisibility getProfileVisibility() { return profileVisibility; }
    public StatsGui getStatsGui() { return statsGui; }
    public MilestonesGui getMilestonesGui() { return milestonesGui; }
    public SeasonsGui getSeasonsGui() { return seasonsGui; }
    public SeasonEditorGui getSeasonEditor() { return seasonEditor; }
    public DetailGui getDetailGui() { return detailGui; }
    public BestsGui getBestsGui() { return bestsGui; }
    public ParticipationCommand getParticipationCommand() { return participationCommand; }
    public IntegrationService getIntegrations() { return integrations; }

    private synchronized void selectDefaultLanguage(String locale, LocalizationSnapshot prepared) throws IOException {
        if (stopping) { throw new IOException("Static is stopping"); }
        configService.update(config -> config.general.language = locale);
        languageService.install(new LanguageService.PreparedLanguage(locale, languageService.languageFile(locale), prepared, true));
        if (hotReloadService != null) { hotReloadService.requestReconfigure(); }
    }

    private synchronized void beginShutdown() {
        stopping = true;
    }

    private JsonObject dashboardMessages() {
        JsonObject response = new JsonObject();
        response.addProperty("locale", languageService.dashboardLocale());
        JsonObject messages = new JsonObject();
        for (Map.Entry<String, String> entry : languageService.dashboardMessages().entrySet()) {
            messages.addProperty(entry.getKey(), entry.getValue());
        }
        response.add("messages", messages);
        return response;
    }

    private void requestConfiguredLanguage() {
        if (languageService != null && configService != null && !stopping) {
            requestLanguage(configService.runtime().language());
        }
    }

    private void requestLanguage(String locale) {
        if (!pendingLanguages.add(locale)) { return; }
        RemoteLanguageCatalog.RequestState state = languageService.requestRemote(locale, result -> {
            pendingLanguages.remove(locale);
            if (stopping) { return; }
            if (result.successful()) {
                executeAsync(() -> reloadAll(false));
            } else {
                getLogger().log(Level.WARNING, "Unable to download Static language " + locale
                        + "; installed translations and English remain available", result.failure());
            }
        });
        if (state != RemoteLanguageCatalog.RequestState.SCHEDULED && state != RemoteLanguageCatalog.RequestState.IN_FLIGHT) {
            pendingLanguages.remove(locale);
        }
    }

    private static String compact(String value) {
        return value.length() <= 120 ? value : value.substring(0, 117) + "...";
    }
}
