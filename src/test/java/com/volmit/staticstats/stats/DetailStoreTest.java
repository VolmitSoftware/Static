package com.volmit.staticstats.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.volmit.staticstats.detailed.DetailCategory;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailSettings;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailStoreTest {
    private static final UUID PLAYER = new UUID(0, 1);

    @TempDir
    Path directory;

    @Test
    void storesExactWholeCountsAndPerKeyObservationDatesWithoutInventingHistory() throws IOException {
        MutableClock clock = new MutableClock();
        Path file = directory.resolve("players.json");
        StatsStore store = new StatsStore(file, clock);
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 500);
        assertTrue(store.details(PLAYER).orElseThrow().values().isEmpty());
        assertEquals(0, store.details(PLAYER).orElseThrow().recordedSince(DetailKey.MINED_DIAMOND_ORE));
        clock.now.set(2_000);
        long largeCount = 9_007_199_254_740_993L;
        store.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, largeCount);
        clock.now.set(3_000);
        store.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 2);
        store.addDetail(PLAYER, DetailKey.CAUGHT_ITEMS, 4);
        clock.now.set(2_500);
        store.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 1);
        assertFalse(Files.exists(file));
        assertEquals(500, store.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
        store.save();
        StatsStore loaded = new StatsStore(file, clock);
        loaded.load();
        DetailSnapshot details = loaded.details(PLAYER).orElseThrow();
        assertEquals(largeCount + 3, details.value(DetailKey.MINED_DIAMOND_ORE));
        assertEquals(2_000, details.recordedSince(DetailKey.MINED_DIAMOND_ORE));
        assertEquals(3_000, details.lastObservedAt(DetailKey.MINED_DIAMOND_ORE));
        assertEquals(3_000, details.recordedSince(DetailKey.CAUGHT_ITEMS));
        assertEquals(3_000, details.updatedAt());
        assertEquals(0, details.value(DetailKey.CAUGHT_COD));
        assertEquals(500, loaded.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
    }

    @Test
    void oldProfilesHaveUnknownDetailCoverageAndSnapshotsRemainDetached() throws IOException {
        Path file = directory.resolve("players.json");
        Files.writeString(file, """
                {"version":1,"players":[{"uuid":"00000000-0000-0000-0000-000000000001","name":"River","updated-at":1000,"values":{"blocks_broken":50}}]}
                """);
        StatsStore store = new StatsStore(file, Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC));
        store.load();
        assertTrue(store.details(new UUID(0, 2)).isEmpty());
        DetailSnapshot before = store.details(PLAYER).orElseThrow();
        assertEquals("River", before.name());
        assertEquals(0, before.recordedSince(DetailKey.MINED_DIAMOND_ORE));
        store.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 1);
        DetailSnapshot after = store.details(PLAYER).orElseThrow();
        assertEquals(0, before.value(DetailKey.MINED_DIAMOND_ORE));
        assertEquals(2_000, after.recordedSince(DetailKey.MINED_DIAMOND_ORE));
        assertThrows(UnsupportedOperationException.class, () -> after.values().put(DetailKey.MINED_DIAMOND_ORE, 2L));
        assertThrows(UnsupportedOperationException.class, () -> after.recordedSince().clear());
        assertThrows(UnsupportedOperationException.class, () -> after.lastObservedAt().clear());
    }

    @Test
    void rejectsNegativeAndOverflowIncrementsWithoutChangingSavedTotals() throws IOException {
        StatsStore store = new StatsStore(directory.resolve("players.json"), Clock.systemUTC());
        store.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 0);
        assertTrue(store.details(PLAYER).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, -1));
        assertTrue(store.details(PLAYER).isEmpty());
        store.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, Long.MAX_VALUE);
        DetailSnapshot before = store.details(PLAYER).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> store.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 1));
        assertEquals(before, store.details(PLAYER).orElseThrow());
        store.save();
        StatsStore loaded = new StatsStore(directory.resolve("players.json"), Clock.systemUTC());
        loaded.load();
        assertEquals(Long.MAX_VALUE, loaded.details(PLAYER).orElseThrow().value(DetailKey.MINED_DIAMOND_ORE));
    }

    @Test
    void concurrentRegionsAndSavesDoNotLoseDetailedCounts() throws Exception {
        StatsStore store = new StatsStore(directory.resolve("players.json"), Clock.systemUTC());
        try (ExecutorService workers = Executors.newFixedThreadPool(5)) {
            List<Future<?>> tasks = List.of(workers.submit(() -> increment(store)), workers.submit(() -> increment(store)),
                    workers.submit(() -> increment(store)), workers.submit(() -> increment(store)),
                    workers.submit(() -> saveRepeatedly(store)));
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        store.save();
        StatsStore loaded = new StatsStore(directory.resolve("players.json"), Clock.systemUTC());
        loaded.load();
        assertEquals(4_000, loaded.details(PLAYER).orElseThrow().value(DetailKey.CRAFTED_ITEMS));
    }

    @Test
    void rankingCapturesAreDetachedAndRemainConsistentDuringConcurrentUpdates() throws Exception {
        StatsStore store = new StatsStore(directory.resolve("players.json"), Clock.systemUTC());
        store.addDetail(PLAYER, DetailKey.CRAFTED_ITEMS, 1);
        List<DetailSnapshot> original = store.detailSnapshots();
        assertThrows(UnsupportedOperationException.class, original::clear);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> increments = workers.submit(() -> increment(store));
            Future<?> captures = workers.submit(() -> inspectCaptures(store));
            increments.get(5, TimeUnit.SECONDS);
            captures.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, original.getFirst().value(DetailKey.CRAFTED_ITEMS));
        DetailRankingSnapshot completed = DetailRankingSnapshot.create(DetailKey.CRAFTED_ITEMS,
                System.currentTimeMillis(), store.detailSnapshots());
        assertEquals(1_001, completed.entries().getFirst().value());
        assertFalse(Files.exists(directory.resolve("players.json")));
    }

    @Test
    void malformedDetailNumbersAndCoverageAreRejectedWithoutReplacingLoadedProfiles() throws IOException {
        Path file = directory.resolve("players.json");
        StatsStore store = new StatsStore(file, Clock.systemUTC());
        store.addDetail(PLAYER, DetailKey.CAUGHT_ITEMS, 3);
        store.save();
        String original = Files.readString(file);
        for (String badValue : List.of("1.5", "9223372036854775808", "-1", "\"4\"", "null")) {
            JsonObject root = JsonParser.parseString(original).getAsJsonObject();
            JsonObject counter = root.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonObject("details").getAsJsonObject("caught_items");
            counter.add("value", JsonParser.parseString(badValue));
            Files.writeString(file, root.toString());
            assertThrows(IOException.class, store::load);
            assertEquals(3, store.details(PLAYER).orElseThrow().value(DetailKey.CAUGHT_ITEMS));
        }
        JsonObject root = JsonParser.parseString(original).getAsJsonObject();
        JsonObject counter = root.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonObject("details").getAsJsonObject("caught_items");
        counter.addProperty("last-observed-at", 1);
        Files.writeString(file, root.toString());
        assertThrows(IOException.class, store::load);
        assertEquals(3, store.details(PLAYER).orElseThrow().value(DetailKey.CAUGHT_ITEMS));
    }

    @Test
    void curatedKeysResolveByIdOrCategoryAndSettingsDetachTheirSelection() {
        for (DetailKey key : DetailKey.values()) {
            assertEquals(key, DetailKey.find(key.id()).orElseThrow());
            assertEquals(key, DetailKey.find(key.category(), key.target()).orElseThrow());
        }
        assertTrue(DetailKey.find("unknown_detail").isEmpty());
        assertTrue(DetailKey.find(DetailCategory.BLOCKS_MINED, "STONE").isEmpty());
        Set<DetailKey> selection = EnumSet.of(DetailKey.MINED_DIAMOND_ORE);
        DetailSettings settings = new DetailSettings(true, selection);
        selection.clear();
        assertTrue(settings.records(DetailKey.MINED_DIAMOND_ORE));
        assertFalse(settings.records(DetailKey.CRAFTED_ITEMS));
        assertFalse(new DetailSettings(false, settings.selectedKeys()).records(DetailKey.MINED_DIAMOND_ORE));
        assertThrows(UnsupportedOperationException.class, () -> settings.selectedKeys().clear());
        assertTrue(DetailSettings.defaults().selectedKeys().size() <= DetailSettings.MAX_SELECTED_KEYS);
    }

    private static void increment(StatsStore store) {
        for (int index = 0; index < 1_000; index++) {
            store.addDetail(PLAYER, DetailKey.CRAFTED_ITEMS, 1);
        }
    }

    private static void inspectCaptures(StatsStore store) {
        long previous = 0;
        for (int index = 0; index < 100; index++) {
            DetailRankingSnapshot ranking = DetailRankingSnapshot.create(DetailKey.CRAFTED_ITEMS,
                    System.currentTimeMillis(), store.detailSnapshots());
            long current = ranking.find(PLAYER).orElseThrow().value();
            assertTrue(current >= previous);
            assertTrue(current <= 1_001);
            previous = current;
        }
    }

    private static void saveRepeatedly(StatsStore store) {
        try {
            for (int index = 0; index < 5; index++) {
                store.save();
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong now = new AtomicLong(1_000);

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
