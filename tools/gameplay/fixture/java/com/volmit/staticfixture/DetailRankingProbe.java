package com.volmit.staticfixture;

import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.detailed.RankedDetailPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
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

public final class DetailRankingProbe implements AutoCloseable {
    private final StaticStatistics statistics;
    private final Path directory;
    private final Logger logger;
    private final Map<UUID, CompletableFuture<Boolean>> requests = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(operation -> {
        Thread thread = new Thread(operation, "StaticFixture-DetailRankings");
        thread.setDaemon(true);
        return thread;
    });

    public DetailRankingProbe(StaticStatistics statistics, Path directory, Logger logger) {
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
                logger.warning("Fixture detailed ranking writes did not finish before shutdown");
            }
        } catch (InterruptedException exception) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Fixture detailed ranking shutdown was interrupted", exception);
        }
        requests.clear();
    }

    public UUID capture(DetailKey key, boolean cached) {
        UUID request = UUID.randomUUID();
        CompletableFuture<DetailRankingSnapshot> snapshot = cached
                ? CompletableFuture.completedFuture(statistics.cachedDetailRanking(key).orElse(null))
                : statistics.detailRanking(key);
        requests.put(request, snapshot.handleAsync((result, failure) -> {
            if (failure != null) {
                logger.log(Level.SEVERE, "Fixture detailed ranking request failed for " + key.id(), failure);
                return false;
            }
            return publish(request, result);
        }, writer));
        return request;
    }

    public String status(UUID request) {
        CompletableFuture<Boolean> pending = Objects.requireNonNull(requests.get(request), "Unknown fixture detailed ranking request");
        if (!pending.isDone()) {
            return "pending";
        }
        requests.remove(request);
        return pending.join() ? "saved" : "failed";
    }

    private boolean publish(UUID request, DetailRankingSnapshot snapshot) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(request + ".json"), serialize(snapshot), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (IOException failure) {
            logger.log(Level.SEVERE, "Fixture detailed ranking could not be written for request " + request, failure);
            return false;
        }
    }

    private String serialize(DetailRankingSnapshot snapshot) {
        if (snapshot == null) {
            return "null";
        }
        StringBuilder data = new StringBuilder(4096);
        data.append("{\"counter\":");
        appendString(data, snapshot.counter().id());
        data.append(",\"refreshedAt\":").append(snapshot.refreshedAt())
                .append(",\"totalProfiles\":").append(snapshot.totalProfiles())
                .append(",\"excludedCount\":").append(snapshot.excludedCount())
                .append(",\"recordedSince\":").append(snapshot.recordedSince())
                .append(",\"entries\":[");
        List<RankedDetailPlayer> entries = snapshot.entries();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                data.append(',');
            }
            RankedDetailPlayer entry = entries.get(index);
            data.append("{\"uuid\":");
            appendString(data, entry.profile().uuid().toString());
            data.append(",\"name\":");
            appendString(data, entry.profile().name());
            data.append(",\"position\":").append(entry.position()).append(",\"rank\":").append(entry.rank())
                    .append(",\"value\":");
            appendString(data, Long.toString(entry.value()));
            data.append(",\"gapToNext\":");
            appendString(data, Long.toString(entry.gapToNext()));
            data.append(",\"recordedSince\":").append(entry.profile().recordedSince(snapshot.counter())).append('}');
        }
        return data.append("]}").toString();
    }

    private void appendString(StringBuilder data, String value) {
        data.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> data.append("\\\"");
                case '\\' -> data.append("\\\\");
                case '\n' -> data.append("\\n");
                case '\r' -> data.append("\\r");
                case '\t' -> data.append("\\t");
                default -> {
                    if (character < 32) {
                        data.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        data.append(character);
                    }
                }
            }
        }
        data.append('"');
    }
}
