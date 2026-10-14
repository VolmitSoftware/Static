package com.volmit.staticstats.stats;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RankingCacheTest {
    @Test
    void coldReadsQueueOneRefreshWithoutReadingProfilesOrSortingOnCaller() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(1, "River", 7)));
            List<CompletableFuture<RankingSnapshot>> waiters = new ArrayList<>();
            for (int request = 0; request < 100; request++) {
                assertTrue(fixture.cache.cachedRanking(StatKey.BLOCKS_BROKEN).isEmpty());
                waiters.add(fixture.cache.ranking(StatKey.BLOCKS_BROKEN));
            }
            assertEquals(0, fixture.captures.get());
            assertEquals(1, fixture.queued.size());
            assertFalse(waiters.getFirst().isDone());
            fixture.runNext();
            RankingSnapshot ranking = waiters.getFirst().join();
            assertEquals(7, ranking.entries().getFirst().value());
            for (CompletableFuture<RankingSnapshot> waiter : waiters) {
                assertSame(ranking, waiter.join());
            }
            assertEquals(1, fixture.captures.get());
            assertTrue(fixture.failures.isEmpty());
        }
    }

    @Test
    void differentStatisticsReuseTheSameImmutableProfileCapture() {
        try (Fixture fixture = new Fixture()) {
            List<PlayerStatsSnapshot> source = new ArrayList<>(List.of(profile(1, "River", 7)));
            fixture.profiles.set(source);
            CompletableFuture<RankingSnapshot> first = fixture.cache.ranking(StatKey.BLOCKS_BROKEN);
            fixture.runNext();
            source.clear();
            CompletableFuture<RankingSnapshot> second = fixture.cache.ranking(StatKey.VOTES);
            fixture.runNext();
            assertEquals(1, fixture.captures.get());
            assertSame(first.join().entries().getFirst().profile(), second.join().entries().getFirst().profile());
            assertEquals(first.join().refreshedAt(), second.join().refreshedAt());
            assertThrows(UnsupportedOperationException.class, () -> first.join().entries().clear());
            assertThrows(UnsupportedOperationException.class,
                    () -> first.join().entries().getFirst().profile().values().clear());
        }
    }

    @Test
    void cachedReadsServePreviousSnapshotWhileFreshRequestsWaitAfterFiveSeconds() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(1, "River", 7)));
            CompletableFuture<RankingSnapshot> initial = fixture.cache.ranking(StatKey.BLOCKS_BROKEN);
            fixture.runNext();
            fixture.profiles.set(List.of(profile(1, "River", 19)));
            fixture.clock.advance(4_999);
            assertSame(initial.join(), fixture.cache.ranking(StatKey.BLOCKS_BROKEN).join());
            assertTrue(fixture.queued.isEmpty());
            fixture.clock.advance(1);
            assertSame(initial.join(), fixture.cache.cachedRanking(StatKey.BLOCKS_BROKEN).orElseThrow());
            CompletableFuture<RankingSnapshot> refreshing = fixture.cache.ranking(StatKey.BLOCKS_BROKEN);
            assertFalse(refreshing.isDone());
            assertEquals(1, fixture.queued.size());
            fixture.runNext();
            assertEquals(19, refreshing.join().entries().getFirst().value());
            assertEquals(fixture.clock.millis(), refreshing.join().refreshedAt());
            assertNotSame(initial.join(), refreshing.join());
            assertEquals(7, initial.join().entries().getFirst().value());
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void ranksTiesByCompetitionAndOrdersRowsByNameThenUuid() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(4, "Bee", 5), profile(3, "Zed", 10),
                    profile(2, "amy", 10), profile(6, "Lowest", 0), profile(1, "Amy", 10),
                    profile(5, "Cat", 5)));
            CompletableFuture<RankingSnapshot> completion = fixture.cache.ranking(StatKey.BLOCKS_BROKEN);
            fixture.runNext();
            RankingSnapshot ranking = completion.join();
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), ranking.entries().stream()
                    .map(entry -> entry.profile().uuid().getLeastSignificantBits()).toList());
            assertEquals(List.of(1, 2, 3, 4, 5, 6), ranking.entries().stream().map(RankedPlayer::position).toList());
            assertEquals(List.of(1, 1, 1, 4, 4, 6), ranking.entries().stream().map(RankedPlayer::rank).toList());
            assertEquals(List.of(0D, 0D, 0D, 5D, 5D, 5D), ranking.entries().stream().map(RankedPlayer::gapToNext).toList());
            assertSame(ranking.entries().get(4), ranking.find(new UUID(0, 5)).orElseThrow());
            assertTrue(ranking.find(new UUID(0, 99)).isEmpty());
        }
    }

    @Test
    void requestsOnlyEnumeratedStatisticsAndEvictsIdleSnapshotsAfterFiveMinutes() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(1, "River", 7)));
            for (StatKey statistic : StatKey.values()) {
                fixture.cache.cachedRanking(statistic);
                fixture.cache.ranking(statistic);
            }
            assertEquals(StatKey.values().length, fixture.queued.size());
            while (!fixture.queued.isEmpty()) {
                fixture.runNext();
            }
            assertEquals(1, fixture.captures.get());
            fixture.clock.advance(299_999);
            fixture.sweep.get().run();
            assertTrue(fixture.queued.isEmpty());
            fixture.clock.advance(1);
            fixture.sweep.get().run();
            assertTrue(fixture.cache.cachedRanking(StatKey.BLOCKS_BROKEN).isEmpty());
            assertEquals(1, fixture.queued.size());
            fixture.runNext();
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void cancelledCallerDoesNotCancelAnotherWaitingCallerOrTheRefresh() {
        try (Fixture fixture = new Fixture()) {
            CompletableFuture<RankingSnapshot> cancelled = fixture.cache.ranking(StatKey.VOTES);
            CompletableFuture<RankingSnapshot> waiting = fixture.cache.ranking(StatKey.VOTES);
            cancelled.cancel(false);
            fixture.runNext();
            assertTrue(cancelled.isCancelled());
            assertTrue(waiting.join().entries().isEmpty());
            assertTrue(fixture.cache.cachedRanking(StatKey.VOTES).isPresent());
        }
    }

    @Test
    void closeFailsPendingWaitersAndPreventsQueuedTasksFromPublishing() {
        Fixture fixture = new Fixture();
        CompletableFuture<RankingSnapshot> pending = fixture.cache.ranking(StatKey.VOTES);
        fixture.close();
        assertTrue(pending.isCompletedExceptionally());
        fixture.runNext();
        assertEquals(0, fixture.captures.get());
        assertTrue(fixture.cache.cachedRanking(StatKey.VOTES).isEmpty());
        assertTrue(fixture.cache.ranking(StatKey.VOTES).isCompletedExceptionally());
        verify(fixture.worker).shutdownNow();
    }

    @Test
    void failedCaptureCompletesWaitersAndBacksOffBeforeRetrying() {
        try (Fixture fixture = new Fixture()) {
            fixture.captureFailure.set(new IllegalStateException("Capture failed"));
            CompletableFuture<RankingSnapshot> failed = fixture.cache.ranking(StatKey.VOTES);
            fixture.runNext();
            assertTrue(failed.isCompletedExceptionally());
            assertEquals(1, fixture.failures.size());
            fixture.captureFailure.set(null);
            for (int request = 0; request < 100; request++) {
                assertTrue(fixture.cache.ranking(StatKey.VOTES).isCompletedExceptionally());
                assertTrue(fixture.cache.cachedRanking(StatKey.VOTES).isEmpty());
            }
            fixture.clock.advance(4_999);
            assertTrue(fixture.cache.ranking(StatKey.VOTES).isCompletedExceptionally());
            assertTrue(fixture.queued.isEmpty());
            assertEquals(1, fixture.captures.get());
            assertEquals(1, fixture.failures.size());
            fixture.clock.advance(1);
            CompletableFuture<RankingSnapshot> retried = fixture.cache.ranking(StatKey.VOTES);
            fixture.runNext();
            assertTrue(retried.join().entries().isEmpty());
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void failedRefreshKeepsThePreviousRankingReadableDuringBackoff() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(profile(1, "River", 7)));
            CompletableFuture<RankingSnapshot> initial = fixture.cache.ranking(StatKey.BLOCKS_BROKEN);
            fixture.runNext();
            fixture.clock.advance(5_000);
            fixture.captureFailure.set(new IllegalStateException("Capture failed"));
            CompletableFuture<RankingSnapshot> refresh = fixture.cache.ranking(StatKey.BLOCKS_BROKEN);
            fixture.runNext();
            assertTrue(refresh.isCompletedExceptionally());
            assertSame(initial.join(), fixture.cache.cachedRanking(StatKey.BLOCKS_BROKEN).orElseThrow());
            assertTrue(fixture.cache.ranking(StatKey.BLOCKS_BROKEN).isCompletedExceptionally());
            assertTrue(fixture.queued.isEmpty());
        }
    }

    @Test
    void blockedCaptureRunsOnDedicatedWorkerAndDoesNotBlockReadsOrShutdown() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> captureThread = new AtomicReference<>();
        RankingCache cache = RankingCache.create(window -> {
            captureThread.set(Thread.currentThread());
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return List.of(profile(1, "River", 7));
        }, () -> ZoneOffset.UTC, RankingRules::defaults, Logger.getLogger("RankingCacheTest"));
        try {
            CompletableFuture<RankingSnapshot> pending = cache.ranking(StatKey.VOTES);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), captureThread.get());
            assertEquals("Static-Rankings", captureThread.get().getName());
            assertTrue(cache.cachedRanking(StatKey.VOTES).isEmpty());
            assertTrue(cache.cachedRanking(StatKey.BLOCKS_BROKEN).isEmpty());
            cache.close();
            assertTrue(pending.isCompletedExceptionally());
            assertTrue(cache.cachedRanking(StatKey.VOTES).isEmpty());
            captureThread.get().join(5_000);
            assertFalse(captureThread.get().isAlive());
        } finally {
            release.countDown();
            cache.close();
        }
    }

    @Test
    void ruleChangesHideOldSnapshotsImmediatelyAndRebuildUsingTheCapturedProfiles() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(ratioProfile(10)));
            CompletableFuture<RankingSnapshot> initial = fixture.cache.ranking(StatKey.KILL_DEATH_RATIO);
            fixture.runNext();
            assertEquals(1, initial.join().entries().size());
            fixture.rules.set(rules(20));
            assertTrue(fixture.cache.cachedRanking(StatKey.KILL_DEATH_RATIO).isEmpty());
            CompletableFuture<RankingSnapshot> changed = fixture.cache.ranking(StatKey.KILL_DEATH_RATIO);
            assertFalse(changed.isDone());
            assertEquals(1, fixture.queued.size());
            fixture.runNext();
            assertTrue(changed.join().entries().isEmpty());
            assertEquals(1, changed.join().excludedCount());
            assertEquals(20, changed.join().qualification(new UUID(0, 1)).orElseThrow().minimum());
            assertEquals(fixture.rules.get(), changed.join().rules());
            assertEquals(1, fixture.captures.get());
            assertEquals(1, initial.join().entries().size());
        }
    }

    @Test
    void repeatedRuleChangesCoalesceIntoOneQueuedRefreshWithTheLatestPolicy() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(ratioProfile(10)));
            CompletableFuture<RankingSnapshot> initial = fixture.cache.ranking(StatKey.KILL_DEATH_RATIO);
            List<CompletableFuture<RankingSnapshot>> waiters = new ArrayList<>();
            for (int minimum = 1; minimum <= 100; minimum++) {
                fixture.rules.set(rules(minimum));
                assertTrue(fixture.cache.cachedRanking(StatKey.KILL_DEATH_RATIO).isEmpty());
                waiters.add(fixture.cache.ranking(StatKey.KILL_DEATH_RATIO));
            }
            assertEquals(1, fixture.queued.size());
            fixture.runNext();
            RankingSnapshot current = initial.join();
            assertEquals(100, current.qualification(new UUID(0, 1)).orElseThrow().minimum());
            assertTrue(current.entries().isEmpty());
            for (CompletableFuture<RankingSnapshot> waiter : waiters) {
                assertSame(current, waiter.join());
            }
            assertEquals(1, fixture.captures.get());
        }
    }

    @Test
    void ruleChangeDuringCaptureDiscardsTheOldResultAndKeepsItsWaitersUntilRebuilt() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(ratioProfile(10)));
            fixture.captureAction.set(() -> fixture.rules.set(rules(20)));
            CompletableFuture<RankingSnapshot> pending = fixture.cache.ranking(StatKey.KILL_DEATH_RATIO);
            fixture.runNext();
            assertFalse(pending.isDone());
            assertTrue(fixture.cache.cachedRanking(StatKey.KILL_DEATH_RATIO).isEmpty());
            assertEquals(1, fixture.queued.size());
            fixture.runNext();
            assertTrue(pending.join().entries().isEmpty());
            assertEquals(fixture.rules.get(), pending.join().rules());
            assertEquals(20, pending.join().qualification(new UUID(0, 1)).orElseThrow().minimum());
            assertEquals(1, fixture.captures.get());
            assertTrue(fixture.queued.isEmpty());
        }
    }

    @Test
    void shutdownCompletesWaitersWhileAnObsoletePolicyRebuildIsQueued() {
        try (Fixture fixture = new Fixture()) {
            fixture.captureAction.set(() -> fixture.rules.set(rules(20)));
            CompletableFuture<RankingSnapshot> pending = fixture.cache.ranking(StatKey.KILL_DEATH_RATIO);
            fixture.runNext();
            assertFalse(pending.isDone());
            fixture.cache.close();
            assertTrue(pending.isCompletedExceptionally());
            fixture.runNext();
            assertTrue(fixture.cache.cachedRanking(StatKey.KILL_DEATH_RATIO).isEmpty());
            assertTrue(fixture.queued.isEmpty());
        }
    }

    @Test
    void calendarBoundaryHidesOldRankingEvenBeforeItsFiveSecondTtlExpires() {
        try (Fixture fixture = new Fixture()) {
            fixture.clock.current.set(Instant.parse("2026-09-27T23:59:59Z").toEpochMilli());
            fixture.profiles.set(List.of(profile(1, "River", 7)));
            CompletableFuture<RankingSnapshot> previous = fixture.cache.ranking(StatKey.BLOCKS_BROKEN, StatsPeriod.DAY);
            fixture.runNext();
            fixture.clock.advance(1_000);
            fixture.profiles.set(List.of(profile(1, "River", 0)));
            assertTrue(fixture.cache.cachedRanking(StatKey.BLOCKS_BROKEN, StatsPeriod.DAY).isEmpty());
            CompletableFuture<RankingSnapshot> current = fixture.cache.ranking(StatKey.BLOCKS_BROKEN, StatsPeriod.DAY);
            fixture.runNext();
            assertEquals("2026-09-27", previous.join().window().id());
            assertEquals("2026-09-28", current.join().window().id());
            assertEquals(0, current.join().entries().getFirst().value());
            assertEquals(ZoneOffset.UTC, current.join().historyZone());
            assertEquals(StatsPeriod.DAY, current.join().period());
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void boundaryDuringCaptureDiscardsPreviousWindowAndRetainsWaitingCallers() {
        try (Fixture fixture = new Fixture()) {
            fixture.clock.current.set(Instant.parse("2026-09-27T23:59:59Z").toEpochMilli());
            fixture.captureAction.set(() -> fixture.clock.advance(1_000));
            CompletableFuture<RankingSnapshot> waiting = fixture.cache.ranking(StatKey.VOTES, StatsPeriod.DAY);
            fixture.runNext();
            assertFalse(waiting.isDone());
            assertTrue(fixture.cache.cachedRanking(StatKey.VOTES, StatsPeriod.DAY).isEmpty());
            assertEquals(1, fixture.queued.size());
            fixture.runNext();
            assertEquals("2026-09-28", waiting.join().window().id());
            assertEquals(2, fixture.captures.get());
        }
    }

    @Test
    void selectedPeriodsUseSeparateProfileCapturesAndQualificationDenominators() {
        try (Fixture fixture = new Fixture()) {
            fixture.profiles.set(List.of(ratioProfile(10)));
            CompletableFuture<RankingSnapshot> lifetime = fixture.cache.ranking(StatKey.KILL_DEATH_RATIO);
            fixture.runNext();
            fixture.profiles.set(List.of(ratioProfile(1)));
            CompletableFuture<RankingSnapshot> day = fixture.cache.ranking(StatKey.KILL_DEATH_RATIO, StatsPeriod.DAY);
            fixture.runNext();
            assertEquals(1, lifetime.join().entries().size());
            assertTrue(day.join().entries().isEmpty());
            assertEquals(1, day.join().qualification(new UUID(0, 1)).orElseThrow().denominator());
            assertEquals(2, fixture.captures.get());
            assertSame(lifetime.join(), fixture.cache.cachedRanking(StatKey.KILL_DEATH_RATIO).orElseThrow());
        }
    }

    private static RankingRules rules(double minimum) {
        EnumMap<StatKey, Double> minimums = new EnumMap<>(RankingRules.defaults().minimums());
        minimums.put(StatKey.KILL_DEATH_RATIO, minimum);
        return new RankingRules(minimums);
    }

    private static PlayerStatsSnapshot ratioProfile(double deaths) {
        return new PlayerStatsSnapshot(new UUID(0, 1), "River", Map.of(StatKey.PVP_KILLS, 20D, StatKey.PVP_DEATHS, deaths), 1_000, 1_000);
    }

    private static PlayerStatsSnapshot profile(long id, String name, double blocks) {
        return new PlayerStatsSnapshot(new UUID(0, id), name, Map.of(StatKey.BLOCKS_BROKEN, blocks), 1_000, 1_000);
    }

    private static final class Fixture implements AutoCloseable {
        private final ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
        private final Queue<Runnable> queued = new ArrayDeque<>();
        private final AtomicReference<Runnable> sweep = new AtomicReference<>();
        private final AtomicReference<List<PlayerStatsSnapshot>> profiles = new AtomicReference<>(List.of());
        private final AtomicReference<RankingRules> rules = new AtomicReference<>(RankingRules.defaults());
        private final AtomicReference<RuntimeException> captureFailure = new AtomicReference<>();
        private final AtomicReference<Runnable> captureAction = new AtomicReference<>(() -> { });
        private final AtomicInteger captures = new AtomicInteger();
        private final List<RuntimeException> failures = new ArrayList<>();
        private final MutableClock clock = new MutableClock();
        private final RankingCache cache;

        private Fixture() {
            doAnswer(invocation -> {
                queued.add(invocation.getArgument(0));
                return null;
            }).when(worker).execute(any(Runnable.class));
            doAnswer(invocation -> {
                sweep.set(invocation.getArgument(0));
                return null;
            }).when(worker).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MINUTES));
            cache = new RankingCache(new RankingCache.Dependencies(window -> capture(), () -> ZoneOffset.UTC,
                    rules::get, clock, worker, failures::add));
        }

        @Override
        public void close() {
            cache.close();
        }

        private List<PlayerStatsSnapshot> capture() {
            captures.incrementAndGet();
            captureAction.getAndSet(() -> { }).run();
            RuntimeException failure = captureFailure.get();
            if (failure != null) {
                throw failure;
            }
            return profiles.get();
        }

        private void runNext() {
            queued.remove().run();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong current = new AtomicLong(1_000_000);

        private void advance(long millis) {
            current.addAndGet(millis);
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
            return Instant.ofEpochMilli(current.get());
        }

        @Override
        public long millis() {
            return current.get();
        }
    }
}
