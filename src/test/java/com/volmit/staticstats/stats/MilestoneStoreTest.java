package com.volmit.staticstats.stats;

import com.volmit.staticstats.milestone.MilestoneCompletion;
import com.volmit.staticstats.milestone.MilestoneCompletionEvent;
import com.volmit.staticstats.milestone.MilestoneSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneStoreTest {
    private static final UUID PLAYER = new UUID(0, 1);

    @TempDir
    Path directory;

    @Test
    void exactBoundaryAndMultiTierLeapEarnOnceAndNotifyOnlyAfterSave() throws IOException {
        MutableClock clock = new MutableClock();
        StatsStore store = store(clock, settings(true, 10D, 20D, 100D));
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 9);
        assertTrue(store.milestoneCompletions(PLAYER).isEmpty());
        assertFalse(store.hasPendingMilestoneWrites());
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 1);
        MilestoneCompletion first = store.milestoneCompletions(PLAYER).getFirst();
        assertEquals(10, first.definition().threshold());
        assertEquals(clock.millis(), first.completedAt());
        assertFalse(first.historical());
        assertTrue(store.hasPendingMilestoneWrites());
        assertFalse(Files.exists(store.file()));
        List<MilestoneCompletionEvent> initial = store.save();
        assertEquals(1, initial.size());
        assertTrue(initial.getFirst().sessionId().isEmpty());
        assertFalse(store.hasPendingMilestoneWrites());
        clock.advance(100);
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 200);
        assertEquals(3, store.milestoneCompletions(PLAYER).size());
        assertEquals(2, store.save().size());
        assertTrue(store.save().isEmpty());
        StatsStore restored = store(clock, settings(true, 10D, 20D, 100D));
        restored.load();
        assertEquals(store.milestoneCompletions(PLAYER), restored.milestoneCompletions(PLAYER));
        assertTrue(restored.save().isEmpty());
    }

    @Test
    void reloadCatchUpIsHistoricalAndRemovedDefinitionsRetainTheirOriginalDate() throws IOException {
        MutableClock clock = new MutableClock();
        StatsStore store = store(clock, settings(false, 10D));
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 25);
        assertTrue(store.milestones(PLAYER).isEmpty());
        clock.advance(1_000);
        store.configureMilestones(settings(true, 10D, 20D));
        assertEquals(2, store.milestoneCompletions(PLAYER).size());
        assertTrue(store.milestoneCompletions(PLAYER).stream().allMatch(MilestoneCompletion::historical));
        long observedAt = clock.millis();
        assertTrue(store.save().isEmpty());
        store.configureMilestones(settings(true, 30D));
        assertEquals(1, store.milestones(PLAYER).size());
        assertEquals(2, store.milestoneCompletions(PLAYER).size());
        clock.advance(1_000);
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 5);
        assertEquals(1, store.save().size());
        store.configureMilestones(settings(true, 10D, 20D, 30D));
        assertEquals(3, store.milestones(PLAYER).size());
        assertEquals(observedAt, store.milestones(PLAYER).getFirst().completion().orElseThrow().completedAt());
        assertTrue(store.save().isEmpty());
        store.configureMilestones(settings(false, 10D, 20D, 30D));
        assertTrue(store.milestones(PLAYER).isEmpty());
        assertEquals(3, store.milestoneCompletions(PLAYER).size());
    }

    @Test
    void existingStatsCatchUpOnLoadWithoutInventingAnOriginalAchievementDate() throws IOException {
        MutableClock clock = new MutableClock();
        Files.writeString(directory.resolve("players.json"), """
                {"version":1,"players":[{"uuid":"00000000-0000-0000-0000-000000000001","name":"River",
                "updated-at":100,"values":{"blocks_broken":250}}]}
                """);
        StatsStore store = store(clock, settings(true, 100D));
        store.load();
        MilestoneCompletion completion = store.milestoneCompletions(PLAYER).getFirst();
        assertTrue(completion.historical());
        assertEquals(clock.millis(), completion.completedAt());
        assertEquals(250, completion.observedValue());
        assertEquals(0, store.snapshot(PLAYER).orElseThrow().recordedSince());
        assertTrue(store.save().isEmpty());
    }

    @Test
    void passiveTimeCheckpointsUseTheSameMilestonePathAndCurrentSessionIdentity() throws IOException {
        MutableClock clock = new MutableClock();
        StatsStore store = store(clock, new MilestoneSettings(true, Map.of(
                StatKey.ONLINE_TIME, List.of(2_000D), StatKey.ACTIVE_TIME, List.of(1_000D), StatKey.AFK_TIME, List.of(1_000D))));
        store.configureHistory(new HistorySettings(ZoneOffset.UTC, 1_000, 35, 12, 12, 20));
        store.join(PLAYER, "River");
        UUID originalSession = store.sessions(PLAYER).getFirst().id();
        clock.advance(2_000);
        store.checkpoint(PLAYER);
        assertEquals(3, store.milestoneCompletions(PLAYER).size());
        store.quit(PLAYER);
        store.join(PLAYER, "River");
        UUID currentSession = store.sessions(PLAYER).getFirst().id();
        assertFalse(currentSession.equals(originalSession));
        List<MilestoneCompletionEvent> events = store.save();
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(event -> event.sessionId().orElseThrow().equals(originalSession)));
        assertTrue(store.save().isEmpty());
    }

    @Test
    void failedAtomicWriteKeepsPendingCompletionsForTheNextSuccessfulSave() throws IOException {
        MutableClock clock = new MutableClock();
        StatsStore store = store(clock, settings(true, 10D));
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 10);
        Files.createDirectory(store.file());
        Files.writeString(store.file().resolve("occupied"), "keep");
        assertThrows(IOException.class, store::save);
        assertTrue(store.hasPendingMilestoneWrites());
        assertEquals(1, store.milestoneCompletions(PLAYER).size());
        Files.delete(store.file().resolve("occupied"));
        Files.delete(store.file());
        assertEquals(1, store.save().size());
        assertFalse(store.hasPendingMilestoneWrites());
        assertTrue(store.save().isEmpty());
    }

    @Test
    void concurrentIncrementsCannotDuplicateCompletionRecordsOrEvents() throws Exception {
        StatsStore store = store(new MutableClock(), settings(true, 500D, 1_000D, 5_000D));
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> updates = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                updates.add(workers.submit(() -> {
                    for (int increment = 0; increment < 1_000; increment++) {
                        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 1);
                    }
                }));
            }
            for (Future<?> update : updates) {
                update.get(10, TimeUnit.SECONDS);
            }
            assertEquals(8_000, store.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
            assertEquals(3, store.milestoneCompletions(PLAYER).size());
            assertEquals(3, store.save().size());
            assertTrue(store.save().isEmpty());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void durableRegistryBoundsRepeatedDefinitionEditsWithoutDeletingEarnedRecords() throws IOException {
        MutableClock clock = new MutableClock();
        StatsStore store = store(clock, new MilestoneSettings(true, Map.of()));
        for (int batch = 0; batch < 256; batch++) {
            List<Double> thresholds = new ArrayList<>();
            for (int index = 1; index <= 16; index++) {
                thresholds.add((double) (batch * 16 + index));
            }
            store.configureMilestones(new MilestoneSettings(true, Map.of(StatKey.BLOCKS_BROKEN, thresholds)));
        }
        store.add(PLAYER, StatKey.BLOCKS_BROKEN, 5_000);
        assertEquals(16, store.milestoneCompletions(PLAYER).size());
        assertThrows(IllegalArgumentException.class, () -> store.validateMilestoneSettings(settings(true, 4_097D)));
        store.save();
        StatsStore restored = store(clock, new MilestoneSettings(true, Map.of()));
        restored.load();
        assertEquals(store.milestoneCompletions(PLAYER), restored.milestoneCompletions(PLAYER));
        assertThrows(IllegalArgumentException.class, () -> restored.configureMilestones(settings(true, 4_097D)));
        restored.configureMilestones(settings(true, 1D));
        assertEquals(17, restored.milestoneCompletions(PLAYER).size());
    }

    private StatsStore store(MutableClock clock, MilestoneSettings settings) {
        StatsStore store = new StatsStore(directory.resolve("players.json"), clock);
        store.configureMilestones(settings);
        return store;
    }

    private static MilestoneSettings settings(boolean enabled, Double... thresholds) {
        return new MilestoneSettings(enabled, Map.of(StatKey.BLOCKS_BROKEN, List.of(thresholds)));
    }

    private static final class MutableClock extends Clock {
        private long now = 1_000_000;

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
