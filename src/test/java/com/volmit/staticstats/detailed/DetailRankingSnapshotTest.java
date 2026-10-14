package com.volmit.staticstats.detailed;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailRankingSnapshotTest {
    private static final DetailKey COUNTER = DetailKey.MINED_DIAMOND_ORE;

    @Test
    void exactLongScoresDistinguishAdjacentLargeValuesAndShareCompetitionRanks() {
        long large = 9_007_199_254_740_992L;
        DetailRankingSnapshot ranking = DetailRankingSnapshot.create(COUNTER, 2_000,
                List.of(profile(5, "ZeroOtherCounter", DetailKey.CRAFTED_ITEMS, 123, 5),
                        profile(4, "Last", COUNTER, large, 800),
                        profile(3, "zed", COUNTER, large + 1, 700),
                        profile(2, "amy", COUNTER, large + 1, 600),
                        profile(1, "Amy", COUNTER, large + 1, 500),
                        new DetailSnapshot(new UUID(0, 6), "NoObservations", Map.of(), Map.of(), Map.of(), 0)));
        assertEquals(List.of(1L, 2L, 3L, 4L), ranking.entries().stream()
                .map(entry -> entry.profile().uuid().getLeastSignificantBits()).toList());
        assertEquals(List.of(1, 2, 3, 4), ranking.entries().stream().map(RankedDetailPlayer::position).toList());
        assertEquals(List.of(1, 1, 1, 4), ranking.entries().stream().map(RankedDetailPlayer::rank).toList());
        assertEquals(List.of(large + 1, large + 1, large + 1, large), ranking.entries().stream().map(RankedDetailPlayer::value).toList());
        assertEquals(List.of(0L, 0L, 0L, 1L), ranking.entries().stream().map(RankedDetailPlayer::gapToNext).toList());
        assertEquals(6, ranking.totalProfiles());
        assertEquals(2, ranking.excludedCount());
        assertEquals(500, ranking.recordedSince());
        assertSame(ranking.entries().get(3), ranking.find(new UUID(0, 4)).orElseThrow());
        assertTrue(ranking.find(new UUID(0, 5)).isEmpty());
        assertEquals(COUNTER, ranking.counter());
        assertEquals(2_000, ranking.refreshedAt());
    }

    @Test
    void maximumLongGapsRemainExactAndGroupsUseNextStrictlyHigherValue() {
        DetailRankingSnapshot ranking = DetailRankingSnapshot.create(COUNTER, 2_000,
                List.of(profile(5, "E", COUNTER, 1, 100), profile(2, "B", COUNTER, 7, 100),
                        profile(4, "D", COUNTER, 1, 100), profile(1, "A", COUNTER, Long.MAX_VALUE, 100),
                        profile(3, "C", COUNTER, 7, 100)));
        assertEquals(List.of(1, 2, 2, 4, 4), ranking.entries().stream().map(RankedDetailPlayer::rank).toList());
        assertEquals(List.of(0L, Long.MAX_VALUE - 7, Long.MAX_VALUE - 7, 6L, 6L),
                ranking.entries().stream().map(RankedDetailPlayer::gapToNext).toList());
        DetailRankingSnapshot extreme = DetailRankingSnapshot.create(COUNTER, 2_000,
                List.of(profile(1, "A", COUNTER, Long.MAX_VALUE, 100), profile(2, "B", COUNTER, 1, 100)));
        assertEquals(Long.MAX_VALUE - 1, extreme.entries().getLast().gapToNext());
    }

    @Test
    void emptyAndDetachedRankingsDoNotInventCoverageOrAcceptDuplicateProfiles() {
        DetailRankingSnapshot empty = DetailRankingSnapshot.create(COUNTER, 1_000, List.of());
        assertTrue(empty.entries().isEmpty());
        assertEquals(0, empty.recordedSince());
        DetailSnapshot profile = profile(1, "River", COUNTER, 8, 100);
        DetailRankingSnapshot ranking = DetailRankingSnapshot.create(COUNTER, 1_000, List.of(profile));
        assertThrows(UnsupportedOperationException.class, () -> ranking.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> ranking.entries().getFirst().profile().values().clear());
        assertThrows(IllegalArgumentException.class, () -> DetailRankingSnapshot.create(COUNTER, 1_000, List.of(profile, profile)));
    }

    private static DetailSnapshot profile(long id, String name, DetailKey key, long amount, long observedAt) {
        return new DetailSnapshot(new UUID(0, id), name, Map.of(key, amount), Map.of(key, observedAt),
                Map.of(key, observedAt), observedAt);
    }
}
