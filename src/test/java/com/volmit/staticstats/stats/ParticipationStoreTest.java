package com.volmit.staticstats.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.volmit.staticstats.participation.ParticipationCapture;
import com.volmit.staticstats.participation.ParticipationQuery;
import com.volmit.staticstats.participation.ParticipationSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticipationStoreTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);
    private static final long DAY = 86_400_000;

    @TempDir
    Path directory;

    @Test
    void apiCountersDoNotCreateParticipantsAndGenuineFirstConnectionDoes() {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore store = store(clock, settings(20));
        store.add(PLAYER, StatKey.JOINS, 50);
        store.add(PLAYER, StatKey.ONLINE_TIME, 1_000_000);
        ParticipationCapture emptyCapture = store.participationCapture();
        ParticipationSnapshot empty = summary(store, StatsPeriod.DAY, 0);
        assertEquals(0, empty.uniquePlayers());
        assertTrue(empty.medianSessionMillis().isEmpty());
        assertEquals(0, empty.earliestObservation());
        assertFalse(empty.retentionLimited());
        store.connect(new StatsStore.Connection(PLAYER, "River", false, false));
        clock.advance(10_000);
        ParticipationSnapshot connected = summary(store, StatsPeriod.DAY, 0);
        assertEquals(1, connected.uniquePlayers());
        assertEquals(1, connected.newPlayers());
        assertEquals(1, connected.openSessions());
        assertEquals(0, connected.completedSessions());
        assertEquals(0, ParticipationSnapshot.create(new ParticipationQuery(StatsPeriod.DAY, 0), emptyCapture).uniquePlayers());
        assertThrows(UnsupportedOperationException.class, () -> emptyCapture.profiles().clear());
        store.disconnect(PLAYER, false);
        ParticipationSnapshot disconnected = summary(store, StatsPeriod.DAY, 0);
        assertEquals(1, disconnected.completedSessions());
        assertEquals(0, disconnected.medianSessionMillis().orElseThrow());
    }

    @Test
    void reconnectsStayInOneCohortAndMedianUsesFullNormallyEndedEligibleDurations() {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore store = store(clock, settings(20));
        store.join(PLAYER, "River");
        clock.advance(1_001);
        store.quit(PLAYER);
        store.join(PLAYER, "River");
        clock.advance(2_000);
        store.quit(PLAYER);
        store.join(PLAYER, "River");
        clock.advance(500);
        ParticipationSnapshot result = summary(store, StatsPeriod.DAY, 0);
        assertEquals(1, result.uniquePlayers());
        assertEquals(1, result.newPlayers());
        assertEquals(0, result.returningPlayers());
        assertEquals(2, result.completedSessions());
        assertEquals(1_500.5, result.medianSessionMillis().orElseThrow());
        assertEquals(1, result.openSessions());
        store.setTracking(PLAYER, false);
        clock.advance(3_000);
        store.quit(PLAYER);
        assertEquals(1_001, summary(store, StatsPeriod.DAY, 0).medianSessionMillis().orElseThrow());
    }

    @Test
    void spanningSessionsBelongToEachObservedWindowButTheirWholeDurationBelongsToEndWindow() {
        MutableClock clock = new MutableClock("2026-05-31T23:59:59Z");
        StatsStore store = store(clock, settings(20));
        store.join(PLAYER, "River");
        clock.advance(3_000);
        store.quit(PLAYER);
        for (StatsPeriod period : List.of(StatsPeriod.DAY, StatsPeriod.WEEK, StatsPeriod.MONTH)) {
            ParticipationSnapshot previous = summary(store, period, 1);
            assertEquals(1, previous.uniquePlayers());
            assertEquals(1, previous.newPlayers());
            assertEquals(0, previous.completedSessions());
            assertTrue(previous.medianSessionMillis().isEmpty());
            ParticipationSnapshot current = summary(store, period, 0);
            assertEquals(1, current.returningPlayers());
            assertEquals(1, current.completedSessions());
            assertEquals(3_000, current.medianSessionMillis().orElseThrow());
            assertEquals(current.window().startedAt(), current.earliestObservation());
        }
    }

    @Test
    void exactBoundaryDisconnectAndZeroLengthRealSessionRemainObservedConnections() {
        MutableClock clock = new MutableClock("2026-10-01T23:59:59Z");
        StatsStore store = store(clock, settings(20));
        store.join(PLAYER, "River");
        clock.advance(1_000);
        store.quit(PLAYER);
        store.connect(new StatsStore.Connection(OTHER, "Visitor", false, false));
        store.disconnect(OTHER, false);
        ParticipationSnapshot current = summary(store, StatsPeriod.DAY, 0);
        assertEquals(2, current.uniquePlayers());
        assertEquals(1, current.newPlayers());
        assertEquals(1, current.returningPlayers());
        assertEquals(2, current.completedSessions());
        assertEquals(500, current.medianSessionMillis().orElseThrow());
        assertEquals(1, summary(store, StatsPeriod.DAY, 1).uniquePlayers());
        assertEquals(0, summary(store, StatsPeriod.DAY, 1).completedSessions());
    }

    @Test
    void legacyProfileWithoutConnectionProvenanceIsUnknownAndRetainedEarlierSessionProvesReturning() throws IOException {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        Files.writeString(file(), """
                {"version":1,"players":[{"uuid":"00000000-0000-0000-0000-000000000001","name":"Old","updated-at":1000,
                "values":{"joins":100,"online_time":900000}}]}
                """);
        StatsStore legacy = store(clock, settings(20));
        legacy.load();
        assertEquals(0, summary(legacy, StatsPeriod.DAY, 0).uniquePlayers());
        assertTrue(summary(legacy, StatsPeriod.DAY, 0).retentionLimited());
        legacy.join(PLAYER, "Old");
        clock.advance(500);
        legacy.quit(PLAYER);
        ParticipationSnapshot first = summary(legacy, StatsPeriod.DAY, 0);
        assertEquals(0, first.newPlayers());
        assertEquals(1, first.unknownCohortPlayers());
        legacy.save();
        clock.advance(DAY);
        StatsStore restored = store(clock, settings(20));
        restored.load();
        restored.join(PLAYER, "Old");
        ParticipationSnapshot returning = summary(restored, StatsPeriod.DAY, 0);
        assertEquals(1, returning.returningPlayers());
        assertEquals(0, returning.unknownCohortPlayers());
        assertTrue(returning.retentionLimited());
    }

    @Test
    void missingMetadataRecoversEarliestActualSessionBeforePruningWithoutInventingFirstConnection() throws IOException {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore original = store(clock, settings(20));
        original.join(PLAYER, "River");
        clock.advance(1_000);
        original.quit(PLAYER);
        clock.advance(DAY);
        original.join(PLAYER, "River");
        original.quit(PLAYER);
        original.save();
        JsonObject data = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        history(data).remove("participation");
        Files.writeString(file(), data.toString());
        StatsStore restored = store(clock, settings(1));
        restored.load();
        assertEquals(1, restored.sessions(PLAYER).size());
        ParticipationSnapshot current = summary(restored, StatsPeriod.DAY, 0);
        assertEquals(1, current.returningPlayers());
        assertEquals(0, current.newPlayers());
        assertTrue(current.retentionLimited());
        assertEquals(0, summary(restored, StatsPeriod.DAY, 1).uniquePlayers());
        assertTrue(summary(restored, StatsPeriod.DAY, 1).retentionLimited());
    }

    @Test
    void durableFirstConnectionSurvivesSessionPruningAndDoesNotInventOldParticipants() throws IOException {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore original = store(clock, settings(1));
        original.join(PLAYER, "River");
        clock.advance(1_000);
        original.quit(PLAYER);
        clock.advance(DAY);
        original.join(PLAYER, "River");
        clock.advance(1_000);
        original.quit(PLAYER);
        original.save();
        StatsStore restored = store(clock, settings(1));
        restored.load();
        ParticipationSnapshot current = summary(restored, StatsPeriod.DAY, 0);
        assertEquals(1, current.returningPlayers());
        assertFalse(current.retentionLimited());
        ParticipationSnapshot previous = summary(restored, StatsPeriod.DAY, 1);
        assertEquals(0, previous.uniquePlayers());
        assertTrue(previous.retentionLimited());
    }

    @Test
    void interruptedAndShutdownSessionsHaveNoMedianSampleAndNeverAccrueDowntime() throws IOException {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore original = store(clock, settings(20));
        original.join(PLAYER, "River");
        clock.advance(1_000);
        original.save();
        clock.advance(DAY);
        StatsStore restored = store(clock, settings(20));
        restored.load();
        ParticipationSnapshot yesterday = summary(restored, StatsPeriod.DAY, 1);
        assertEquals(1, yesterday.interruptedSessions());
        assertEquals(0, yesterday.completedSessions());
        assertTrue(yesterday.medianSessionMillis().isEmpty());
        assertEquals(0, summary(restored, StatsPeriod.DAY, 0).uniquePlayers());
        restored.join(PLAYER, "River");
        clock.advance(2_000);
        restored.disconnectAll();
        ParticipationSnapshot today = summary(restored, StatsPeriod.DAY, 0);
        assertEquals(1, today.returningPlayers());
        assertEquals(1, today.shutdownSessions());
        assertEquals(0, today.completedSessions());
        assertTrue(today.medianSessionMillis().isEmpty());
    }

    @Test
    void calendarOffsetsRespectDstIsoWeeksAndEpochWithoutSentinelRetentionLoss() {
        ZoneId denver = ZoneId.of("America/Denver");
        HistorySettings settings = new HistorySettings(denver, 5_000, 366, 104, 120, 20);
        long now = Instant.parse("2026-03-09T12:00:00Z").toEpochMilli();
        PeriodWindow previous = new ParticipationQuery(StatsPeriod.DAY, 1).window(now, settings);
        assertEquals("2026-03-08", previous.id());
        assertEquals(23 * 3_600_000, previous.endsAt() - previous.startedAt());
        long year = Instant.parse("2021-01-04T12:00:00Z").toEpochMilli();
        assertEquals("2020-W53", new ParticipationQuery(StatsPeriod.WEEK, 1).window(year, settings).id());
        assertThrows(IllegalArgumentException.class, () -> new ParticipationQuery(StatsPeriod.LIFETIME, 0));
        assertThrows(IllegalArgumentException.class, () -> new ParticipationQuery(StatsPeriod.DAY, -1));
        assertThrows(IllegalArgumentException.class, () -> new ParticipationQuery(StatsPeriod.DAY, Integer.MAX_VALUE).window(now, settings));
        MutableClock clock = new MutableClock("1970-01-01T00:00:00Z");
        StatsStore store = store(clock, settings(20));
        store.connect(new StatsStore.Connection(PLAYER, "River", false, false));
        store.disconnect(PLAYER, false);
        ParticipationSnapshot epoch = summary(store, StatsPeriod.WEEK, 0);
        assertTrue(epoch.window().startedAt() < 0);
        assertEquals(1, epoch.uniquePlayers());
        assertEquals(1, epoch.newPlayers());
        assertEquals(1, epoch.completedSessions());
        assertEquals(0, epoch.earliestObservation());
        assertFalse(epoch.retentionLimited());
    }

    @Test
    void malformedParticipationMetadataDoesNotReplaceLoadedData() throws IOException {
        MutableClock clock = new MutableClock("2026-10-01T12:00:00Z");
        StatsStore store = store(clock, settings(20));
        store.join(PLAYER, "River");
        store.quit(PLAYER);
        store.save();
        String original = Files.readString(file());
        for (String key : List.of("first-connection-known", "retention-known")) {
            for (String value : List.of("\"true\"", "0", "null")) {
                JsonObject data = JsonParser.parseString(original).getAsJsonObject();
                history(data).getAsJsonObject("participation").add(key, JsonParser.parseString(value));
                Files.writeString(file(), data.toString());
                assertThrows(IOException.class, store::load);
                assertEquals(1, summary(store, StatsPeriod.DAY, 0).newPlayers());
            }
        }
        for (String value : List.of("-2", "1.5", "9223372036854775808")) {
            JsonObject data = JsonParser.parseString(original).getAsJsonObject();
            history(data).getAsJsonObject("participation").add("first-connection-at", JsonParser.parseString(value));
            Files.writeString(file(), data.toString());
            assertThrows(IOException.class, store::load);
        }
    }

    private StatsStore store(MutableClock clock, HistorySettings settings) {
        StatsStore store = new StatsStore(file(), clock);
        store.configureHistory(settings);
        return store;
    }

    private Path file() {
        return directory.resolve("players.json");
    }

    private static JsonObject history(JsonObject root) {
        return root.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonObject("history");
    }

    private static HistorySettings settings(int sessions) {
        return new HistorySettings(ZoneOffset.UTC, 5_000, 35, 12, 12, sessions);
    }

    private static ParticipationSnapshot summary(StatsStore store, StatsPeriod period, int offset) {
        return ParticipationSnapshot.create(new ParticipationQuery(period, offset), store.participationCapture());
    }

    private static final class MutableClock extends Clock {
        private long now;

        private MutableClock(String instant) {
            now = Instant.parse(instant).toEpochMilli();
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
