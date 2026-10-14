package com.volmit.staticstats.gui;

import com.volmit.staticstats.config.StaticConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonEditorGuiTest {
    @Test
    void applyingADraftUsesADetachedCopyAndPreservesOtherEvents() {
        StaticConfig config = new StaticConfig();
        StaticConfig.Season first = season("first");
        config.seasons.events.add(first);
        StaticConfig.Season second = season("second");
        SeasonEditorGui.apply(config, null, second, false);
        assertEquals(List.of("first", "second"), config.seasons.events.stream().map(event -> event.id).toList());
        assertNotSame(second, config.seasons.events.get(1));
        StaticConfig.Season edited = first.copy();
        edited.name = "Updated";
        SeasonEditorGui.apply(config, first.copy(), edited, false);
        assertEquals("Updated", config.seasons.events.get(0).name);
        assertEquals("first", first.name);
    }

    @Test
    void simultaneousEditsAndDuplicateIdsDoNotReplaceSavedDefinitions() {
        StaticConfig config = new StaticConfig();
        StaticConfig.Season saved = season("first");
        config.seasons.events.add(saved);
        StaticConfig.Season original = saved.copy();
        saved.minimumSample = 12.5;
        assertThrows(IllegalArgumentException.class, () -> SeasonEditorGui.apply(config, original, season("first"), false));
        assertThrows(IllegalArgumentException.class, () -> SeasonEditorGui.apply(config, original, season("first"), true));
        assertThrows(IllegalArgumentException.class, () -> SeasonEditorGui.apply(config, null, season("first"), false));
        assertEquals(12.5, config.seasons.events.get(0).minimumSample);
        SeasonEditorGui.apply(config, saved.copy(), saved.copy(), true);
        assertTrue(config.seasons.events.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SeasonEditorGui.apply(config, saved, saved.copy(), false));
    }

    private StaticConfig.Season season(String id) {
        StaticConfig.Season season = new StaticConfig.Season();
        season.id = id;
        season.name = id;
        season.startsAt = "2027-01-01T00:00:00Z";
        season.endsAt = "2027-01-08T00:00:00Z";
        return season;
    }
}
