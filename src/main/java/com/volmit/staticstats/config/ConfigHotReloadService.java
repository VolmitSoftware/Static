package com.volmit.staticstats.config;

import art.arcane.volmlib.util.config.ConfigFileSupport;
import art.arcane.volmlib.util.hotload.ConfigHotloadEngine;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

public final class ConfigHotReloadService implements AutoCloseable {
    private static final long MAXIMUM_WATCHED_BYTES = 2L * 1024L * 1024L;

    private final StaticPlugin plugin;
    private final ConfigService configService;
    private final ConfigHotloadEngine engine;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconfigureRequested = new AtomicBoolean();
    private final Set<Path> readFailures = ConcurrentHashMap.newKeySet();
    private WatchTiming appliedTiming;

    public ConfigHotReloadService(StaticPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
        this.engine = new ConfigHotloadEngine(
                this::isManagedFile,
                this::knownFiles,
                this::readFile,
                ConfigFileSupport::normalize
        );
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Static-HotReload");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        configureEngine();
        configService.setSelfWriteListener(this::noteSelfWrite);
        plugin.getLanguageService().setSelfWriteListener(this::noteSelfWrite);
        scheduleNext(configService.runtime().hotReloadPollMillis());
    }

    public void requestReconfigure() {
        reconfigureRequested.set(true);
    }

    public void noteSelfWrite(File file, String content) {
        engine.noteSelfWrite(file, content);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        configService.setSelfWriteListener(null);
        plugin.getLanguageService().setSelfWriteListener(null);
        engine.clear();
        readFailures.clear();
        executor.shutdownNow();
    }

    private void scheduleNext(long delayMillis) {
        if (closed.get()) {
            return;
        }
        try {
            executor.schedule(this::poll, Math.max(250L, delayMillis), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            if (!closed.get()) {
                plugin.getLogger().log(Level.SEVERE, "Static configuration watcher could not reschedule", exception);
            }
        }
    }

    private void poll() {
        try {
            pollChanges();
        } catch (Throwable exception) {
            plugin.getLogger().log(Level.SEVERE, "Static configuration watcher failed", exception);
        } finally {
            scheduleNext(configService.runtime().hotReloadPollMillis());
        }
    }

    void pollChanges() {
        if (closed.get()) {
            return;
        }
        if (reconfigureRequested.getAndSet(false)) {
            updateEngineTiming();
        }
        Set<ConfigHotloadEngine.StableContentSnapshot> snapshots = engine.pollTouchedSnapshots();
        if (!snapshots.isEmpty()) {
            applySnapshots(snapshots);
        }
    }

    private void applySnapshots(Set<ConfigHotloadEngine.StableContentSnapshot> snapshots) {
        boolean enabled = configService.runtime().hotReloadEnabled();
        ReloadOutcome outcome = processSnapshots(engine, snapshots, enabled, () -> plugin.reloadAll(false));
        if (outcome == ReloadOutcome.NOT_ATTEMPTED || outcome == ReloadOutcome.DISABLED) {
            return;
        }
        if (outcome == ReloadOutcome.APPLIED) {
            plugin.getLogger().info("Applied Static configuration file changes.");
        } else {
            plugin.getLogger().warning("Rejected Static file changes; the last known good settings remain active.");
        }
        notifyOperators(outcome == ReloadOutcome.APPLIED);
    }

    private void configureEngine() {
        RuntimeConfig config = configService.runtime();
        engine.configure(
                config.hotReloadPollMillis(),
                config.hotReloadCooldownMillis(),
                knownFiles(),
                List.of(configService.dataFolder(), plugin.getLanguageService().languageDirectory())
        );
        appliedTiming = new WatchTiming(config.hotReloadPollMillis(), config.hotReloadCooldownMillis());
    }

    private void updateEngineTiming() {
        RuntimeConfig config = configService.runtime();
        WatchTiming timing = new WatchTiming(config.hotReloadPollMillis(), config.hotReloadCooldownMillis());
        if (!timing.equals(appliedTiming)) {
            engine.updateTiming(timing.pollMillis(), timing.cooldownMillis());
            appliedTiming = timing;
        }
    }

    private void notifyOperators(boolean success) {
        if (!configService.runtime().hotReloadNotifyOperators()) {
            return;
        }
        FoliaScheduler.runGlobal(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                FoliaScheduler.runEntity(plugin, player, () -> {
                    if (player.hasPermission("static.config")) {
                        plugin.getPresentationService().hotReload(player, success);
                    }
                });
            }
        });
    }

    private List<File> knownFiles() {
        ArrayList<File> files = new ArrayList<>();
        files.add(configService.configFile());
        File[] languages = plugin.getLanguageService().languageDirectory().listFiles();
        if (languages != null) {
            for (File language : languages) {
                if (plugin.getLanguageService().isLanguageFile(language)) {
                    files.add(language);
                }
            }
        }
        return List.copyOf(files);
    }

    private boolean isManagedFile(File file) {
        return file != null && (file.getAbsoluteFile().equals(configService.configFile().getAbsoluteFile())
                || plugin.getLanguageService().isLanguageFile(file));
    }

    static ReloadOutcome processSnapshots(
            ConfigHotloadEngine engine,
            Set<ConfigHotloadEngine.StableContentSnapshot> snapshots,
            boolean enabled,
            BooleanSupplier reload
    ) {
        AtomicBoolean attempted = new AtomicBoolean();
        AtomicReference<Boolean> result = new AtomicReference<>();
        for (ConfigHotloadEngine.StableContentSnapshot snapshot : snapshots) {
            engine.processSnapshotChange(snapshot, ignored -> {
                attempted.set(true);
                if (!enabled) {
                    return true;
                }
                Boolean existing = result.get();
                if (existing != null) {
                    return existing;
                }
                boolean applied = reload.getAsBoolean();
                result.set(applied);
                return applied;
            }, null);
        }
        if (!attempted.get()) {
            return ReloadOutcome.NOT_ATTEMPTED;
        }
        if (!enabled) {
            return ReloadOutcome.DISABLED;
        }
        return Boolean.TRUE.equals(result.get()) ? ReloadOutcome.APPLIED : ReloadOutcome.REJECTED;
    }

    static boolean isManagedFile(File file, File configFile, File activeLanguageFile) {
        if (file == null) {
            return false;
        }
        File absolute = file.getAbsoluteFile();
        return absolute.equals(configFile.getAbsoluteFile())
                || absolute.equals(activeLanguageFile.getAbsoluteFile());
    }

    private String readFile(File file) {
        if (file == null) {
            return null;
        }
        Path path = file.toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || file.length() > MAXIMUM_WATCHED_BYTES) {
            return null;
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            readFailures.remove(path);
            return content;
        } catch (IOException exception) {
            if (readFailures.add(path)) {
                plugin.getLogger().log(Level.WARNING, "Could not read watched Static file " + path, exception);
            }
            return null;
        }
    }

    enum ReloadOutcome {
        NOT_ATTEMPTED,
        DISABLED,
        APPLIED,
        REJECTED
    }

    private record WatchTiming(long pollMillis, long cooldownMillis) {
    }
}
