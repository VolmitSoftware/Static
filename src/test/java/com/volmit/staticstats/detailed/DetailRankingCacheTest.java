package com.volmit.staticstats.detailed;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DetailRankingCacheTest {
    private static final DetailKey COUNTER = DetailKey.MINED_DIAMOND_ORE;

    @Test
    void coldReadsCoalesceWithoutCapturingOnTheCallerAndCancellationIsIsolated() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(9_007_199_254_740_993L)));
            List<CompletableFuture<DetailRankingSnapshot>> waiters = new ArrayList<>();
            for (int request = 0; request < 100; request++) {
                assertTrue(fixture.cache.cachedRanking(COUNTER).isEmpty());
                waiters.add(fixture.cache.ranking(COUNTER));
            }
            waiters.getFirst().cancel(false);
            assertEquals(0, fixture.captures.get());
            assertEquals(1, fixture.queued.size());
            assertFalse(waiters.getLast().isDone());
            fixture.runNext();
            DetailRankingSnapshot ranking = waiters.getLast().join();
            assertEquals(9_007_199_254_740_993L, ranking.entries().getFirst().value());
            for (CompletableFuture<DetailRankingSnapshot> waiter : waiters.subList(1, waiters.size())) {
                assertSame(ranking, waiter.join());
            }
            assertTrue(waiters.getFirst().isCancelled());
            assertEquals(1, fixture.captures.get());
            assertTrue(fixture.failures.isEmpty());
        }
    }

    @Test
    void countersShareOneDetachedCaptureAndPreserveItsStartTime() {
        try (Fixture fixture = new Fixture()) {
            List<DetailSnapshot> profiles = new ArrayList<>(List.of(profile(7)));
            fixture.profiles.set(profiles);
            fixture.captureHook.set(() -> fixture.clock.advance(100));
            long began = fixture.clock.millis();
            CompletableFuture<DetailRankingSnapshot> first = fixture.cache.ranking(COUNTER);
            fixture.runNext();
            profiles.clear();
            CompletableFuture<DetailRankingSnapshot> second = fixture.cache.ranking(DetailKey.CRAFTED_ITEMS);
            fixture.runNext();
            assertEquals(1, fixture.captures.get());
            assertEquals(began, first.join().refreshedAt());
            assertEquals(began, second.join().refreshedAt());
            assertSame(first.join().entries().getFirst().profile(), second.join().entries().getFirst().profile());
        }
    }

    @Test
    void staleCachedReadsRemainAvailableUntilTheFiveSecondRefreshCompletes() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(7)));
            CompletableFuture<DetailRankingSnapshot> original = fixture.cache.ranking(COUNTER);
            fixture.runNext();
            fixture.profiles.set(List.of(profile(19)));
            fixture.clock.advance(4_999);
            assertSame(original.join(), fixture.cache.ranking(COUNTER).join());
            fixture.clock.advance(1);
            assertSame(original.join(), fixture.cache.cachedRanking(COUNTER).orElseThrow());
            CompletableFuture<DetailRankingSnapshot> updated = fixture.cache.ranking(COUNTER);
            assertFalse(updated.isDone());
            assertEquals(1, fixture.queued.size());
            fixture.runNext();
            assertEquals(19, updated.join().entries().getFirst().value());
            assertEquals(7, original.join().entries().getFirst().value());
            assertNotSame(original.join(), updated.join());
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void requestsAreBoundedByCatalogAndIdleSweepReleasesTheSharedCapture() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(7)));
            for (DetailKey key : DetailKey.values()) {
                fixture.cache.cachedRanking(key);
                fixture.cache.ranking(key);
            }
            assertEquals(DetailKey.values().length, fixture.queued.size());
            while (!fixture.queued.isEmpty()) {
                fixture.runNext();
            }
            assertEquals(1, fixture.captures.get());
            fixture.clock.advance(300_000);
            fixture.sweep.get().run();
            assertTrue(fixture.queued.isEmpty());
            assertTrue(fixture.cache.cachedRanking(COUNTER).isEmpty());
            fixture.runNext();
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void failuresBackOffPerCounterAndRetainTheLastPublishedRanking() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(7)));
            CompletableFuture<DetailRankingSnapshot> initial = fixture.cache.ranking(COUNTER);
            fixture.runNext();
            fixture.clock.advance(5_000);
            fixture.captureFailure.set(new IllegalStateException("Capture failed"));
            CompletableFuture<DetailRankingSnapshot> failed = fixture.cache.ranking(COUNTER);
            fixture.runNext();
            assertTrue(failed.isCompletedExceptionally());
            for (int request = 0; request < 100; request++) {
                assertSame(initial.join(), fixture.cache.cachedRanking(COUNTER).orElseThrow());
                assertTrue(fixture.cache.ranking(COUNTER).isCompletedExceptionally());
            }
            assertTrue(fixture.queued.isEmpty());
            assertEquals(1, fixture.failures.size());
            fixture.captureFailure.set(null);
            fixture.clock.advance(4_999);
            assertTrue(fixture.cache.ranking(COUNTER).isCompletedExceptionally());
            fixture.clock.advance(1);
            CompletableFuture<DetailRankingSnapshot> retried = fixture.cache.ranking(COUNTER);
            fixture.runNext();
            assertEquals(7, retried.join().entries().getFirst().value());
            assertEquals(3, fixture.captures.get());
        }
    }

    @Test
    void clockRollbackRefreshesInsteadOfTreatingFutureSnapshotsAsFresh() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(7)));
            CompletableFuture<DetailRankingSnapshot> original = fixture.cache.ranking(COUNTER);
            fixture.runNext();
            fixture.clock.advance(-500);
            fixture.profiles.set(List.of(profile(8)));
            CompletableFuture<DetailRankingSnapshot> updated = fixture.cache.ranking(COUNTER);
            fixture.runNext();
            assertEquals(8, updated.join().entries().getFirst().value());
            assertTrue(updated.join().refreshedAt() < original.join().refreshedAt());
        }
    }

    @Test
    void closeFailsPendingWaitersAndQueuedWorkCannotPublish() {
        Fixture fixture = new Fixture();
        CompletableFuture<DetailRankingSnapshot> pending = fixture.cache.ranking(COUNTER);
        fixture.close();
        fixture.close();
        assertTrue(pending.isCompletedExceptionally());
        fixture.runNext();
        assertEquals(0, fixture.captures.get());
        assertTrue(fixture.cache.cachedRanking(COUNTER).isEmpty());
        assertTrue(fixture.cache.ranking(COUNTER).isCompletedExceptionally());
        verify(fixture.worker).shutdownNow();
    }

    @Test
    void closeDuringAnActiveCaptureCompletesWaitersAndDiscardsLateResults() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        List<RuntimeException> failures = new ArrayList<>();
        DetailRankingCache cache = new DetailRankingCache(new DetailRankingCache.Dependencies(() -> {
            started.countDown();
            await(release);
            return List.of(profile(7));
        }, Clock.systemUTC(), worker, failures::add));
        try {
            CompletableFuture<DetailRankingSnapshot> active = cache.ranking(COUNTER);
            CompletableFuture<DetailRankingSnapshot> queued = cache.ranking(DetailKey.CRAFTED_ITEMS);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            cache.close();
            assertTrue(active.isCompletedExceptionally());
            assertTrue(queued.isCompletedExceptionally());
            release.countDown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(cache.cachedRanking(COUNTER).isEmpty());
            assertTrue(failures.isEmpty());
        } finally {
            release.countDown();
            cache.close();
        }
    }

    private static DetailSnapshot profile(long amount) {
        return new DetailSnapshot(new UUID(0, 1), "River", Map.of(COUNTER, amount, DetailKey.CRAFTED_ITEMS, 4L),
                Map.of(COUNTER, 100L, DetailKey.CRAFTED_ITEMS, 100L),
                Map.of(COUNTER, 100L, DetailKey.CRAFTED_ITEMS, 100L), 100L);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Capture timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MutableClock clock = new MutableClock();
        private final ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
        private final Queue<Runnable> queued = new ArrayDeque<>();
        private final AtomicReference<Runnable> sweep = new AtomicReference<>();
        private final AtomicReference<List<DetailSnapshot>> profiles = new AtomicReference<>(List.of());
        private final AtomicReference<Runnable> captureHook = new AtomicReference<>(() -> { });
        private final AtomicReference<RuntimeException> captureFailure = new AtomicReference<>();
        private final AtomicInteger captures = new AtomicInteger();
        private final List<RuntimeException> failures = new ArrayList<>();
        private final DetailRankingCache cache;

        private Fixture() {
            doAnswer(invocation -> queued.add(invocation.getArgument(0))).when(worker).execute(any(Runnable.class));
            doAnswer(invocation -> {
                sweep.set(invocation.getArgument(0));
                return null;
            }).when(worker).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MINUTES));
            cache = new DetailRankingCache(new DetailRankingCache.Dependencies(this::capture, clock, worker, failures::add));
        }

        @Override
        public void close() {
            cache.close();
        }

        private List<DetailSnapshot> capture() {
            captures.incrementAndGet();
            RuntimeException failure = captureFailure.get();
            if (failure != null) {
                throw failure;
            }
            captureHook.get().run();
            return profiles.get();
        }

        private void runNext() {
            queued.remove().run();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong now = new AtomicLong(10_000);

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
