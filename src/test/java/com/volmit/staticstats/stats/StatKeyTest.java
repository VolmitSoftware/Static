package com.volmit.staticstats.stats;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatKeyTest {
    @Test
    void derivesAccuracyFromShotsAndProducesFiniteEmptyStatistics() {
        PlayerStatsSnapshot snapshot = snapshot(Map.of(StatKey.ARROWS_FIRED, 10D, StatKey.ARROW_HITS_DEALT, 8D));
        assertEquals(0.8, snapshot.value(StatKey.ARROW_ACCURACY));
        assertEquals(2, snapshot.value(StatKey.ARROWS_MISSED));
        assertEquals("80.0%", StatKey.ARROW_ACCURACY.format(snapshot.value(StatKey.ARROW_ACCURACY)));
        PlayerStatsSnapshot empty = snapshot(Map.of());
        for (StatKey key : StatKey.values()) {
            assertTrue(Double.isFinite(empty.value(key)), key.id());
            assertEquals(0, empty.value(key), key.id());
            assertEquals(key, StatKey.parse(key.id()));
        }
    }

    @Test
    void derivesRatiosAndVotesPerDayFromBaseValues() {
        PlayerStatsSnapshot snapshot = snapshot(Map.of(StatKey.PVP_KILLS, 6D, StatKey.PVP_DEATHS, 2D,
                StatKey.MELEE_DAMAGE_DEALT, 10D, StatKey.MELEE_DAMAGE_TAKEN, 4D,
                StatKey.VOTES, 3D, StatKey.ONLINE_TIME, 43_200_000D));
        assertEquals(3, snapshot.value(StatKey.KILL_DEATH_RATIO));
        assertEquals(2.5, snapshot.value(StatKey.MELEE_DAMAGE_RATIO));
        assertEquals(6, snapshot.value(StatKey.VOTES_PER_DAY));
    }

    @Test
    void formatsFractionalDistancesAndLiveDuration() {
        assertEquals("12.5 m", StatKey.DISTANCE_WALKED.format(12.5));
        assertEquals("1.25 km", StatKey.DISTANCE_TRAVELED.format(1250));
        assertEquals("2d 3h 4m", StatKey.ONLINE_TIME.format(183_840_000));
        assertEquals("2.25", StatKey.MELEE_DAMAGE_DEALT.format(2.25));
    }

    private static PlayerStatsSnapshot snapshot(Map<StatKey, Double> values) {
        return new PlayerStatsSnapshot(UUID.fromString("10000000-0000-0000-0000-000000000001"), "River", values, 0, 0);
    }
}
