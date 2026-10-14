package com.volmit.staticstats.stats;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingRulesTest {
    @Test
    void allSevenRatiosUseTheirRawDenominatorAndIncludeTheExactMinimum() {
        RankingRules rules = RankingRules.defaults();
        Map<StatKey, RankingRequirement> expected = Map.of(
                StatKey.KILL_DEATH_RATIO, new RankingRequirement(StatKey.PVP_KILLS, StatKey.PVP_DEATHS, 10),
                StatKey.MELEE_DAMAGE_RATIO, new RankingRequirement(StatKey.MELEE_DAMAGE_DEALT, StatKey.MELEE_DAMAGE_TAKEN, 100),
                StatKey.MELEE_HIT_RATIO, new RankingRequirement(StatKey.MELEE_HITS_DEALT, StatKey.MELEE_HITS_TAKEN, 25),
                StatKey.ARROW_DAMAGE_RATIO, new RankingRequirement(StatKey.ARROW_DAMAGE_DEALT, StatKey.ARROW_DAMAGE_TAKEN, 100),
                StatKey.ARROW_HIT_RATIO, new RankingRequirement(StatKey.ARROW_HITS_DEALT, StatKey.ARROW_HITS_TAKEN, 25),
                StatKey.ARROW_ACCURACY, new RankingRequirement(StatKey.ARROW_HITS_DEALT, StatKey.ARROWS_FIRED, 50),
                StatKey.VOTES_PER_DAY, new RankingRequirement(StatKey.VOTES, StatKey.ONLINE_TIME, 3_600_000));
        assertEquals(expected.keySet(), rules.minimums().keySet());
        for (Map.Entry<StatKey, RankingRequirement> entry : expected.entrySet()) {
            StatKey statistic = entry.getKey();
            RankingRequirement requirement = rules.requirement(statistic).orElseThrow();
            assertEquals(entry.getValue(), requirement);
            PlayerStatsSnapshot below = profile(1, Map.of(requirement.numeratorKey(), 500D,
                    requirement.denominatorKey(), Math.nextDown(requirement.minimum())));
            PlayerStatsSnapshot boundary = profile(2, Map.of(requirement.numeratorKey(), 3D,
                    requirement.denominatorKey(), requirement.minimum()));
            RankingQualification unqualified = rules.evaluate(statistic, below).orElseThrow();
            RankingQualification qualified = rules.evaluate(statistic, boundary).orElseThrow();
            assertFalse(unqualified.qualified(), statistic.id());
            assertTrue(qualified.qualified(), statistic.id());
            assertEquals(requirement.numeratorKey(), qualified.numeratorKey());
            assertEquals(3, qualified.numerator());
            assertEquals(requirement.denominatorKey(), qualified.denominatorKey());
            assertEquals(requirement.minimum(), qualified.denominator());
            assertEquals(requirement.minimum(), qualified.minimum());
            assertEquals(3, boundary.baseValue(requirement.numeratorKey()));
        }
    }

    @Test
    void zeroMinimumStillRequiresPositiveDenominatorAndAcceptsFractionalSamples() {
        EnumMap<StatKey, Double> minimums = new EnumMap<>(RankingRules.defaults().minimums());
        minimums.replaceAll((statistic, minimum) -> 0D);
        RankingRules rules = new RankingRules(minimums);
        for (StatKey statistic : minimums.keySet()) {
            RankingRequirement requirement = rules.requirement(statistic).orElseThrow();
            assertFalse(rules.evaluate(statistic, profile(1, Map.of(requirement.numeratorKey(), 10D))).orElseThrow().qualified());
            assertTrue(rules.evaluate(statistic, profile(1, Map.of(requirement.denominatorKey(), 0.25D))).orElseThrow().qualified());
        }
        minimums.put(StatKey.MELEE_DAMAGE_RATIO, 0.5D);
        RankingRules fractional = new RankingRules(minimums);
        assertFalse(fractional.evaluate(StatKey.MELEE_DAMAGE_RATIO,
                profile(1, Map.of(StatKey.MELEE_DAMAGE_TAKEN, 0.49D))).orElseThrow().qualified());
        assertTrue(fractional.evaluate(StatKey.MELEE_DAMAGE_RATIO,
                profile(1, Map.of(StatKey.MELEE_DAMAGE_TAKEN, 0.5D))).orElseThrow().qualified());
    }

    @Test
    void snapshotsExcludeSmallSamplesBeforeRankingAndKeepTheirQualificationDetails() {
        PlayerStatsSnapshot inflated = profile(1, Map.of(StatKey.PVP_KILLS, 900D, StatKey.PVP_DEATHS, 1D));
        PlayerStatsSnapshot zeroDenominator = profile(2, Map.of(StatKey.PVP_KILLS, 1000D));
        PlayerStatsSnapshot first = profile(3, Map.of(StatKey.PVP_KILLS, 40D, StatKey.PVP_DEATHS, 10D));
        PlayerStatsSnapshot tied = profile(4, Map.of(StatKey.PVP_KILLS, 80D, StatKey.PVP_DEATHS, 20D));
        PlayerStatsSnapshot lower = profile(5, Map.of(StatKey.PVP_KILLS, 10D, StatKey.PVP_DEATHS, 10D));
        RankingRules rules = RankingRules.defaults();
        RankingSnapshot ranking = RankingSnapshot.create(StatKey.KILL_DEATH_RATIO, 42,
                List.of(inflated, zeroDenominator, first, tied, lower), rules, StatsPeriod.LIFETIME.window(42, ZoneOffset.UTC), ZoneOffset.UTC);
        assertEquals(5, ranking.totalProfiles());
        assertEquals(2, ranking.excludedCount());
        assertEquals(List.of(first, tied, lower), ranking.entries().stream().map(RankedPlayer::profile).toList());
        assertEquals(List.of(1, 1, 3), ranking.entries().stream().map(RankedPlayer::rank).toList());
        assertEquals(List.of(1, 2, 3), ranking.entries().stream().map(RankedPlayer::position).toList());
        assertEquals(List.of(0D, 0D, 3D), ranking.entries().stream().map(RankedPlayer::gapToNext).toList());
        assertTrue(ranking.find(inflated.uuid()).isEmpty());
        assertFalse(ranking.qualification(inflated.uuid()).orElseThrow().qualified());
        assertEquals(1, ranking.qualification(inflated.uuid()).orElseThrow().denominator());
        assertTrue(ranking.qualification(first.uuid()).orElseThrow().qualified());
        assertTrue(ranking.qualification(new UUID(0, 99)).isEmpty());
        assertEquals(rules, ranking.rules());
        assertEquals(900, inflated.value(StatKey.KILL_DEATH_RATIO));
    }

    @Test
    void baseStatisticsAndArrowsMissedHaveNoQualificationFilter() {
        PlayerStatsSnapshot profile = profile(1, Map.of(StatKey.ARROWS_FIRED, 3D));
        RankingRules rules = RankingRules.defaults();
        for (StatKey statistic : StatKey.values()) {
            if (rules.minimums().containsKey(statistic)) {
                continue;
            }
            assertTrue(rules.requirement(statistic).isEmpty());
            assertTrue(rules.evaluate(statistic, profile).isEmpty());
            RankingSnapshot ranking = RankingSnapshot.create(statistic, 42, List.of(profile), rules,
                    StatsPeriod.LIFETIME.window(42, ZoneOffset.UTC), ZoneOffset.UTC);
            assertEquals(1, ranking.totalProfiles());
            assertEquals(0, ranking.excludedCount());
            assertEquals(1, ranking.entries().size());
            assertTrue(ranking.qualification(profile.uuid()).isEmpty());
        }
    }

    @Test
    void emptyRatioRankingRetainsRequirementsWithoutInventingProfiles() {
        RankingRules rules = RankingRules.defaults();
        RankingSnapshot ranking = RankingSnapshot.create(StatKey.ARROW_ACCURACY, 42, List.of(), rules,
                StatsPeriod.LIFETIME.window(42, ZoneOffset.UTC), ZoneOffset.UTC);
        assertEquals(0, ranking.totalProfiles());
        assertEquals(0, ranking.excludedCount());
        assertTrue(ranking.entries().isEmpty());
        assertEquals(50, ranking.rules().requirement(ranking.statistic()).orElseThrow().minimum());
    }

    @Test
    void rulesDefensivelyCopyAndRejectUnsupportedMissingOrInvalidThresholds() {
        EnumMap<StatKey, Double> minimums = new EnumMap<>(RankingRules.defaults().minimums());
        RankingRules captured = new RankingRules(minimums);
        minimums.put(StatKey.KILL_DEATH_RATIO, 99D);
        assertEquals(10, captured.requirement(StatKey.KILL_DEATH_RATIO).orElseThrow().minimum());
        assertThrows(UnsupportedOperationException.class, () -> captured.minimums().clear());
        for (double invalid : List.of(-1D, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            minimums.put(StatKey.KILL_DEATH_RATIO, invalid);
            assertThrows(IllegalArgumentException.class, () -> new RankingRules(minimums));
        }
        minimums.put(StatKey.KILL_DEATH_RATIO, 0D);
        minimums.remove(StatKey.ARROW_ACCURACY);
        assertThrows(IllegalArgumentException.class, () -> new RankingRules(minimums));
        minimums.put(StatKey.ARROWS_MISSED, 50D);
        assertThrows(IllegalArgumentException.class, () -> new RankingRules(minimums));
    }

    private static PlayerStatsSnapshot profile(long id, Map<StatKey, Double> values) {
        return new PlayerStatsSnapshot(new UUID(0, id), "Player" + id, values, 1_000, 1_000);
    }
}
