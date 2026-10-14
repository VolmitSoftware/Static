package com.volmit.staticstats.stats;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class RankingCache implements AutoCloseable {
    private static final long REFRESH_MILLIS = 5_000;
    private static final long IDLE_MILLIS = 300_000;

    private final Function<PeriodWindow, List<PlayerStatsSnapshot>> source;
    private final Supplier<ZoneId> historyZone;
    private final Supplier<RankingRules> rules;
    private final Clock clock;
    private final ScheduledExecutorService worker;
    private final Consumer<RuntimeException> failureHandler;
    private final Object lock = new Object();
    private final Map<RankingKey, Entry> entries = new HashMap<>();
    private final Map<StatsPeriod, Capture> captures = new EnumMap<>(StatsPeriod.class);
    private boolean closed;

    RankingCache(Dependencies dependencies) {
        source = dependencies.source();
        historyZone = dependencies.historyZone();
        rules = dependencies.rules();
        clock = dependencies.clock();
        worker = dependencies.worker();
        failureHandler = dependencies.failureHandler();
        worker.scheduleWithFixedDelay(this::evictIdle, 1, 1, TimeUnit.MINUTES);
    }

    static RankingCache create(Function<PeriodWindow, List<PlayerStatsSnapshot>> source, Supplier<ZoneId> historyZone,
                               Supplier<RankingRules> rules, Logger logger) {
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Static-Rankings");
            thread.setDaemon(true);
            return thread;
        });
        return new RankingCache(new Dependencies(source, historyZone, rules, Clock.systemUTC(), worker,
                exception -> logger.log(Level.SEVERE, "Statistics ranking refresh failed", exception)));
    }

    @Override
    public void close() {
        List<CompletableFuture<RankingSnapshot>> pending = new ArrayList<>();
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
            captures.clear();
        }
        worker.shutdownNow();
        for (CompletableFuture<RankingSnapshot> completion : pending) {
            completion.completeExceptionally(new IllegalStateException("Statistics ranking cache is stopped"));
        }
    }

    Optional<RankingSnapshot> cachedRanking(StatKey statistic) {
        return cachedRanking(statistic, StatsPeriod.LIFETIME);
    }

    Optional<RankingSnapshot> cachedRanking(StatKey statistic, StatsPeriod period) {
        return Optional.ofNullable(request(new RankingKey(statistic, period)).snapshot());
    }

    CompletableFuture<RankingSnapshot> ranking(StatKey statistic) {
        return ranking(statistic, StatsPeriod.LIFETIME);
    }

    CompletableFuture<RankingSnapshot> ranking(StatKey statistic, StatsPeriod period) {
        return request(new RankingKey(statistic, period)).completion().copy();
    }

    private Request request(RankingKey key) {
        synchronized (lock) {
            if (closed) {
                return new Request(null, CompletableFuture.failedFuture(
                        new IllegalStateException("Statistics ranking cache is stopped")));
            }
            long now = clock.millis();
            evictIdle(now);
            Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
            entry.apply(Objects.requireNonNull(rules.get()), key.period().window(now, historyZone.get()));
            entry.lastAccess = now;
            if (entry.snapshot != null && fresh(entry.snapshot.refreshedAt(), now)) {
                return new Request(entry.snapshot, CompletableFuture.completedFuture(entry.snapshot));
            }
            if (entry.failure != null && (now < entry.failedAt || now - entry.failedAt < REFRESH_MILLIS)) {
                return new Request(entry.snapshot, CompletableFuture.failedFuture(entry.failure));
            }
            if (entry.pending == null) {
                entry.pending = new CompletableFuture<>();
                CompletableFuture<RankingSnapshot> completion = entry.pending;
                try {
                    worker.execute(() -> refresh(key, entry, completion));
                } catch (RejectedExecutionException exception) {
                    entry.pending = null;
                    completion.completeExceptionally(exception);
                    return new Request(entry.snapshot, completion);
                }
            }
            return new Request(entry.snapshot, entry.pending);
        }
    }

    private void refresh(RankingKey key, Entry entry, CompletableFuture<RankingSnapshot> completion) {
        try {
            Capture capture;
            RankingRules appliedRules;
            ZoneId zone;
            PeriodWindow window;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                zone = historyZone.get();
                window = key.period().window(clock.millis(), zone);
                capture = captures.get(key.period());
                appliedRules = Objects.requireNonNull(rules.get());
                entry.apply(appliedRules, window);
            }
            if (capture == null || !capture.window().equals(window) || !capture.zone().equals(zone)
                    || !fresh(capture.capturedAt(), clock.millis())) {
                capture = new Capture(window, zone, clock.millis(), List.copyOf(source.apply(window)));
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
            }
            RankingSnapshot snapshot = RankingSnapshot.create(key.statistic(), capture.capturedAt(), capture.profiles(),
                    appliedRules, window, zone);
            synchronized (lock) {
                if (closed) {
                    return;
                }
                captures.put(key.period(), capture);
                RankingRules latestRules = Objects.requireNonNull(rules.get());
                ZoneId latestZone = historyZone.get();
                PeriodWindow latestWindow = key.period().window(clock.millis(), latestZone);
                entry.apply(latestRules, latestWindow);
                if (!appliedRules.equals(latestRules) || !window.equals(latestWindow) || !zone.equals(latestZone)) {
                    worker.execute(() -> refresh(key, entry, completion));
                    return;
                }
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
                entry.apply(Objects.requireNonNull(rules.get()), key.period().window(clock.millis(), historyZone.get()));
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
            captures.clear();
        }
    }

    private static boolean fresh(long captured, long now) {
        return now >= captured && now - captured < REFRESH_MILLIS;
    }

    record Dependencies(Function<PeriodWindow, List<PlayerStatsSnapshot>> source, Supplier<ZoneId> historyZone,
                        Supplier<RankingRules> rules, Clock clock, ScheduledExecutorService worker,
                        Consumer<RuntimeException> failureHandler) {
        Dependencies {
            Objects.requireNonNull(source);
            Objects.requireNonNull(historyZone);
            Objects.requireNonNull(rules);
            Objects.requireNonNull(clock);
            Objects.requireNonNull(worker);
            Objects.requireNonNull(failureHandler);
        }
    }

    private static final class Entry {
        private RankingSnapshot snapshot;
        private CompletableFuture<RankingSnapshot> pending;
        private RuntimeException failure;
        private long failedAt;
        private long lastAccess;
        private RankingRules rules;
        private PeriodWindow window;

        private void apply(RankingRules current, PeriodWindow currentWindow) {
            if (!current.equals(rules) || !currentWindow.equals(window)) {
                rules = current;
                window = currentWindow;
                snapshot = null;
                failure = null;
            }
        }
    }

    private record Request(RankingSnapshot snapshot, CompletableFuture<RankingSnapshot> completion) { }

    private record Capture(PeriodWindow window, ZoneId zone, long capturedAt, List<PlayerStatsSnapshot> profiles) { }

    private record RankingKey(StatKey statistic, StatsPeriod period) {
        private RankingKey {
            Objects.requireNonNull(statistic);
            Objects.requireNonNull(period);
        }
    }
}
