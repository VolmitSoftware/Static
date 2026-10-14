package com.volmit.staticfixture;

import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.season.SeasonCompletionEvent;
import com.volmit.staticstats.season.SeasonRankingSnapshot;
import com.volmit.staticstats.season.SeasonSnapshot;
import com.volmit.staticstats.stats.RankedPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SeasonProbe implements AutoCloseable {
    private final StaticStatistics statistics;
    private final Path directory;
    private final Logger logger;
    private final AutoCloseable subscription;
    private final Map<String, CompletableFuture<SeasonRankingSnapshot>> requests = new ConcurrentHashMap<>();

    public SeasonProbe(StaticStatistics statistics, Path directory, Logger logger) {
        this.statistics = statistics;
        this.directory = directory;
        this.logger = logger;
        subscription = statistics.onSeasonCompletion(this::completed);
    }

    public String profile(String id, UUID uuid) {
        SeasonSnapshot season = statistics.season(id).orElseThrow();
        double value = statistics.seasonSnapshot(uuid, id)
                .map(snapshot -> snapshot.profile().value(season.definition().rules().statistic())).orElse(0D);
        return "{\"id\":\"" + id + "\",\"status\":\"" + season.status()
                + "\",\"value\":" + value + ",\"recordedSince\":" + season.recordedSince()
                + ",\"completedAt\":" + season.completedAt() + "}";
    }

    public void request(String id) {
        requests.put(id, statistics.seasonRanking(id));
    }

    public String result(String id) {
        CompletableFuture<SeasonRankingSnapshot> request = requests.get(id);
        if (request == null || !request.isDone()) {
            return "pending";
        }
        return ranking(request.join());
    }

    @Override
    public void close() {
        try {
            subscription.close();
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Could not close season fixture subscription", exception);
        }
    }

    private synchronized void completed(SeasonCompletionEvent event) {
        try {
            Path storage = directory.resolveSibling("Static").resolve("storage/players.json");
            String stored = Files.readString(storage).replace("\r", "").replace("\n", "");
            String record = "{\"result\":" + ranking(event.snapshot()) + ",\"reconciled\":"
                    + event.reconciledAfterRestart() + ",\"stored\":" + stored + "}\n";
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("season-events.jsonl"), record, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.SEVERE, "Could not record season completion", exception);
        }
    }

    private String ranking(SeasonRankingSnapshot snapshot) {
        StringBuilder data = new StringBuilder("{\"id\":\"").append(snapshot.season().definition().id())
                .append("\",\"status\":\"").append(snapshot.season().status()).append("\",\"winners\":")
                .append(snapshot.winners().size()).append(",\"entries\":[");
        boolean first = true;
        for (RankedPlayer player : snapshot.ranking().entries()) {
            if (!first) { data.append(','); }
            first = false;
            data.append("{\"uuid\":\"").append(player.profile().uuid()).append("\",\"value\":")
                    .append(player.value()).append(",\"rank\":").append(player.rank()).append('}');
        }
        return data.append("]}").toString();
    }
}
