package com.volmit.staticstats.milestone;

import com.volmit.staticstats.stats.StatKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MilestoneSettingsTest {
    @Test
    void validatesFiniteAscendingBaseOnlyLaddersAndTheirBounds() {
        for (StatKey statistic : StatKey.values()) {
            if (statistic.derived()) {
                assertThrows(IllegalArgumentException.class, () -> new MilestoneSettings(true, Map.of(statistic, List.of(1D))));
            }
        }
        for (List<Double> invalid : List.of(List.of(0D), List.of(-1D), List.of(Double.NaN),
                List.of(Double.POSITIVE_INFINITY), List.of(2D, 1D), List.of(1D, 1D))) {
            assertThrows(IllegalArgumentException.class, () -> new MilestoneSettings(true, Map.of(StatKey.VOTES, invalid)));
        }
        List<Double> tooMany = new ArrayList<>();
        for (int threshold = 1; threshold <= 17; threshold++) {
            tooMany.add((double) threshold);
        }
        assertThrows(IllegalArgumentException.class, () -> new MilestoneSettings(true, Map.of(StatKey.VOTES, tooMany)));
        assertEquals(9, MilestoneSettings.defaults().definitions().size());
        assertEquals("active_time:3600000", new MilestoneDefinition(StatKey.ACTIVE_TIME, 3_600_000).id());
        assertEquals("distance_walked:0.125", new MilestoneDefinition(StatKey.DISTANCE_WALKED, 0.125).id());
    }

    @Test
    void copiesMutableInputsAndSharesOneImmutableCompiledDefinitionIndex() {
        List<Double> ladder = new ArrayList<>(List.of(1D, 2D));
        Map<StatKey, List<Double>> map = new EnumMap<>(StatKey.class);
        map.put(StatKey.VOTES, ladder);
        MilestoneSettings settings = new MilestoneSettings(true, map);
        ladder.clear();
        map.clear();
        assertEquals(List.of(1D, 2D), settings.thresholds().get(StatKey.VOTES));
        assertSame(settings.definitions(), settings.definitions());
        assertSame(settings.definitionIndex(), settings.definitionIndex());
        assertThrows(UnsupportedOperationException.class, () -> settings.thresholds().clear());
        assertThrows(UnsupportedOperationException.class, () -> settings.definitions().clear());
        assertEquals(new MilestoneSettings(true, Map.of(StatKey.VOTES, List.of(1D, 2D))), settings);
    }
}
