package com.volmit.staticstats.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.volmit.staticstats.records.PersonalBest;
import com.volmit.staticstats.records.PersonalBestLedger;
import com.volmit.staticstats.records.PersonalBestPeriod;
import com.volmit.staticstats.records.PersonalBestSnapshot;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalBestStoreTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final long DAY = 86_400_000;

    @TempDir
    Path directory;

    @Test
    void epochObservationsAllowCalendarBoundariesBeforeEpochAndPersistThem() throws IOException {
        for (ZoneId zone : List.of(ZoneOffset.UTC, ZoneId.of("America/Denver"), ZoneId.of("Asia/Tokyo"))) {
            MutableClock clock = new MutableClock("1970-01-01T00:00:01Z");
            HistorySettings settings = new HistorySettings(zone, 5_000, 35, 12, 12, 2);
            StatsStore original = store(clock, settings);
            original.join(PLAYER, "River");
            original.add(PLAYER, StatKey.VOTES, 2);
            clock.advance(1_000);
            original.quit(PLAYER);
            original.save();
            StatsStore restored = store(clock, settings);
            restored.load();
            for (StatsPeriod period : List.of(StatsPeriod.DAY, StatsPeriod.WEEK, StatsPeriod.MONTH)) {
                PersonalBest best = best(restored, PersonalBestPeriod.from(period), StatKey.VOTES);
                assertEquals(period.window(1_000, zone).startedAt(), best.startedAt());
                assertEquals(1_000, best.recordedSince());
                assertEquals(1_000, best.observedAt());
                assertEquals(2, best.value());
            }
            assertTrue(best(restored, PersonalBestPeriod.WEEK, StatKey.VOTES).startedAt() < 0);
            assertEquals(1_000, best(restored, PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME).startedAt());
            assertEquals(1_000, best(restored, PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME).value());
        }
    }

    @Test
    void liveCalendarRecordsUseActualPeriodTotalsAndDetachedSnapshots() {
        MutableClock clock = new MutableClock("2026-05-31T23:59:59Z");
        StatsStore store = store(clock, settings(35));
        assertTrue(store.personalBests(PLAYER).isEmpty());
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 0);
        assertEquals(0, store.playerCount());
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 9);
        PersonalBestSnapshot first = store.personalBests(PLAYER).orElseThrow();
        assertEquals(3, first.records().size());
        assertEquals(clock.millis(), first.trackingSince());
        assertThrows(UnsupportedOperationException.class, () -> first.records().clear());
        assertTrue(first.find(PersonalBestPeriod.DAY, StatKey.KILL_DEATH_RATIO).isEmpty());
        clock.advance(1_000);
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 4);
        for (PersonalBestPeriod period : List.of(PersonalBestPeriod.DAY, PersonalBestPeriod.WEEK, PersonalBestPeriod.MONTH)) {
            PersonalBest best = best(store, period, StatKey.BLOCKS_BROKEN);
            assertEquals(9, best.value());
            assertFalse(best.inProgress());
            assertFalse(best.recovered());
            assertEquals(first.find(period, StatKey.BLOCKS_BROKEN).orElseThrow().observedAt(), best.observedAt());
        }
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 6);
        assertEquals(10, best(store, PersonalBestPeriod.DAY, StatKey.BLOCKS_BROKEN).value());
        assertTrue(best(store, PersonalBestPeriod.DAY, StatKey.BLOCKS_BROKEN).inProgress());
        assertEquals(9, first.find(PersonalBestPeriod.DAY, StatKey.BLOCKS_BROKEN).orElseThrow().value());
    }

    @Test
    void tiesKeepEarlierWindowAndFirstAttainmentDateWhileClosedMetadataChanges() {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore store = store(clock, settings(35));
        store.add(PLAYER, StatKey.VOTES, 10);
        PersonalBest original = best(store, PersonalBestPeriod.DAY, StatKey.VOTES);
        clock.advance(DAY);
        store.add(PLAYER, StatKey.VOTES, 10);
        PersonalBest tied = best(store, PersonalBestPeriod.DAY, StatKey.VOTES);
        assertEquals(original.startedAt(), tied.startedAt());
        assertEquals(original.observedAt(), tied.observedAt());
        assertFalse(tied.inProgress());
        clock.advance(-2 * DAY);
        assertFalse(best(store, PersonalBestPeriod.DAY, StatKey.VOTES).inProgress());
        store.add(PLAYER, StatKey.VOTES, 10);
        assertTrue(best(store, PersonalBestPeriod.DAY, StatKey.VOTES).startedAt() < original.startedAt());
    }

    @Test
    void winningRecordsSurviveRetentionChangesAndAtomicSaveRestart() throws IOException {
        MutableClock clock = new MutableClock("2026-01-01T12:00:00Z");
        StatsStore store = store(clock, settings(35));
        store.add(PLAYER, StatKey.DISTANCE_WALKED, 123.5);
        PersonalBestSnapshot original = store.personalBests(PLAYER).orElseThrow();
        clock.advance(400 * DAY);
        store.add(PLAYER, StatKey.DISTANCE_WALKED, 1);
        store.configureHistory(settings(1));
        for (StatsPeriod period : List.of(StatsPeriod.DAY, StatsPeriod.WEEK, StatsPeriod.MONTH)) {
            assertEquals(1, store.history(PLAYER, period).size());
        }
        store.save();
        StatsStore restored = store(clock, settings(1));
        restored.load();
        assertEquals(original.trackingSince(), restored.personalBests(PLAYER).orElseThrow().trackingSince());
        for (PersonalBest record : original.records()) {
            PersonalBest persisted = best(restored, record.period(), record.statistic());
            assertEquals(record.value(), persisted.value());
            assertEquals(record.startedAt(), persisted.startedAt());
            assertEquals(record.observedAt(), persisted.observedAt());
            assertFalse(persisted.inProgress());
        }
    }

    @Test
    void retainedHistoryIsRecoveredBeforeLoadPrunesItWithoutInventingLifetimeHistory() throws IOException {
        MutableClock clock = new MutableClock("2026-01-01T12:00:00Z");
        StatsStore store = store(clock, settings(35));
        store.join(PLAYER, "River");
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 12);
        clock.advance(9_000);
        store.quit(PLAYER);
        store.save();
        JsonObject root = read();
        player(root).getAsJsonObject("history").remove("personal-bests");
        Files.writeString(file(), root.toString());
        clock.advance(400 * DAY);
        StatsStore restored = store(clock, settings(1));
        restored.load();
        PersonalBestSnapshot recovered = restored.personalBests(PLAYER).orElseThrow();
        assertEquals(clock.millis(), recovered.trackingSince());
        assertTrue(restored.history(PLAYER, StatsPeriod.DAY).isEmpty());
        assertEquals(12, best(restored, PersonalBestPeriod.DAY, StatKey.BLOCKS_BROKEN).value());
        assertEquals(9_000, best(restored, PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME).value());
        assertTrue(recovered.records().stream().allMatch(PersonalBest::recovered));
        restored.add(PLAYER, StatKey.BLOCKS_BROKEN, 13);
        assertFalse(best(restored, PersonalBestPeriod.DAY, StatKey.BLOCKS_BROKEN).recovered());
        assertTrue(best(restored, PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME).recovered());
        player(root).remove("history");
        Files.writeString(file(), root.toString());
        StatsStore lifetimeOnly = store(clock, settings(1));
        lifetimeOnly.load();
        assertTrue(lifetimeOnly.personalBests(PLAYER).orElseThrow().records().isEmpty());
    }

    @Test
    void recoveredPlateauRetainsItsProvenanceUntilTheSameWindowImproves() throws IOException {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore original = store(clock, settings(35));
        original.add(PLAYER, StatKey.VOTES, 4);
        original.save();
        JsonObject root = read();
        player(root).getAsJsonObject("history").remove("personal-bests");
        Files.writeString(file(), root.toString());
        clock.advance(1_000);
        StatsStore recovered = store(clock, settings(35));
        recovered.load();
        PersonalBest initial = best(recovered, PersonalBestPeriod.DAY, StatKey.VOTES);
        recovered.add(PLAYER, StatKey.CHAT_MESSAGES, 1);
        assertEquals(initial, best(recovered, PersonalBestPeriod.DAY, StatKey.VOTES));
        recovered.add(PLAYER, StatKey.VOTES, 1);
        PersonalBest improved = best(recovered, PersonalBestPeriod.DAY, StatKey.VOTES);
        assertEquals(5, improved.value());
        assertFalse(improved.recovered());
        assertEquals(clock.millis(), improved.observedAt());
    }

    @Test
    void passiveSessionRecordsFreezeWhileIneligibleAndFinalizeWithoutChangingAttainmentDate() {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore store = store(clock, settings(35));
        store.join(PLAYER, "River");
        clock.advance(9_000);
        PersonalBest active = best(store, PersonalBestPeriod.SESSION, StatKey.ACTIVE_TIME);
        assertEquals(5_000, active.value());
        assertEquals(4_000, best(store, PersonalBestPeriod.SESSION, StatKey.AFK_TIME).value());
        assertTrue(active.inProgress());
        assertEquals(0, active.endsAt());
        store.setTracking(PLAYER, false);
        clock.advance(90_000);
        assertEquals(9_000, best(store, PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME).value());
        store.quit(PLAYER);
        PersonalBest completed = best(store, PersonalBestPeriod.SESSION, StatKey.ACTIVE_TIME);
        assertFalse(completed.inProgress());
        assertEquals(clock.millis(), completed.endsAt());
        assertEquals(active.observedAt(), completed.observedAt());
        store.add(PLAYER, StatKey.ONLINE_TIME, 1_000_000);
        assertEquals(9_000, best(store, PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME).value());
        assertEquals(1_009_000, best(store, PersonalBestPeriod.DAY, StatKey.ONLINE_TIME).value());
    }

    @Test
    void interruptedSessionClosesAtItsSavedCheckpointAndCannotMergeWithAnotherUuid() throws IOException {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore original = store(clock, settings(35));
        original.join(PLAYER, "River");
        clock.advance(8_000);
        original.save();
        long savedAt = clock.millis();
        clock.advance(DAY);
        StatsStore restored = store(clock, settings(35));
        restored.load();
        PersonalBest interrupted = best(restored, PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME);
        assertEquals(8_000, interrupted.value());
        assertEquals(savedAt, interrupted.endsAt());
        assertFalse(interrupted.inProgress());
        assertFalse(interrupted.recovered());
        PersonalBestLedger ledger = new PersonalBestLedger();
        ledger.begin(1_000);
        UUID first = new UUID(0, 1);
        ledger.session(new PlayerSession(first, 1_000, 2_000, 2_000, 1_000, 1_000, 0, SessionEndReason.QUIT), false);
        ledger.session(new PlayerSession(new UUID(0, 2), 1_000, 0, 3_000, 1_000, 1_000, 0, SessionEndReason.OPEN), false);
        PersonalBest sameStart = ledger.snapshot(PLAYER, "River", ZoneOffset.UTC, 3_000)
                .find(PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME).orElseThrow();
        assertEquals(2_000, sameStart.endsAt());
        assertFalse(sameStart.inProgress());
    }

    @Test
    void dstWindowsAndLargeClockJumpsKeepAccurateBestsAndBoundedHistory() {
        ZoneId zone = ZoneId.of("America/Denver");
        MutableClock clock = new MutableClock("2026-11-01T06:00:00Z");
        StatsStore store = store(clock, new HistorySettings(zone, 7_200_000, 1, 1, 1, 1));
        store.join(PLAYER, "River");
        clock.advance(1_000 * DAY);
        PersonalBest day = best(store, PersonalBestPeriod.DAY, StatKey.ONLINE_TIME);
        assertEquals(25 * 3_600_000, day.value());
        assertEquals(25 * 3_600_000, day.endsAt() - day.startedAt());
        assertEquals(7_200_000, best(store, PersonalBestPeriod.DAY, StatKey.ACTIVE_TIME).value());
        assertEquals(1_000 * DAY, best(store, PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME).value());
        assertEquals(1_000 * DAY - 7_200_000, best(store, PersonalBestPeriod.SESSION, StatKey.AFK_TIME).value());
        for (StatsPeriod period : List.of(StatsPeriod.DAY, StatsPeriod.WEEK, StatsPeriod.MONTH)) {
            assertTrue(store.history(PLAYER, period).size() <= 1);
        }
    }

    @Test
    void malformedCurrentRecordsDoNotReplaceAlreadyLoadedProfiles() throws IOException {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore store = store(clock, settings(35));
        store.add(PLAYER, StatKey.VOTES, 5);
        store.save();
        JsonObject root = read();
        JsonArray records = player(root).getAsJsonObject("history").getAsJsonObject("personal-bests").getAsJsonArray("records");
        records.add(records.get(0).deepCopy());
        Files.writeString(file(), root.toString());
        assertThrows(IOException.class, store::load);
        assertEquals(5, best(store, PersonalBestPeriod.DAY, StatKey.VOTES).value());
        records.remove(records.size() - 1);
        records.get(0).getAsJsonObject().addProperty("statistic", "kill_death_ratio");
        Files.writeString(file(), root.toString());
        assertThrows(IOException.class, store::load);
        records.get(0).getAsJsonObject().addProperty("statistic", "votes");
        records.get(0).getAsJsonObject().addProperty("observed-at", 1.5);
        Files.writeString(file(), root.toString());
        assertThrows(IOException.class, store::load);
    }

    @Test
    void concurrentUpdatesAndSavesRetainTheLargestObservedTotals() throws Exception {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore store = store(clock, settings(35));
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> first = executor.submit(() -> addVotes(store));
            Future<?> second = executor.submit(() -> addVotes(store));
            Future<?> save = executor.submit(() -> save(store));
            first.get();
            second.get();
            save.get();
        }
        store.save();
        StatsStore restored = store(clock, settings(35));
        restored.load();
        assertEquals(2_000, best(restored, PersonalBestPeriod.DAY, StatKey.VOTES).value());
    }

    private StatsStore store(MutableClock clock, HistorySettings settings) {
        StatsStore store = new StatsStore(file(), clock);
        store.configureHistory(settings);
        return store;
    }

    private Path file() {
        return directory.resolve("players.json");
    }

    private JsonObject read() throws IOException {
        return JsonParser.parseString(Files.readString(file())).getAsJsonObject();
    }

    private static JsonObject player(JsonObject root) {
        return root.getAsJsonArray("players").get(0).getAsJsonObject();
    }

    private static PersonalBest best(StatsStore store, PersonalBestPeriod period, StatKey key) {
        return store.personalBests(PLAYER).orElseThrow().find(period, key).orElseThrow();
    }

    private static HistorySettings settings(int retained) {
        return new HistorySettings(ZoneOffset.UTC, 5_000, retained, retained, retained, 2);
    }

    private static void addVotes(StatsStore store) {
        for (int index = 0; index < 1_000; index++) {
            store.add(PLAYER, StatKey.VOTES, 1);
        }
    }

    private static void save(StatsStore store) {
        try {
            store.save();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class MutableClock extends Clock {
        private long now;

        private MutableClock(String value) {
            now = Instant.parse(value).toEpochMilli();
        }

        private void advance(long amount) {
            now += amount;
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
