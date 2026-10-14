package com.volmit.staticstats.participation;

import com.volmit.staticstats.stats.HistorySettings;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.SessionEndReason;
import com.volmit.staticstats.stats.StatsPeriod;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ParticipationCacheTest {
    @Test
    void coalescesColdRequestsOffCallerAndProtectsOtherWaitersFromCancellation() {
        try (Fixture fixture = new Fixture()) {
            List<CompletableFuture<ParticipationSnapshot>> waiters = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                assertTrue(fixture.cache.cached(StatsPeriod.DAY, 0).isEmpty());
                waiters.add(fixture.cache.summary(StatsPeriod.DAY, 0));
            }
            assertEquals(0, fixture.captures.get());
            assertEquals(1, fixture.queued.size());
            waiters.getFirst().cancel(false);
            fixture.runNext();
            ParticipationSnapshot result = waiters.getLast().join();
            assertEquals(1, result.uniquePlayers());
            assertTrue(waiters.getFirst().isCancelled());
            for (CompletableFuture<ParticipationSnapshot> waiter : waiters.subList(1, waiters.size())) {
                assertSame(result, waiter.join());
            }
        }
    }

    @Test
    void sharesCapturedProfilesAcrossWindowsAndServesStaleWhileRefreshRuns() {
        try (Fixture fixture = new Fixture()) {
            CompletableFuture<ParticipationSnapshot> day = fixture.cache.summary(StatsPeriod.DAY, 0);
            fixture.runNext();
            fixture.profiles.set(List.of());
            CompletableFuture<ParticipationSnapshot> week = fixture.cache.summary(StatsPeriod.WEEK, 0);
            fixture.runNext();
            assertEquals(1, week.join().uniquePlayers());
            assertEquals(day.join().refreshedAt(), week.join().refreshedAt());
            assertEquals(1, fixture.captures.get());
            fixture.clock.advance(5_000);
            assertSame(day.join(), fixture.cache.cached(StatsPeriod.DAY, 0).orElseThrow());
            CompletableFuture<ParticipationSnapshot> changed = fixture.cache.summary(StatsPeriod.DAY, 0);
            assertFalse(changed.isDone());
            fixture.runNext();
            assertEquals(0, changed.join().uniquePlayers());
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void rolloverInvalidatesCurrentAndPriorOffsetsInsideTheFiveSecondTtl() {
        try (Fixture fixture = new Fixture()) {
            fixture.clock.now.set(Instant.parse("2026-05-31T23:59:59Z").toEpochMilli());
            CompletableFuture<ParticipationSnapshot> current = fixture.cache.summary(StatsPeriod.DAY, 0);
            CompletableFuture<ParticipationSnapshot> previous = fixture.cache.summary(StatsPeriod.DAY, 1);
            fixture.runNext();
            fixture.runNext();
            assertEquals("2026-05-31", current.join().window().id());
            assertEquals("2026-05-30", previous.join().window().id());
            fixture.clock.advance(1_000);
            assertTrue(fixture.cache.cached(StatsPeriod.DAY, 0).isEmpty());
            assertTrue(fixture.cache.cached(StatsPeriod.DAY, 1).isEmpty());
            CompletableFuture<ParticipationSnapshot> newCurrent = fixture.cache.summary(StatsPeriod.DAY, 0);
            CompletableFuture<ParticipationSnapshot> newPrevious = fixture.cache.summary(StatsPeriod.DAY, 1);
            fixture.runNext();
            fixture.runNext();
            assertEquals("2026-06-01", newCurrent.join().window().id());
            assertEquals("2026-05-31", newPrevious.join().window().id());
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void retentionChangesInvalidateCapturesAndRejectOutOfRangeCachedRequests() {
        try (Fixture fixture = new Fixture()) {
            CompletableFuture<ParticipationSnapshot> old = fixture.cache.summary(StatsPeriod.DAY, 1);
            fixture.runNext();
            assertTrue(old.isDone());
            fixture.settings.set(new HistorySettings(ZoneOffset.UTC, 5_000, 1, 1, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> fixture.cache.cached(StatsPeriod.DAY, 1));
            assertThrows(IllegalArgumentException.class, () -> fixture.cache.summary(StatsPeriod.DAY, 1));
            fixture.profiles.set(List.of());
            CompletableFuture<ParticipationSnapshot> updated = fixture.cache.summary(StatsPeriod.DAY, 0);
            fixture.runNext();
            assertEquals(0, updated.join().uniquePlayers());
            assertEquals(2, fixture.captures.get());
            assertThrows(IllegalArgumentException.class, () -> fixture.cache.summary(StatsPeriod.LIFETIME, 0));
            assertThrows(IllegalArgumentException.class, () -> fixture.cache.summary(StatsPeriod.MONTH, -1));
        }
    }

    @Test
    void settingsChangedDuringCaptureDiscardOldResultsAndQueuedInvalidOffsetsFailWithoutLogging() {
        try (Fixture fixture = new Fixture()) {
            fixture.captureHook.set(() -> {
                fixture.settings.set(new HistorySettings(ZoneOffset.UTC, 5_000, 1, 1, 1, 1));
                fixture.profiles.set(List.of());
            });
            CompletableFuture<ParticipationSnapshot> current = fixture.cache.summary(StatsPeriod.DAY, 0);
            CompletableFuture<ParticipationSnapshot> previous = fixture.cache.summary(StatsPeriod.DAY, 1);
            fixture.runNext();
            assertFalse(current.isDone());
            assertTrue(fixture.cache.cached(StatsPeriod.DAY, 0).isEmpty());
            fixture.runNext();
            assertTrue(previous.isCompletedExceptionally());
            fixture.runNext();
            assertEquals(0, current.join().uniquePlayers());
            assertTrue(fixture.failures.isEmpty());
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void failureBackoffKeepsPublishedDataAndIdleEvictionDropsItWithoutBackgroundCapture() {
        try (Fixture fixture = new Fixture()) {
            CompletableFuture<ParticipationSnapshot> first = fixture.cache.summary(StatsPeriod.DAY, 0);
            fixture.runNext();
            fixture.clock.advance(5_000);
            fixture.failure.set(new IllegalStateException("Capture failed"));
            CompletableFuture<ParticipationSnapshot> failed = fixture.cache.summary(StatsPeriod.DAY, 0);
            fixture.runNext();
            assertTrue(failed.isCompletedExceptionally());
            assertSame(first.join(), fixture.cache.cached(StatsPeriod.DAY, 0).orElseThrow());
            assertTrue(fixture.cache.summary(StatsPeriod.DAY, 0).isCompletedExceptionally());
            assertEquals(1, fixture.failures.size());
            assertTrue(fixture.queued.isEmpty());
            fixture.failure.set(null);
            fixture.clock.advance(300_000);
            fixture.sweep.get().run();
            assertEquals(2, fixture.captures.get());
            assertTrue(fixture.cache.cached(StatsPeriod.DAY, 0).isEmpty());
            fixture.runNext();
            assertEquals(3, fixture.captures.get());
        }
    }

    @Test
    void closeSettlesQueuedAndInProgressCaptureRequestsWithoutPublishingLateResults() {
        try (Fixture fixture = new Fixture()) {
            fixture.captureHook.set(fixture.cache::close);
            CompletableFuture<ParticipationSnapshot> active = fixture.cache.summary(StatsPeriod.DAY, 0);
            CompletableFuture<ParticipationSnapshot> pending = fixture.cache.summary(StatsPeriod.MONTH, 0);
            fixture.runNext();
            assertTrue(active.isCompletedExceptionally());
            assertTrue(pending.isCompletedExceptionally());
            fixture.runNext();
            assertEquals(1, fixture.captures.get());
            assertTrue(fixture.cache.cached(StatsPeriod.DAY, 0).isEmpty());
            assertTrue(fixture.cache.summary(StatsPeriod.DAY, 0).isCompletedExceptionally());
            assertTrue(fixture.failures.isEmpty());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MutableClock clock = new MutableClock();
        private final ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
        private final Queue<Runnable> queued = new ArrayDeque<>();
        private final AtomicReference<Runnable> sweep = new AtomicReference<>();
        private final AtomicReference<HistorySettings> settings = new AtomicReference<>(HistorySettings.defaults());
        private final AtomicReference<List<ParticipationProfile>> profiles = new AtomicReference<>(List.of(profile()));
        private final AtomicReference<Runnable> captureHook = new AtomicReference<>(() -> { });
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final List<RuntimeException> failures = new ArrayList<>();
        private final AtomicInteger captures = new AtomicInteger();
        private final ParticipationCache cache;

        private Fixture() {
            doAnswer(invocation -> queued.add(invocation.getArgument(0))).when(worker).execute(any(Runnable.class));
            doAnswer(invocation -> {
                sweep.set(invocation.getArgument(0));
                return null;
            }).when(worker).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MINUTES));
            cache = new ParticipationCache(this::capture, settings::get, clock, worker, failures::add);
        }

        @Override
        public void close() {
            cache.close();
        }

        private ParticipationCapture capture() {
            captures.incrementAndGet();
            if (failure.get() != null) {
                throw failure.get();
            }
            ParticipationCapture result = new ParticipationCapture(clock.millis(), settings.get(), profiles.get());
            captureHook.get().run();
            return result;
        }

        private void runNext() {
            queued.remove().run();
        }

        private static ParticipationProfile profile() {
            long start = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli();
            PlayerSession session = new PlayerSession(new UUID(0, 2), start, start + 1_000, start + 1_000,
                    1_000, 1_000, 0, SessionEndReason.QUIT);
            return new ParticipationProfile(new UUID(0, 1), start, true, true, -1, List.of(session));
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong now = new AtomicLong(Instant.parse("2026-10-01T12:00:02Z").toEpochMilli());

        private void advance(long amount) {
            now.addAndGet(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }

        @Override
        public long millis() {
            return now.get();
        }
    }
}
