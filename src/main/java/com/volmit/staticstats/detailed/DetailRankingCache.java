package com.volmit.staticstats.detailed;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DetailRankingCache implements AutoCloseable {
    private static final long REFRESH_MILLIS = 5_000;
    private static final long IDLE_MILLIS = 300_000;

    private final Supplier<List<DetailSnapshot>> source;
    private final Clock clock;
    private final ScheduledExecutorService worker;
    private final Consumer<RuntimeException> failureHandler;
    private final Object lock = new Object();
    private final Map<DetailKey, Entry> entries = new EnumMap<>(DetailKey.class);
    private Capture capture;
    private boolean closed;

    DetailRankingCache(Dependencies dependencies) {
        source = dependencies.source();
        clock = dependencies.clock();
        worker = dependencies.worker();
        failureHandler = dependencies.failureHandler();
        worker.scheduleWithFixedDelay(this::evictIdle, 1, 1, TimeUnit.MINUTES);
    }

    public static DetailRankingCache create(Supplier<List<DetailSnapshot>> source, Logger logger) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(logger);
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Static-Detail-Rankings");
            thread.setDaemon(true);
            return thread;
        });
        return new DetailRankingCache(new Dependencies(source, Clock.systemUTC(), worker,
                exception -> logger.log(Level.SEVERE, "Detailed counter ranking refresh failed", exception)));
    }

    @Override
    public void close() {
        List<CompletableFuture<DetailRankingSnapshot>> pending = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            for (Entry entry : entries.values()) {
                if (entry.pending != null) {
                    pending.add(entry.pending);
                }
            }
            entries.clear();
            capture = null;
        }
        worker.shutdownNow();
        for (CompletableFuture<DetailRankingSnapshot> completion : pending) {
            completion.completeExceptionally(new IllegalStateException("Detailed ranking cache is stopped"));
        }
    }

    public Optional<DetailRankingSnapshot> cachedRanking(DetailKey counter) {
        return Optional.ofNullable(request(Objects.requireNonNull(counter)).snapshot());
    }

    public CompletableFuture<DetailRankingSnapshot> ranking(DetailKey counter) {
        return request(Objects.requireNonNull(counter)).completion().copy();
    }

    private Request request(DetailKey counter) {
        synchronized (lock) {
            if (closed) {
                return new Request(null, CompletableFuture.failedFuture(
                        new IllegalStateException("Detailed ranking cache is stopped")));
            }
            long now = clock.millis();
            evictIdle(now);
            Entry entry = entries.computeIfAbsent(counter, ignored -> new Entry());
            entry.lastAccess = now;
            if (entry.snapshot != null && fresh(entry.snapshot.refreshedAt(), now)) {
                return new Request(entry.snapshot, CompletableFuture.completedFuture(entry.snapshot));
            }
            if (entry.failure != null && (now < entry.failedAt || now - entry.failedAt < REFRESH_MILLIS)) {
                return new Request(entry.snapshot, CompletableFuture.failedFuture(entry.failure));
            }
            if (entry.pending == null) {
                entry.pending = new CompletableFuture<>();
                CompletableFuture<DetailRankingSnapshot> completion = entry.pending;
                try {
                    worker.execute(() -> refresh(counter, entry, completion));
                } catch (RejectedExecutionException exception) {
                    entry.pending = null;
                    entry.failure = exception;
                    entry.failedAt = now;
                    completion.completeExceptionally(exception);
                    return new Request(entry.snapshot, completion);
                }
            }
            return new Request(entry.snapshot, entry.pending);
        }
    }

    private void refresh(DetailKey counter, Entry entry, CompletableFuture<DetailRankingSnapshot> completion) {
        try {
            Capture current;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                current = capture;
            }
            if (current == null || !fresh(current.capturedAt(), clock.millis())) {
                long capturedAt = clock.millis();
                current = new Capture(capturedAt, List.copyOf(source.get()));
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
            }
            DetailRankingSnapshot snapshot = DetailRankingSnapshot.create(counter, current.capturedAt(), current.profiles());
            synchronized (lock) {
                if (closed) {
                    return;
                }
                capture = current;
                entry.snapshot = snapshot;
                entry.pending = null;
                entry.failure = null;
            }
            completion.complete(snapshot);
        } catch (RuntimeException exception) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                entry.pending = null;
                entry.failure = exception;
                entry.failedAt = clock.millis();
            }
            completion.completeExceptionally(exception);
            failureHandler.accept(exception);
        }
    }

    private void evictIdle() {
        synchronized (lock) {
            evictIdle(clock.millis());
        }
    }

    private void evictIdle(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().pending == null
                && now - entry.getValue().lastAccess >= IDLE_MILLIS);
        if (entries.isEmpty()) {
            capture = null;
        }
    }

    private static boolean fresh(long capturedAt, long now) {
        return now >= capturedAt && now - capturedAt < REFRESH_MILLIS;
    }

    record Dependencies(Supplier<List<DetailSnapshot>> source, Clock clock, ScheduledExecutorService worker,
                        Consumer<RuntimeException> failureHandler) {
        Dependencies {
            Objects.requireNonNull(source);
            Objects.requireNonNull(clock);
            Objects.requireNonNull(worker);
            Objects.requireNonNull(failureHandler);
        }
    }

    private static final class Entry {
        private DetailRankingSnapshot snapshot;
        private CompletableFuture<DetailRankingSnapshot> pending;
        private RuntimeException failure;
        private long failedAt;
        private long lastAccess;
    }

    private record Request(DetailRankingSnapshot snapshot, CompletableFuture<DetailRankingSnapshot> completion) { }

    private record Capture(long capturedAt, List<DetailSnapshot> profiles) { }
}
