package com.volmit.staticfixture;

import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.milestone.MilestoneCompletion;
import com.volmit.staticstats.milestone.MilestoneCompletionEvent;
import com.volmit.staticstats.milestone.MilestoneDefinition;
import com.volmit.staticstats.milestone.MilestoneProgress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MilestoneProbe implements AutoCloseable {
    private final StaticStatistics statistics;
    private final Path file;
    private final Path storage;
    private final Logger logger;
    private final AutoCloseable subscription;

    public MilestoneProbe(StaticStatistics statistics, Path file, Path storage, Logger logger) {
        this.statistics = Objects.requireNonNull(statistics);
        this.file = Objects.requireNonNull(file);
        this.storage = Objects.requireNonNull(storage);
        this.logger = Objects.requireNonNull(logger);
        subscription = statistics.onMilestoneCompletion(this::completed);
    }

    @Override
    public void close() {
        try {
            subscription.close();
        } catch (Exception failure) {
            logger.log(Level.SEVERE, "Fixture milestone subscription could not close", failure);
        }
    }

    public String snapshot(UUID uuid) {
        List<MilestoneProgress> progress = statistics.milestones(uuid);
        List<MilestoneCompletion> completions = statistics.milestoneCompletions(uuid);
        StringBuilder data = new StringBuilder(1024);
        data.append("{\"uuid\":\"").append(uuid).append("\",\"progress\":[");
        for (int index = 0; index < progress.size(); index++) {
            if (index > 0) {
                data.append(',');
            }
            MilestoneProgress entry = progress.get(index);
            data.append('{');
            appendDefinition(data, entry.definition());
            data.append(",\"value\":").append(entry.value()).append(",\"completion\":");
            if (entry.completion().isPresent()) {
                appendCompletion(data, entry.completion().orElseThrow());
            } else {
                data.append("null");
            }
            data.append('}');
        }
        data.append("],\"completions\":[");
        for (int index = 0; index < completions.size(); index++) {
            if (index > 0) {
                data.append(',');
            }
            appendCompletion(data, completions.get(index));
        }
        return data.append("]}").toString();
    }

    private synchronized void completed(MilestoneCompletionEvent event) {
        try {
            String stored = Files.readString(storage, StandardCharsets.UTF_8).replace("\r", "").replace("\n", "");
            StringBuilder data = new StringBuilder(stored.length() + 300);
            data.append("{\"at\":").append(System.currentTimeMillis())
                    .append(",\"uuid\":\"").append(event.playerId()).append("\",\"sessionId\":");
            if (event.sessionId().isPresent()) {
                data.append('"').append(event.sessionId().orElseThrow()).append('"');
            } else {
                data.append("null");
            }
            data.append(",\"completion\":");
            appendCompletion(data, event.completion());
            data.append(",\"stored\":").append(stored).append("}\n");
            Files.createDirectories(file.getParent());
            Files.writeString(file, data, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Fixture milestone event capture failed", failure);
        }
    }

    private void appendCompletion(StringBuilder data, MilestoneCompletion completion) {
        data.append('{');
        appendDefinition(data, completion.definition());
        data.append(",\"completedAt\":").append(completion.completedAt())
                .append(",\"observedValue\":").append(completion.observedValue())
                .append(",\"historical\":").append(completion.historical()).append('}');
    }

    private void appendDefinition(StringBuilder data, MilestoneDefinition definition) {
        data.append("\"id\":\"").append(definition.id()).append("\",\"statistic\":\"")
                .append(definition.statistic().id()).append("\",\"threshold\":").append(definition.threshold());
    }
}
