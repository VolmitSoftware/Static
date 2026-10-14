package com.volmit.staticfixture;

import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.participation.ParticipationSnapshot;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.StatsPeriod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ParticipationProbe implements AutoCloseable {
    private final StaticStatistics statistics;
    private final Path directory;
    private final Logger logger;
    private final Map<UUID, CompletableFuture<Boolean>> requests = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(operation -> {
        Thread thread = new Thread(operation, "StaticFixture-Participation");
        thread.setDaemon(true);
        return thread;
    });

    public ParticipationProbe(StaticStatistics statistics, Path directory, Logger logger) {
        this.statistics = Objects.requireNonNull(statistics);
        this.directory = Objects.requireNonNull(directory);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
                logger.warning("Fixture participation writes did not finish before shutdown");
            }
        } catch (InterruptedException exception) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Fixture participation shutdown was interrupted", exception);
        }
        requests.clear();
    }

    public UUID capture(Request options) {
        UUID request = UUID.randomUUID();
        CompletableFuture<ParticipationSnapshot> snapshot = options.cached()
                ? CompletableFuture.completedFuture(statistics.cachedParticipation(options.period(), options.offset()).orElse(null))
                : statistics.participation(options.period(), options.offset());
        requests.put(request, snapshot.handleAsync((result, failure) -> {
            if (failure != null) {
                logger.log(Level.SEVERE, "Fixture participation request failed", failure);
                return false;
            }
            return publish(request, result, options.players());
        }, writer));
        return request;
    }

    public String status(UUID request) {
        CompletableFuture<Boolean> pending = Objects.requireNonNull(requests.get(request), "Unknown fixture participation request");
        if (!pending.isDone()) {
            return "pending";
        }
        requests.remove(request);
        return pending.join() ? "saved" : "failed";
    }

    private boolean publish(UUID request, ParticipationSnapshot snapshot, List<UUID> players) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(request + ".json"), serialize(snapshot, players), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (IOException failure) {
            logger.log(Level.SEVERE, "Fixture participation could not be written for request " + request, failure);
            return false;
        }
    }

    private String serialize(ParticipationSnapshot snapshot, List<UUID> players) {
        if (snapshot == null) {
            return "null";
        }
        StringBuilder data = new StringBuilder(2048);
        data.append("{\"window\":{\"period\":\"").append(snapshot.window().period().id())
                .append("\",\"id\":\"").append(snapshot.window().id())
                .append("\",\"startedAt\":").append(snapshot.window().startedAt())
                .append(",\"endsAt\":").append(snapshot.window().endsAt()).append('}')
                .append(",\"historyZone\":\"").append(snapshot.historyZone().getId()).append('"')
                .append(",\"refreshedAt\":").append(snapshot.refreshedAt())
                .append(",\"uniquePlayers\":").append(snapshot.uniquePlayers())
                .append(",\"newPlayers\":").append(snapshot.newPlayers())
                .append(",\"returningPlayers\":").append(snapshot.returningPlayers())
                .append(",\"unknownCohortPlayers\":").append(snapshot.unknownCohortPlayers())
                .append(",\"completedSessions\":").append(snapshot.completedSessions())
                .append(",\"medianSessionMillis\":").append(snapshot.medianSessionMillis().isPresent()
                        ? Double.toString(snapshot.medianSessionMillis().getAsDouble()) : "null")
                .append(",\"openSessions\":").append(snapshot.openSessions())
                .append(",\"interruptedSessions\":").append(snapshot.interruptedSessions())
                .append(",\"shutdownSessions\":").append(snapshot.shutdownSessions())
                .append(",\"earliestObservation\":").append(snapshot.earliestObservation())
                .append(",\"retentionLimited\":").append(snapshot.retentionLimited())
                .append(",\"profiles\":[");
        for (int index = 0; index < players.size(); index++) {
            if (index > 0) {
                data.append(',');
            }
            UUID uuid = players.get(index);
            data.append("{\"uuid\":\"").append(uuid).append("\",\"sessions\":[");
            List<PlayerSession> sessions = statistics.sessions(uuid);
            for (int sessionIndex = 0; sessionIndex < sessions.size(); sessionIndex++) {
                if (sessionIndex > 0) {
                    data.append(',');
                }
                PlayerSession session = sessions.get(sessionIndex);
                data.append("{\"id\":\"").append(session.id()).append('"')
                        .append(",\"startedAt\":").append(session.startedAt())
                        .append(",\"endedAt\":").append(session.endedAt())
                        .append(",\"checkpointAt\":").append(session.checkpointAt())
                        .append(",\"eligibleMillis\":").append(session.eligibleMillis())
                        .append(",\"activeMillis\":").append(session.activeMillis())
                        .append(",\"afkMillis\":").append(session.afkMillis())
                        .append(",\"endReason\":\"").append(session.endReason()).append("\"}");
            }
            data.append("]}");
        }
        return data.append("]}").toString();
    }

    public record Request(StatsPeriod period, int offset, boolean cached, List<UUID> players) {
        public Request {
            Objects.requireNonNull(period);
            players = List.copyOf(players);
        }
    }
}
