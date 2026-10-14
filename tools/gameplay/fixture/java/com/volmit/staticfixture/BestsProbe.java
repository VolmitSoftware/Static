package com.volmit.staticfixture;

import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.records.PersonalBest;
import com.volmit.staticstats.records.PersonalBestSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BestsProbe implements AutoCloseable {
    private final StaticStatistics statistics;
    private final Path directory;
    private final Logger logger;
    private final Map<UUID, CompletableFuture<Boolean>> requests = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(operation -> {
        Thread thread = new Thread(operation, "StaticFixture-Bests");
        thread.setDaemon(true);
        return thread;
    });

    public BestsProbe(StaticStatistics statistics, Path directory, Logger logger) {
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
                logger.warning("Fixture best snapshot writes did not finish before shutdown");
            }
        } catch (InterruptedException exception) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Fixture best snapshot shutdown was interrupted", exception);
        }
        requests.clear();
    }

    public UUID capture(UUID uuid) {
        UUID request = UUID.randomUUID();
        String snapshot = snapshot(uuid);
        requests.put(request, CompletableFuture.supplyAsync(() -> publish(request, snapshot), writer));
        return request;
    }

    public String status(UUID request) {
        CompletableFuture<Boolean> pending = Objects.requireNonNull(requests.get(request), "Unknown fixture best snapshot request");
        if (!pending.isDone()) {
            return "pending";
        }
        requests.remove(request);
        return pending.join() ? "saved" : "failed";
    }

    private boolean publish(UUID request, String snapshot) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(request + ".json"), snapshot, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (IOException failure) {
            logger.log(Level.SEVERE, "Fixture best snapshot could not be written for request " + request, failure);
            return false;
        }
    }

    private String snapshot(UUID uuid) {
        Optional<PersonalBestSnapshot> result = statistics.personalBests(uuid);
        if (result.isEmpty()) {
            return "null";
        }
        PersonalBestSnapshot snapshot = result.orElseThrow();
        StringBuilder data = new StringBuilder(4096);
        data.append("{\"uuid\":");
        appendString(data, snapshot.uuid().toString());
        data.append(",\"name\":");
        appendString(data, snapshot.name());
        data.append(",\"historyZone\":");
        appendString(data, snapshot.historyZone().getId());
        data.append(",\"trackingSince\":").append(snapshot.trackingSince()).append(",\"records\":[");
        List<PersonalBest> records = snapshot.records();
        for (int index = 0; index < records.size(); index++) {
            if (index > 0) {
                data.append(',');
            }
            appendRecord(data, records.get(index));
        }
        return data.append("]}").toString();
    }

    private void appendRecord(StringBuilder data, PersonalBest record) {
        data.append("{\"period\":");
        appendString(data, record.period().name().toLowerCase(Locale.ROOT));
        data.append(",\"statistic\":");
        appendString(data, record.statistic().id());
        data.append(",\"value\":").append(record.value())
                .append(",\"startedAt\":").append(record.startedAt())
                .append(",\"endsAt\":").append(record.endsAt())
                .append(",\"recordedSince\":").append(record.recordedSince())
                .append(",\"observedAt\":").append(record.observedAt())
                .append(",\"inProgress\":").append(record.inProgress())
                .append(",\"recovered\":").append(record.recovered()).append('}');
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
