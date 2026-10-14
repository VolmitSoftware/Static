package com.volmit.staticstats.season;

import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.StatKey;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonEngineTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);
    private static final SeasonContext SURVIVAL = new SeasonContext("world", "SURVIVAL");
    private static final SeasonContext CREATIVE = new SeasonContext("world", "CREATIVE");

    @Test
    void clipsIntervalsToExclusiveEndAndSplitsFrozenAfkExactly() throws Exception {
        MutableClock clock = new MutableClock();
        try (SeasonEngine engine = new SeasonEngine(clock)) {
            SeasonDefinition definition = definition("event", 2_000, 7_000, StatKey.ACTIVE_TIME, false, 3_000);
            engine.configure(new SeasonSettings(List.of(definition)));
            engine.connect(PLAYER, "River", SURVIVAL, false);
            assertEquals(0, engine.season("event").orElseThrow().recordedSince());
            engine.add(PLAYER, "River", StatKey.BLOCKS_BROKEN, 100, SURVIVAL);
            clock.now = 2_000;
            engine.add(PLAYER, "River", StatKey.BLOCKS_BROKEN, 2, SURVIVAL);
            clock.now = 8_000;
            engine.tick();
            SeasonRankingSnapshot result = engine.ranking("event").get(5, TimeUnit.SECONDS);
            PlayerStatsSnapshot profile = result.ranking().entries().getFirst().profile();
            assertEquals(5_000, profile.value(StatKey.ONLINE_TIME));
            assertEquals(2_000, profile.value(StatKey.ACTIVE_TIME));
            assertEquals(3_000, profile.value(StatKey.AFK_TIME));
            assertEquals(2, profile.value(StatKey.BLOCKS_BROKEN));
            assertEquals(SeasonStatus.ARCHIVED, result.season().status());
            engine.add(PLAYER, "Changed", StatKey.BLOCKS_BROKEN, 100, SURVIVAL);
            assertEquals(result, engine.ranking("event").get(5, TimeUnit.SECONDS));
            assertEquals("River", result.ranking().entries().getFirst().profile().name());
        }
    }

    @Test
    void ratioUsesSeasonComponentsAndFrozenSampleMinimumWithCompetitionTies() throws Exception {
        MutableClock clock = new MutableClock();
        try (SeasonEngine engine = new SeasonEngine(clock)) {
            SeasonDefinition definition = definition("combat", 1_000, 10_000, StatKey.KILL_DEATH_RATIO, true, 5_000);
            engine.configure(new SeasonSettings(List.of(definition)));
            engine.add(PLAYER, "Zed", StatKey.PVP_KILLS, 40, CREATIVE);
            engine.add(PLAYER, "Zed", StatKey.PVP_DEATHS, 10, CREATIVE);
            engine.add(OTHER, "Amy", StatKey.PVP_KILLS, 80, null);
            engine.add(OTHER, "Amy", StatKey.PVP_DEATHS, 20, null);
            UUID smallSample = new UUID(0, 3);
            engine.add(smallSample, "Small", StatKey.PVP_KILLS, 1000, null);
            engine.add(smallSample, "Small", StatKey.PVP_DEATHS, 1, null);
            clock.now = 10_000;
            SeasonRankingSnapshot ranking = engine.ranking("combat").get(5, TimeUnit.SECONDS);
            assertEquals(2, ranking.winners().size());
            assertEquals(List.of("Amy", "Zed"), ranking.winners().stream().map(entry -> entry.profile().name()).toList());
            assertEquals(List.of(1, 1), ranking.winners().stream().map(entry -> entry.rank()).toList());
            assertEquals(1, ranking.ranking().excludedCount());
            assertEquals(10, ranking.ranking().qualification(PLAYER).orElseThrow().minimum());
            assertEquals(1, ranking.ranking().qualification(smallSample).orElseThrow().denominator());
        }
    }

    @Test
    void frozenEligibilityAndResumeExcludePausedIntervals() throws Exception {
        MutableClock clock = new MutableClock();
        try (SeasonEngine engine = new SeasonEngine(clock)) {
            engine.configure(new SeasonSettings(List.of(definition("time", 1_000, 50_000, StatKey.ACTIVE_TIME, false, 5_000))));
            engine.connect(PLAYER, "River", SURVIVAL, false);
            clock.now = 3_000;
            engine.context(PLAYER, CREATIVE);
            clock.now = 20_000;
            engine.context(PLAYER, SURVIVAL);
            clock.now = 23_000;
            engine.setEnabled(false);
            clock.now = 40_000;
            engine.setEnabled(true);
            clock.now = 43_000;
            PlayerStatsSnapshot profile = engine.profile(PLAYER, "time").orElseThrow().profile();
            assertEquals(8_000, profile.value(StatKey.ONLINE_TIME));
            assertEquals(8_000, profile.value(StatKey.ACTIVE_TIME));
            assertEquals(0, profile.value(StatKey.AFK_TIME));
            assertFalse(engine.accepts(CREATIVE));
            assertTrue(engine.accepts(SURVIVAL));
        }
    }

    @Test
    void restartAfterEndFreezesSavedDataWithoutDowntimeAndEmitsOnlyOneNewCompletion() throws Exception {
        MutableClock clock = new MutableClock();
        SeasonDefinition definition = definition("event", 1_000, 10_000, StatKey.ONLINE_TIME, false, 5_000);
        SeasonEngine.Capture saved;
        try (SeasonEngine original = new SeasonEngine(clock)) {
            original.configure(new SeasonSettings(List.of(definition)));
            original.connect(PLAYER, "River", SURVIVAL, false);
            clock.now = 4_000;
            saved = original.capture();
        }
        clock.now = 20_000;
        SeasonEngine.Capture archived;
        try (SeasonEngine restored = new SeasonEngine(clock)) {
            restored.load(saved.data());
            restored.configure(new SeasonSettings(List.of(definition)));
            SeasonRankingSnapshot ranking = restored.ranking("event").get(5, TimeUnit.SECONDS);
            assertEquals(3_000, ranking.ranking().entries().getFirst().value());
            archived = restored.capture();
            assertEquals(1, archived.events().size());
            assertTrue(archived.events().getFirst().reconciledAfterRestart());
            restored.acknowledge(archived.events());
            assertTrue(restored.capture().events().isEmpty());
        }
        try (SeasonEngine reloaded = new SeasonEngine(clock)) {
            reloaded.load(archived.data());
            reloaded.configure(new SeasonSettings(List.of()));
            assertEquals(SeasonStatus.ARCHIVED, reloaded.season("event").orElseThrow().status());
            assertEquals(3_000, reloaded.ranking("event").get(5, TimeUnit.SECONDS).ranking().entries().getFirst().value());
            assertTrue(reloaded.capture().events().isEmpty());
        }
    }

    @Test
    void draftsCanChangeOrDisappearButStartedDefinitionsAreImmutableAndRetained() {
        MutableClock clock = new MutableClock();
        try (SeasonEngine engine = new SeasonEngine(clock)) {
            SeasonDefinition first = definition("event", 5_000, 10_000, StatKey.BLOCKS_BROKEN, false, 5_000);
            engine.configure(new SeasonSettings(List.of(first)));
            assertThrows(IllegalArgumentException.class, () -> engine.validate(new SeasonSettings(List.of(
                    definition("event", 100, 500, StatKey.BLOCKS_BROKEN, false, 5_000)))));
            SeasonDefinition changed = definition("event", 6_000, 12_000, StatKey.BLOCKS_PLACED, true, 7_000);
            engine.configure(new SeasonSettings(List.of(changed)));
            assertEquals(changed, engine.season("event").orElseThrow().definition());
            engine.configure(new SeasonSettings(List.of()));
            assertTrue(engine.seasons().isEmpty());
            engine.configure(new SeasonSettings(List.of(first)));
            clock.now = 5_000;
            assertThrows(IllegalArgumentException.class, () -> engine.validate(new SeasonSettings(List.of(changed))));
            engine.configure(new SeasonSettings(List.of()));
            assertEquals(first, engine.season("event").orElseThrow().definition());
            clock.now = 1_000;
            assertThrows(IllegalArgumentException.class, () -> engine.validate(new SeasonSettings(List.of(changed))));
            engine.configure(new SeasonSettings(List.of()));
            assertEquals(first, engine.season("event").orElseThrow().definition());
        }
    }

    @Test
    void configurationUsesOneCutoffAndRevalidatesBeforeChangingStartedRules() {
        MutableClock clock = new MutableClock();
        try (SeasonEngine engine = new SeasonEngine(clock)) {
            SeasonDefinition first = definition("event", 5_000, 10_000, StatKey.BLOCKS_BROKEN, false, 5_000);
            SeasonDefinition changed = definition("event", 6_000, 12_000, StatKey.BLOCKS_PLACED, true, 7_000);
            engine.configure(new SeasonSettings(List.of(first)));
            clock.now = 4_999;
            clock.step = 1;
            engine.configure(new SeasonSettings(List.of(changed)));
            assertEquals(5_000, clock.now);
            clock.step = 0;
            assertEquals(changed, engine.season("event").orElseThrow().definition());
            SeasonSettings replacement = new SeasonSettings(List.of(first));
            engine.validate(replacement);
            clock.now = 6_000;
            assertThrows(IllegalArgumentException.class, () -> engine.configure(replacement));
            assertEquals(changed, engine.season("event").orElseThrow().definition());
        }
    }

    @Test
    void lateAcceptanceRecordsPartialCoverageAndNeverImportsEarlierTotals() {
        MutableClock clock = new MutableClock();
        clock.now = 5_000;
        try (SeasonEngine engine = new SeasonEngine(clock)) {
            engine.connect(PLAYER, "River", SURVIVAL, false);
            engine.configure(new SeasonSettings(List.of(definition("late", 1_000, 10_000, StatKey.BLOCKS_BROKEN, false, 5_000))));
            assertEquals(5_000, engine.season("late").orElseThrow().recordedSince());
            assertTrue(engine.profile(PLAYER, "late").isEmpty());
            engine.add(PLAYER, "River", StatKey.BLOCKS_BROKEN, 1, SURVIVAL);
            assertEquals(1, engine.profile(PLAYER, "late").orElseThrow().profile().value(StatKey.BLOCKS_BROKEN));
            assertThrows(IllegalArgumentException.class, () -> engine.configure(new SeasonSettings(List.of(
                    definition("old", 1_000, 2_000, StatKey.BLOCKS_BROKEN, false, 5_000)))));
        }
    }

    @Test
    void boundsOverlapAndDurableIds() {
        List<SeasonDefinition> overlapping = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            overlapping.add(definition("event" + index, 1_000, 10_000, StatKey.VOTES, false, 5_000));
        }
        assertThrows(IllegalArgumentException.class, () -> new SeasonSettings(overlapping));
        List<SeasonDefinition> tooMany = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            tooMany.add(definition("event" + index, 1_000 + index * 1_000, 2_000 + index * 1_000, StatKey.VOTES, false, 5_000));
        }
        assertThrows(IllegalArgumentException.class, () -> new SeasonSettings(tooMany));
    }

    private static SeasonDefinition definition(String id, long start, long end, StatKey statistic, boolean creative, long afk) {
        return new SeasonDefinition(id, id, Instant.ofEpochMilli(start), Instant.ofEpochMilli(end),
                new SeasonRules(statistic, 10, Set.of(), creative, false, afk));
    }

    private static final class MutableClock extends Clock {
        private volatile long now = 1_000;
        private long step;

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
            long result = now;
            now += step;
            return result;
        }
    }
}
