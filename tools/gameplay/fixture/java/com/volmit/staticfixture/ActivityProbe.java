package com.volmit.staticfixture;

import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.StatKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ActivityProbe implements AutoCloseable {
    private final StaticStatistics statistics;
    private final Path file;
    private final Logger logger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(operation -> {
        Thread thread = new Thread(operation, "StaticFixture-Activity");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> task;
    private int sequence;

    public ActivityProbe(StaticStatistics statistics, Path file, Logger logger) {
        this.statistics = Objects.requireNonNull(statistics);
        this.file = Objects.requireNonNull(file);
        this.logger = Objects.requireNonNull(logger);
    }

    public synchronized void start(UUID uuid) {
        if (task != null) {
            task.cancel(false);
        }
        sequence = 0;
        task = executor.scheduleAtFixedRate(() -> sample(uuid), 0, 200, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (task != null) {
            task.cancel(false);
        }
        executor.shutdownNow();
    }

    private synchronized void sample(UUID uuid) {
        if (sequence >= 300) {
            task.cancel(false);
            return;
        }
        try {
            PlayerStatsSnapshot snapshot = statistics.snapshot(uuid).orElseThrow();
            List<PlayerSession> sessions = statistics.sessions(uuid);
            StringBuilder data = new StringBuilder(1024);
            data.append("{\"sequence\":").append(++sequence)
                    .append(",\"at\":").append(System.currentTimeMillis())
                    .append(",\"uuid\":\"").append(uuid).append('"')
                    .append(",\"online\":").append(snapshot.value(StatKey.ONLINE_TIME))
                    .append(",\"active\":").append(snapshot.value(StatKey.ACTIVE_TIME))
                    .append(",\"afk\":").append(snapshot.value(StatKey.AFK_TIME))
                    .append(",\"sessions\":[");
            for (int index = 0; index < sessions.size(); index++) {
                if (index > 0) {
                    data.append(',');
                }
                appendSession(data, sessions.get(index));
            }
            data.append("]}\n");
            Files.createDirectories(file.getParent());
            Files.writeString(file, data, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Fixture activity sampling failed", failure);
            task.cancel(false);
        }
    }

    private void appendSession(StringBuilder data, PlayerSession session) {
        data.append("{\"id\":\"").append(session.id()).append('"')
                .append(",\"startedAt\":").append(session.startedAt())
                .append(",\"endedAt\":").append(session.endedAt())
                .append(",\"checkpointAt\":").append(session.checkpointAt())
                .append(",\"eligible\":").append(session.eligibleMillis())
                .append(",\"active\":").append(session.activeMillis())
                .append(",\"afk\":").append(session.afkMillis())
                .append(",\"reason\":\"").append(session.endReason()).append("\"}");
    }
}
