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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsStoreTest {
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @TempDir
    Path directory;

    @Test
    void includesLiveSessionAndPersistsWithoutDoubleCounting() throws IOException {
        MutableClock clock = new MutableClock();
        StatsStore store = new StatsStore(directory.resolve("players.json"), clock);
        store.join(PLAYER, "River");
        clock.advance(10_000);
        assertEquals(10_000, store.snapshot(PLAYER).orElseThrow().value(StatKey.ONLINE_TIME));
        store.save();
        clock.advance(5_000);
        store.quit(PLAYER);
        store.quit(PLAYER);
        clock.advance(20_000);
        assertEquals(15_000, store.snapshot(PLAYER).orElseThrow().value(StatKey.ONLINE_TIME));
        assertEquals(1, store.snapshot(PLAYER).orElseThrow().value(StatKey.QUITS));
        store.save();
        StatsStore restored = new StatsStore(directory.resolve("players.json"), clock);
        restored.load();
        assertEquals(15_000, restored.snapshot(PLAYER).orElseThrow().value(StatKey.ONLINE_TIME));
        restored.join(PLAYER, "RiverRenamed");
        clock.advance(2_000);
        assertEquals(17_000, restored.snapshot(PLAYER).orElseThrow().value(StatKey.ONLINE_TIME));
        assertEquals("RiverRenamed", restored.find("riverrENAMED").orElseThrow().name());
        assertFalse(restored.find("River").isPresent());
    }

    @Test
    void shutdownCheckpointsEverySessionWithoutInventingQuits() {
        MutableClock clock = new MutableClock();
        StatsStore store = new StatsStore(directory.resolve("players.json"), clock);
        store.join(PLAYER, "River");
        clock.advance(1_500);
        store.disconnectAll();
        clock.advance(5_000);
        PlayerStatsSnapshot snapshot = store.snapshot(PLAYER).orElseThrow();
        assertEquals(1_500, snapshot.value(StatKey.ONLINE_TIME));
        assertEquals(0, snapshot.value(StatKey.QUITS));
    }

    @Test
    void pausedTrackingExcludesTimeAndResumesTheSameSession() {
        MutableClock clock = new MutableClock();
        StatsStore store = new StatsStore(directory.resolve("players.json"), clock);
        store.join(PLAYER, "River");
        clock.advance(1_000);
        store.setTracking(PLAYER, false);
        clock.advance(5_000);
        assertEquals(1_000, store.snapshot(PLAYER).orElseThrow().value(StatKey.ONLINE_TIME));
        store.setTracking(PLAYER, true);
        clock.advance(3_000);
        store.quit(PLAYER);
        assertEquals(4_000, store.snapshot(PLAYER).orElseThrow().value(StatKey.ONLINE_TIME));
        assertEquals(1, store.snapshot(PLAYER).orElseThrow().value(StatKey.JOINS));
    }

    @Test
    void concurrentRegionAndChatUpdatesDoNotLoseIncrements() throws InterruptedException {
        StatsStore store = new StatsStore(directory.resolve("players.json"), Clock.systemUTC());
        store.join(PLAYER, "River");
        ExecutorService workers = Executors.newFixedThreadPool(6);
        for (int worker = 0; worker < 6; worker++) {
            workers.submit(() -> {
                for (int update = 0; update < 2_000; update++) {
                    store.add(PLAYER, StatKey.CHAT_MESSAGES, 1);
                }
            });
        }
        workers.shutdown();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(12_000, store.snapshot(PLAYER).orElseThrow().value(StatKey.CHAT_MESSAGES));
    }

    @Test
    void snapshotsAreDetachedAndImmutable() {
        StatsStore store = new StatsStore(directory.resolve("players.json"), Clock.systemUTC());
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 3);
        PlayerStatsSnapshot first = store.snapshot(PLAYER).orElseThrow();
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 4);
        assertEquals(3, first.value(StatKey.BLOCKS_BROKEN));
        assertEquals(7, store.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
        assertThrows(UnsupportedOperationException.class, () -> first.values().put(StatKey.BLOCKS_BROKEN, 20D));
    }

    @Test
    void recordingStartSurvivesSaveReconnectAndOfflineIncrements() throws IOException {
        MutableClock clock = new MutableClock();
        Path file = directory.resolve("players.json");
        StatsStore store = new StatsStore(file, clock);
        store.join(PLAYER, "River");
        long startedAt = clock.millis();
        clock.advance(10_000);
        store.quit(PLAYER);
        store.add(PLAYER, StatKey.VOTES, 7);
        store.save();
        StatsStore restored = new StatsStore(file, clock);
        restored.load();
        clock.advance(10_000);
        restored.join(PLAYER, "RiverRenamed");
        assertEquals(startedAt, restored.snapshot(PLAYER).orElseThrow().recordedSince());
        assertEquals(7, restored.snapshot(PLAYER).orElseThrow().value(StatKey.VOTES));
        UUID offline = UUID.randomUUID();
        restored.add(offline, StatKey.VOTES, 1);
        assertEquals(clock.millis(), restored.snapshot(offline).orElseThrow().recordedSince());
    }

    @Test
    void existingProfileWithoutCoverageKeepsUnknownCoverageAndEveryStatistic() throws IOException {
        MutableClock clock = new MutableClock();
        Path file = directory.resolve("players.json");
        Files.writeString(file, """
                {"version":1,"players":[{"uuid":"%s","name":"River","updated-at":900000,
                "values":{"blocks_broken":37,"distance_traveled":12.345,"online_time":5000}}]}
                """.formatted(PLAYER));
        StatsStore store = new StatsStore(file, clock);
        store.load();
        PlayerStatsSnapshot previous = store.snapshot(PLAYER).orElseThrow();
        assertEquals(0, previous.recordedSince());
        store.join(PLAYER, "RiverRenamed");
        clock.advance(1_000);
        store.quit(PLAYER);
        store.save();
        StatsStore restored = new StatsStore(file, clock);
        restored.load();
        PlayerStatsSnapshot current = restored.snapshot(PLAYER).orElseThrow();
        assertEquals(0, current.recordedSince());
        assertEquals(previous.value(StatKey.BLOCKS_BROKEN), current.value(StatKey.BLOCKS_BROKEN));
        assertEquals(previous.value(StatKey.DISTANCE_TRAVELED), current.value(StatKey.DISTANCE_TRAVELED));
        assertEquals(6_000, current.value(StatKey.ONLINE_TIME));
        assertTrue(Files.readString(file).contains("\"recorded-since\": 0"));
    }

    @Test
    void invalidCurrentDataDoesNotReplaceLoadedState() throws IOException {
        Path file = directory.resolve("players.json");
        StatsStore store = new StatsStore(file, Clock.systemUTC());
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 5);
        Files.writeString(file, "{\"version\":1,\"players\":[{\"uuid\":\"bad\"}]}");
        assertThrows(IOException.class, store::load);
        assertEquals(5, store.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
    }

    @Test
    void historicalNameReuseResolvesTheMostRecentlyUpdatedProfile() {
        MutableClock clock = new MutableClock();
        StatsStore store = new StatsStore(directory.resolve("players.json"), clock);
        UUID second = UUID.fromString("20000000-0000-0000-0000-000000000001");
        store.join(PLAYER, "River");
        store.quit(PLAYER);
        clock.advance(1_000);
        store.join(second, "River");
        store.quit(second);
        assertEquals(second, store.find("river").orElseThrow().uuid());
        assertEquals(PLAYER, store.find(PLAYER.toString()).orElseThrow().uuid());
    }

    @Test
    void atomicSaveKeepsFractionalValuesAndLeavesNoTemporaryFiles() throws IOException {
        Path file = directory.resolve("storage").resolve("players.json");
        StatsStore store = new StatsStore(file, Clock.systemUTC());
        store.add(PLAYER, StatKey.DISTANCE_TRAVELED, 2.345);
        store.save();
        store.add(PLAYER, StatKey.DISTANCE_TRAVELED, 0.005);
        store.save();
        StatsStore restored = new StatsStore(file, Clock.systemUTC());
        restored.load();
        assertEquals(2.35, restored.snapshot(PLAYER).orElseThrow().value(StatKey.DISTANCE_TRAVELED), 0.000001);
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void rejectsDerivedNegativeAndNonfiniteMutations() {
        StatsStore store = new StatsStore(directory.resolve("players.json"), Clock.systemUTC());
        assertThrows(IllegalArgumentException.class, () -> store.add(PLAYER, StatKey.ARROW_ACCURACY, 1));
        assertThrows(IllegalArgumentException.class, () -> store.add(PLAYER, StatKey.VOTES, -1));
        assertThrows(IllegalArgumentException.class, () -> store.add(PLAYER, StatKey.VOTES, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> store.add(PLAYER, StatKey.VOTES, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerStatsSnapshot(PLAYER, "River", Map.of(StatKey.VOTES, -1D), 0, 0));
        assertEquals(0, store.playerCount());
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
