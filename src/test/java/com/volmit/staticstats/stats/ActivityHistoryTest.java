package com.volmit.staticstats.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityHistoryTest {
    private static final UUID PLAYER = new UUID(0, 1);

    @TempDir
    Path directory;

    @Test
    void splitsAfkAtMidnightWithoutDoubleCountingSnapshotsSaveOrQuit() throws IOException {
        MutableClock clock = new MutableClock("2026-09-27T23:59:59Z");
        StatsStore store = store(clock, new HistorySettings(ZoneOffset.UTC, 1_000, 35, 12, 12, 20));
        store.join(PLAYER, "River");
        clock.advance(2_000);
        PlayerStatsSnapshot lifetime = store.snapshot(PLAYER).orElseThrow();
        assertTimes(lifetime, 2_000, 1_000, 1_000);
        List<PeriodStatsSnapshot> days = store.history(PLAYER, StatsPeriod.DAY);
        assertEquals(List.of("2026-09-28", "2026-09-27"), days.stream().map(value -> value.window().id()).toList());
        assertTimes(days.get(0).profile(), 1_000, 0, 1_000);
        assertTimes(days.get(1).profile(), 1_000, 1_000, 0);
        assertEquals(Instant.parse("2026-09-28T00:00:00Z").toEpochMilli(), days.getFirst().recordedSince());
        store.save();
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 2_000, 1_000, 1_000);
        store.quit(PLAYER);
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 2_000, 1_000, 1_000);
        PlayerSession session = store.sessions(PLAYER).getFirst();
        assertEquals(2_000, session.eligibleMillis());
        assertEquals(session.eligibleMillis(), session.activeMillis() + session.afkMillis());
        assertEquals(SessionEndReason.QUIT, session.endReason());
        assertEquals(clock.millis(), session.endedAt());
    }

    @Test
    void activityResumesActiveTimeAndIneligibleIntervalsNeverAccrue() {
        MutableClock clock = new MutableClock("2026-09-27T12:00:00Z");
        StatsStore store = store(clock, settings(5_000));
        store.join(PLAYER, "River");
        clock.advance(8_000);
        store.activity(PLAYER);
        clock.advance(2_000);
        store.setTracking(PLAYER, false);
        clock.advance(90_000);
        store.activity(PLAYER);
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 10_000, 7_000, 3_000);
        store.setTracking(PLAYER, true);
        clock.advance(3_000);
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 13_000, 10_000, 3_000);
        assertEquals(1, store.sessions(PLAYER).size());
    }

    @Test
    void changingAfkThresholdCheckpointsTheOldClassificationFirst() {
        MutableClock clock = new MutableClock("2026-09-27T12:00:00Z");
        StatsStore store = store(clock, settings(5_000));
        store.join(PLAYER, "River");
        clock.advance(10_000);
        store.configureHistory(settings(300_000));
        clock.advance(5_000);
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 15_000, 10_000, 5_000);
    }

    @Test
    void backwardsClockDoesNotSubtractOrRecountElapsedTime() {
        MutableClock clock = new MutableClock("2026-09-27T12:00:00Z");
        StatsStore store = store(clock, settings(5_000));
        store.join(PLAYER, "River");
        clock.advance(8_000);
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 8_000, 5_000, 3_000);
        clock.advance(-6_000);
        store.activity(PLAYER);
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 8_000, 5_000, 3_000);
        clock.advance(8_000);
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 10_000, 7_000, 3_000);
    }

    @Test
    void calendarWindowsUseIsoWeeksAndRealDstDayLengths() {
        ZoneId denver = ZoneId.of("America/Denver");
        PeriodWindow spring = StatsPeriod.DAY.window(Instant.parse("2026-03-08T12:00:00Z").toEpochMilli(), denver);
        PeriodWindow autumn = StatsPeriod.DAY.window(Instant.parse("2026-11-01T12:00:00Z").toEpochMilli(), denver);
        assertEquals(23 * 3_600_000L, spring.endsAt() - spring.startedAt());
        assertEquals(25 * 3_600_000L, autumn.endsAt() - autumn.startedAt());
        assertEquals("2020-W53", StatsPeriod.WEEK.window(Instant.parse("2021-01-01T12:00:00Z").toEpochMilli(), ZoneOffset.UTC).id());
        assertEquals("2021-W01", StatsPeriod.WEEK.window(Instant.parse("2021-01-04T00:00:00Z").toEpochMilli(), ZoneOffset.UTC).id());
        for (PeriodWindow window : List.of(spring, autumn)) {
            MutableClock clock = new MutableClock(Instant.ofEpochMilli(window.startedAt()).toString());
            StatsStore store = store(clock, new HistorySettings(denver, Long.MAX_VALUE, 35, 12, 12, 20));
            store.join(PLAYER, "River");
            clock.advance(window.endsAt() - window.startedAt());
            List<PeriodStatsSnapshot> days = store.history(PLAYER, StatsPeriod.DAY);
            assertEquals(1, days.size());
            assertTimes(days.getFirst().profile(), window.endsAt() - window.startedAt(), window.endsAt() - window.startedAt(), 0);
            assertEquals(0, store.periodSnapshot(PLAYER, StatsPeriod.DAY).orElseThrow().recordedSince());
        }
    }

    @Test
    void persistsInterruptedSessionsAtTheirSavedCheckpointWithoutDowntime() throws IOException {
        MutableClock clock = new MutableClock("2026-09-27T12:00:00Z");
        StatsStore original = store(clock, settings(5_000));
        original.join(PLAYER, "River");
        clock.advance(9_000);
        original.save();
        long savedAt = clock.millis();
        clock.advance(86_400_000);
        StatsStore restored = store(clock, settings(5_000));
        restored.load();
        assertTimes(restored.snapshot(PLAYER).orElseThrow(), 9_000, 5_000, 4_000);
        PlayerSession interrupted = restored.sessions(PLAYER).getFirst();
        assertEquals(SessionEndReason.INTERRUPTED, interrupted.endReason());
        assertEquals(savedAt, interrupted.endedAt());
        assertEquals(savedAt, interrupted.checkpointAt());
        restored.join(PLAYER, "River");
        clock.advance(1_000);
        restored.disconnectAll();
        restored.save();
        assertTimes(restored.snapshot(PLAYER).orElseThrow(), 10_000, 6_000, 4_000);
        assertEquals(List.of(SessionEndReason.SHUTDOWN, SessionEndReason.INTERRUPTED), restored.sessions(PLAYER).stream().map(PlayerSession::endReason).toList());
        StatsStore twiceRestored = store(clock, settings(5_000));
        twiceRestored.load();
        assertEquals(restored.sessions(PLAYER), twiceRestored.sessions(PLAYER));
        assertEquals(restored.history(PLAYER, StatsPeriod.DAY), twiceRestored.history(PLAYER, StatsPeriod.DAY));
    }

    @Test
    void boundsRetainedWindowsAndSessionsWhileKeepingLifetimeTotals() {
        MutableClock clock = new MutableClock("2026-01-01T12:00:00Z");
        StatsStore store = store(clock, new HistorySettings(ZoneOffset.UTC, 5_000, 2, 2, 2, 2));
        for (int day = 0; day < 100; day++) {
            store.join(PLAYER, "River");
            store.add(PLAYER, StatKey.BLOCKS_BROKEN, 1);
            store.quit(PLAYER);
            if (day < 99) {
                clock.advance(86_400_000);
            }
        }
        assertEquals(100, store.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
        for (StatsPeriod period : List.of(StatsPeriod.DAY, StatsPeriod.WEEK, StatsPeriod.MONTH)) {
            assertEquals(2, store.history(PLAYER, period).size());
        }
        assertEquals(2, store.sessions(PLAYER).size());
        store.configureHistory(new HistorySettings(ZoneOffset.UTC, 5_000, 1, 1, 1, 1));
        assertEquals(1, store.history(PLAYER, StatsPeriod.DAY).size());
        assertEquals(1, store.sessions(PLAYER).size());
    }

    @Test
    void preservesExistingLifetimeDataAndUnknownCoverageWithoutInventingHistory() throws IOException {
        MutableClock clock = new MutableClock("2026-09-27T12:00:00Z");
        Files.writeString(directory.resolve("players.json"), """
                {"version":1,"players":[{"uuid":"00000000-0000-0000-0000-000000000001","name":"River","updated-at":1000,
                "values":{"online_time":500000,"blocks_broken":23}}]}
                """);
        StatsStore store = store(clock, settings(5_000));
        store.load();
        assertEquals(0, store.snapshot(PLAYER).orElseThrow().recordedSince());
        assertEquals(0, store.activityRecordedSince(PLAYER));
        assertTrue(store.history(PLAYER, StatsPeriod.DAY).isEmpty());
        assertTrue(store.sessions(PLAYER).isEmpty());
        assertEquals(0, store.snapshot(PLAYER, StatsPeriod.DAY).orElseThrow().recordedSince());
        store.join(PLAYER, "River");
        long firstObservation = clock.millis();
        clock.advance(2_000);
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 502_000, 2_000, 0);
        assertEquals(firstObservation, store.activityRecordedSince(PLAYER));
        assertEquals(0, store.snapshot(PLAYER).orElseThrow().recordedSince());
        assertEquals(firstObservation, store.snapshot(PLAYER, StatsPeriod.DAY).orElseThrow().recordedSince());
        assertEquals(0, store.snapshot(PLAYER, StatsPeriod.DAY).orElseThrow().value(StatKey.BLOCKS_BROKEN));
    }

    @Test
    void externalDurationIncrementsDoNotInventSessionElapsedTime() {
        MutableClock clock = new MutableClock("2026-09-27T12:00:00Z");
        StatsStore store = store(clock, settings(5_000));
        store.add(PLAYER, StatKey.ONLINE_TIME, 9_000);
        store.add(PLAYER, StatKey.ACTIVE_TIME, 123);
        store.add(PLAYER, StatKey.AFK_TIME, 456);
        assertTimes(store.snapshot(PLAYER).orElseThrow(), 9_000, 123, 456);
        assertTimes(store.snapshot(PLAYER, StatsPeriod.DAY).orElseThrow(), 9_000, 123, 456);
        assertTrue(store.sessions(PLAYER).isEmpty());
    }

    @Test
    void offlineEventsAndRatioComponentsBelongToTheirOwnDayWeekAndMonth() {
        MutableClock clock = new MutableClock("2026-05-31T23:59:59Z");
        StatsStore store = store(clock, settings(5_000));
        store.add(PLAYER, StatKey.PVP_KILLS, 30);
        store.add(PLAYER, StatKey.PVP_DEATHS, 10);
        clock.advance(1_000);
        store.add(PLAYER, StatKey.PVP_KILLS, 2);
        store.add(PLAYER, StatKey.PVP_DEATHS, 1);
        store.add(PLAYER, StatKey.VOTES, 3);
        for (StatsPeriod period : List.of(StatsPeriod.DAY, StatsPeriod.WEEK, StatsPeriod.MONTH)) {
            List<PeriodStatsSnapshot> retained = store.history(PLAYER, period);
            assertEquals(2, retained.size());
            PlayerStatsSnapshot current = store.snapshot(PLAYER, period).orElseThrow();
            assertEquals(2, current.value(StatKey.KILL_DEATH_RATIO));
            assertEquals(1, current.value(StatKey.PVP_DEATHS));
            assertEquals(3, current.value(StatKey.VOTES));
            assertEquals(3, retained.get(1).profile().value(StatKey.KILL_DEATH_RATIO));
            assertEquals(0, retained.get(1).profile().value(StatKey.VOTES));
            RankingSnapshot ranking = RankingSnapshot.create(StatKey.KILL_DEATH_RATIO, clock.millis(), List.of(current),
                    RankingRules.defaults(), period.window(clock.millis(), ZoneOffset.UTC), ZoneOffset.UTC);
            assertTrue(ranking.entries().isEmpty());
            assertEquals(1, ranking.qualification(PLAYER).orElseThrow().denominator());
        }
        assertEquals(32D / 11D, store.snapshot(PLAYER).orElseThrow().value(StatKey.KILL_DEATH_RATIO));
        assertTrue(store.sessions(PLAYER).isEmpty());
    }

    @Test
    void historyZoneCannotChangeAfterObservationOrAcrossRestart() throws IOException {
        MutableClock clock = new MutableClock("2026-09-27T12:00:00Z");
        StatsStore store = store(clock, settings(5_000));
        HistorySettings denver = new HistorySettings(ZoneId.of("America/Denver"), 5_000, 35, 12, 12, 20);
        store.validateHistorySettings(denver);
        store.join(PLAYER, "River");
        assertThrows(IllegalArgumentException.class, () -> store.configureHistory(denver));
        store.save();
        StatsStore restored = store(clock, denver);
        assertThrows(IOException.class, restored::load);
    }

    @Test
    void invalidPersistedZoneFailsAsStorageIoError() throws IOException {
        Files.writeString(directory.resolve("players.json"), "{\"version\":1,\"history-zone\":\"Invalid/Zone\",\"players\":[]}");
        StatsStore store = store(new MutableClock("2026-09-27T12:00:00Z"), settings(5_000));
        assertThrows(IOException.class, store::load);
    }

    private StatsStore store(MutableClock clock, HistorySettings settings) {
        StatsStore store = new StatsStore(directory.resolve("players.json"), clock);
        store.configureHistory(settings);
        return store;
    }

    private static HistorySettings settings(long afkMillis) {
        return new HistorySettings(ZoneOffset.UTC, afkMillis, 35, 12, 12, 20);
    }

    private static void assertTimes(PlayerStatsSnapshot snapshot, long online, long active, long afk) {
        assertEquals(online, snapshot.value(StatKey.ONLINE_TIME));
        assertEquals(active, snapshot.value(StatKey.ACTIVE_TIME));
        assertEquals(afk, snapshot.value(StatKey.AFK_TIME));
    }

    private static final class MutableClock extends Clock {
        private long now;

        private MutableClock(String instant) {
            now = Instant.parse(instant).toEpochMilli();
        }

        private void advance(long millis) {
            now += millis;
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
            return Instant.ofEpochMilli(now);
        }

        @Override
        public long millis() {
            return now;
        }
    }
}
