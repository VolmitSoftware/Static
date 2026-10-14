package com.volmit.staticstats.participation;

import com.volmit.staticstats.stats.HistorySettings;
import com.volmit.staticstats.stats.PeriodWindow;
import com.volmit.staticstats.stats.StatsPeriod;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
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

public final class ParticipationCache implements AutoCloseable {
    private static final long REFRESH_MILLIS = 5_000;
    private static final long IDLE_MILLIS = 300_000;

    private final Supplier<ParticipationCapture> source;
    private final Supplier<HistorySettings> settings;
    private final Clock clock;
    private final ScheduledExecutorService worker;
    private final Consumer<RuntimeException> failureHandler;
    private final Object lock = new Object();
    private final Map<ParticipationQuery, Entry> entries = new HashMap<>();
    private ParticipationCapture capture;
    private boolean closed;

    ParticipationCache(Supplier<ParticipationCapture> source, Supplier<HistorySettings> settings, Clock clock,
                       ScheduledExecutorService worker, Consumer<RuntimeException> failureHandler) {
        this.source = Objects.requireNonNull(source);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.worker = Objects.requireNonNull(worker);
        this.failureHandler = Objects.requireNonNull(failureHandler);
        worker.scheduleWithFixedDelay(this::evictIdle, 1, 1, TimeUnit.MINUTES);
    }

    public static ParticipationCache create(Supplier<ParticipationCapture> source, Supplier<HistorySettings> settings, Logger logger) {
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Static-Participation");
            thread.setDaemon(true);
            return thread;
        });
        return new ParticipationCache(source, settings, Clock.systemUTC(), worker,
                exception -> logger.log(Level.SEVERE, "Participation summary refresh failed", exception));
    }

    @Override
    public void close() {
        List<CompletableFuture<ParticipationSnapshot>> pending = new ArrayList<>();
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
        for (CompletableFuture<ParticipationSnapshot> completion : pending) {
            completion.completeExceptionally(new IllegalStateException("Participation cache is stopped"));
        }
    }

    public Optional<ParticipationSnapshot> cached(StatsPeriod period, int offset) {
        return Optional.ofNullable(request(new ParticipationQuery(period, offset)).snapshot());
    }

    public CompletableFuture<ParticipationSnapshot> summary(StatsPeriod period, int offset) {
        return request(new ParticipationQuery(period, offset)).completion().copy();
    }

    private Request request(ParticipationQuery query) {
        synchronized (lock) {
            HistorySettings currentSettings = settings.get();
            long now = clock.millis();
            PeriodWindow window = query.window(now, currentSettings);
            if (closed) {
                return new Request(null, CompletableFuture.failedFuture(new IllegalStateException("Participation cache is stopped")));
            }
            evictIdle(now);
            Entry entry = entries.computeIfAbsent(query, ignored -> new Entry());
            entry.apply(currentSettings, window);
            entry.lastAccess = now;
            if (entry.snapshot != null && fresh(entry.snapshot.refreshedAt(), now)) {
                return new Request(entry.snapshot, CompletableFuture.completedFuture(entry.snapshot));
            }
            if (entry.failure != null && (now < entry.failedAt || now - entry.failedAt < REFRESH_MILLIS)) {
                return new Request(entry.snapshot, CompletableFuture.failedFuture(entry.failure));
            }
            if (entry.pending == null) {
                entry.pending = new CompletableFuture<>();
                CompletableFuture<ParticipationSnapshot> completion = entry.pending;
                try {
                    worker.execute(() -> refresh(query, entry, completion));
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

    private void refresh(ParticipationQuery query, Entry entry, CompletableFuture<ParticipationSnapshot> completion) {
        try {
            HistorySettings currentSettings = settings.get();
            if (!validate(query, entry, completion, currentSettings)) {
                return;
            }
            ParticipationCapture current;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                current = capture;
            }
            PeriodWindow window = query.window(clock.millis(), currentSettings);
            if (current == null || !current.settings().equals(currentSettings) || !fresh(current.capturedAt(), clock.millis())
                    || !query.window(current.capturedAt(), current.settings()).equals(window)) {
                current = source.get();
            }
            if (!validate(query, entry, completion, current.settings())) {
                return;
            }
            ParticipationSnapshot snapshot = ParticipationSnapshot.create(query, current);
            HistorySettings latestSettings = settings.get();
            if (!validate(query, entry, completion, latestSettings)) {
                return;
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
                PeriodWindow latestWindow = query.window(clock.millis(), latestSettings);
                entry.apply(latestSettings, latestWindow);
                if (!current.settings().equals(latestSettings) || !snapshot.window().equals(latestWindow)) {
                    worker.execute(() -> refresh(query, entry, completion));
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

    private boolean validate(ParticipationQuery query, Entry entry, CompletableFuture<ParticipationSnapshot> completion,
                             HistorySettings current) {
        if (query.offset() < current.retention(query.period())) {
            return true;
        }
        synchronized (lock) {
            if (entry.pending == completion) {
                entries.remove(query, entry);
                entry.pending = null;
            }
        }
        completion.completeExceptionally(new IllegalArgumentException("Participation offset is outside the configured history retention"));
        return false;
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

    private static final class Entry {
        private ParticipationSnapshot snapshot;
        private CompletableFuture<ParticipationSnapshot> pending;
        private RuntimeException failure;
        private long failedAt;
        private long lastAccess;
        private HistorySettings settings;
        private PeriodWindow window;

        private void apply(HistorySettings current, PeriodWindow currentWindow) {
            if (!current.equals(settings) || !currentWindow.equals(window)) {
                settings = current;
                window = currentWindow;
                snapshot = null;
                failure = null;
            }
        }
    }

    private record Request(ParticipationSnapshot snapshot, CompletableFuture<ParticipationSnapshot> completion) { }
}
