package com.volmit.staticstats.privacy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.stats.RankingSnapshot;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProfileVisibility implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final int MAX_CACHED_BOARDS = 256;

    private final Path file;
    private final Logger logger;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), task -> {
        Thread thread = new Thread(task, "Static-Visibility");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<Object, Object> boards = new LinkedHashMap<>();
    private final CompletableFuture<Void> ready;
    private volatile Map<UUID, Visibility> values = Map.of();
    private volatile boolean loaded;
    private volatile boolean closed;
    private volatile long revision;
    private volatile Consumer<UUID> changed = ignored -> { };

    public ProfileVisibility(Path file, Logger logger) {
        this.file = Objects.requireNonNull(file);
        this.logger = Objects.requireNonNull(logger);
        ready = CompletableFuture.runAsync(this::load, writer);
        ready.whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Could not load profile visibility; other-player access is unavailable", error);
            }
        });
    }

    public CompletableFuture<Void> ready() {
        return ready.copy();
    }

    public boolean available() {
        return loaded && !closed;
    }

    public Visibility visibility(UUID uuid) {
        return available() ? values.getOrDefault(Objects.requireNonNull(uuid), Visibility.SERVER) : Visibility.PRIVATE;
    }

    public boolean canView(UUID target, UUID viewer, boolean administrator) {
        return administrator || Objects.requireNonNull(target).equals(viewer) || isServerVisible(target);
    }

    public boolean canView(CommandSender sender, UUID target) {
        return canView(target, sender instanceof Player player ? player.getUniqueId() : null,
                sender.hasPermission("static.privacy.bypass"));
    }

    public boolean isServerVisible(UUID uuid) {
        return available() && visibility(uuid) != Visibility.PRIVATE;
    }

    public boolean isPublic(UUID uuid) {
        return available() && visibility(uuid) == Visibility.PUBLIC;
    }

    public long revision() {
        return revision;
    }

    public void onChanged(Consumer<UUID> listener) {
        changed = Objects.requireNonNull(listener);
    }

    public CompletableFuture<Visibility> set(UUID uuid, Visibility visibility) {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(visibility);
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Profile visibility is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> update(uuid, visibility), writer);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public synchronized RankingSnapshot filter(RankingSnapshot snapshot) {
        RankingSnapshot result = (RankingSnapshot) boards.get(snapshot);
        if (result == null) {
            result = snapshot.visible(this::isServerVisible);
            cache(snapshot, result);
        }
        return result;
    }

    public synchronized DetailRankingSnapshot filter(DetailRankingSnapshot snapshot) {
        DetailRankingSnapshot result = (DetailRankingSnapshot) boards.get(snapshot);
        if (result == null) {
            result = snapshot.visible(this::isServerVisible);
            cache(snapshot, result);
        }
        return result;
    }

    @Override
    public void close() {
        closed = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Profile visibility writes are still finishing after shutdown wait");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrupted while finishing profile visibility writes", exception);
        }
        synchronized (this) {
            boards.clear();
        }
    }

    private Visibility update(UUID uuid, Visibility visibility) {
        ready.join();
        if (values.getOrDefault(uuid, Visibility.SERVER) == visibility) {
            return visibility;
        }
        Map<UUID, Visibility> updated = new HashMap<>(values);
        if (visibility == Visibility.SERVER) {
            updated.remove(uuid);
        } else {
            updated.put(uuid, visibility);
        }
        try {
            save(updated);
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not save profile visibility for " + uuid, exception);
            throw new CompletionException(exception);
        }
        synchronized (this) {
            values = Map.copyOf(updated);
            revision++;
            boards.clear();
        }
        try {
            changed.accept(uuid);
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Profile visibility was saved but open views could not be refreshed for " + uuid, exception);
        }
        return visibility;
    }

    private void load() {
        Map<UUID, Visibility> loadedValues = new HashMap<>();
        try {
            if (Files.exists(file)) {
                if (!Files.isRegularFile(file) || Files.size(file) > 16 * 1024 * 1024) {
                    throw new IOException("Invalid profile visibility file");
                }
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!root.keySet().equals(Set.of("version", "players")) || !root.get("version").isJsonPrimitive()
                        || !root.get("version").getAsJsonPrimitive().isNumber() || !root.get("version").getAsString().equals("1")) {
                    throw new IOException("Invalid profile visibility format");
                }
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("players").entrySet()) {
                    UUID uuid = UUID.fromString(entry.getKey());
                    if (!uuid.toString().equals(entry.getKey()) || !entry.getValue().isJsonPrimitive()
                            || !entry.getValue().getAsJsonPrimitive().isString()) {
                        throw new IOException("Invalid profile visibility entry");
                    }
                    loadedValues.put(uuid, Visibility.valueOf(entry.getValue().getAsString().toUpperCase(Locale.ROOT)));
                }
            }
            synchronized (this) {
                values = Map.copyOf(loadedValues);
                loaded = true;
                revision++;
                boards.clear();
            }
        } catch (IOException | RuntimeException exception) {
            throw new CompletionException(new IOException("Cannot read profile visibility from " + file, exception));
        }
    }

    private void save(Map<UUID, Visibility> values) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject players = new JsonObject();
        values.forEach((uuid, visibility) -> players.addProperty(uuid.toString(), visibility.id()));
        root.add("players", players);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "visibility-", ".tmp");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(GSON.toJson(root));
            if (bytes.remaining() > 16 * 1024 * 1024) {
                throw new IOException("Profile visibility exceeds the 16 MiB limit");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void cache(Object source, Object filtered) {
        if (boards.size() >= MAX_CACHED_BOARDS) {
            boards.remove(boards.keySet().iterator().next());
        }
        boards.put(source, filtered);
    }
}
