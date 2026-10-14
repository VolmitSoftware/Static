package com.volmit.staticstats.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.volmit.staticstats.stats.StatKey;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProfilePreferences implements AutoCloseable {
    public static final int MAX_FAVORITES = 8;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, List<StatKey>> favorites = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Static-ProfilePreferences");
        thread.setDaemon(true);
        return thread;
    });
    private final CompletableFuture<Void> ready;
    private volatile boolean closed;

    public ProfilePreferences(Path file, Logger logger) {
        this.file = Objects.requireNonNull(file).toAbsolutePath();
        this.logger = Objects.requireNonNull(logger);
        ready = CompletableFuture.runAsync(this::load, writer);
        ready.whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Could not load profile favorites from " + this.file, error);
            }
        });
    }

    public CompletableFuture<Void> ready() {
        return ready;
    }

    public boolean available() {
        return ready.isDone() && !ready.isCompletedExceptionally() && !closed;
    }

    public List<StatKey> favorites(UUID viewer) {
        return favorites.getOrDefault(Objects.requireNonNull(viewer), List.of());
    }

    public CompletableFuture<ToggleResult> toggle(UUID viewer, StatKey statistic) {
        Objects.requireNonNull(viewer);
        Objects.requireNonNull(statistic);
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Profile preferences are closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                ready.join();
                return update(viewer, statistic);
            }, writer);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public void close() {
        closed = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Profile preference writes are still finishing after shutdown wait.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrupted while finishing profile preference writes", exception);
        }
    }

    private ToggleResult update(UUID viewer, StatKey statistic) {
        List<StatKey> next = new ArrayList<>(favorites(viewer));
        boolean removed = next.remove(statistic);
        if (!removed && next.size() >= MAX_FAVORITES) {
            return ToggleResult.LIMIT;
        }
        if (!removed) {
            next.add(statistic);
        }
        Map<UUID, List<StatKey>> updated = new HashMap<>(favorites);
        if (next.isEmpty()) {
            updated.remove(viewer);
        } else {
            updated.put(viewer, List.copyOf(next));
        }
        try {
            save(updated);
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not save profile favorites to " + file, exception);
            throw new CompletionException(exception);
        }
        if (next.isEmpty()) {
            favorites.remove(viewer);
        } else {
            favorites.put(viewer, List.copyOf(next));
        }
        return removed ? ToggleResult.REMOVED : ToggleResult.ADDED;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        Map<UUID, List<StatKey>> loaded = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported profile preference format version");
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("players").entrySet()) {
                List<StatKey> selected = new ArrayList<>();
                for (JsonElement value : entry.getValue().getAsJsonArray()) {
                    StatKey statistic = StatKey.parse(value.getAsString());
                    if (selected.contains(statistic) || selected.size() == MAX_FAVORITES) {
                        throw new IllegalArgumentException("Invalid favorite selection for " + entry.getKey());
                    }
                    selected.add(statistic);
                }
                loaded.put(UUID.fromString(entry.getKey()), List.copyOf(selected));
            }
        } catch (IOException | JsonParseException | IllegalStateException | IllegalArgumentException | NullPointerException exception) {
            throw new CompletionException(new IOException("Cannot read profile preferences from " + file, exception));
        }
        favorites.putAll(loaded);
    }

    private void save(Map<UUID, List<StatKey>> selected) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, List<StatKey>> entry : new TreeMap<>(selected).entrySet()) {
            JsonArray values = new JsonArray();
            for (StatKey statistic : entry.getValue()) {
                values.add(statistic.id());
            }
            players.add(entry.getKey().toString(), values);
        }
        root.add("players", players);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "profile-preferences-", ".tmp");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(GSON.toJson(root));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public enum ToggleResult {
        ADDED,
        REMOVED,
        LIMIT
    }
}
