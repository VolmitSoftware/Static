package com.volmit.staticstats.gui;

import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.stats.StatKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigEditorGuiTest {
    @Test
    void everyPersistedSettingCanBeEdited() {
        Set<String> paths = new LinkedHashSet<>();
        for (Field section : StaticConfig.class.getFields()) {
            for (Field setting : section.getType().getFields()) {
                if (section.getName().equals("milestones") && setting.getName().equals("thresholds")) {
                    for (StatKey statistic : StatKey.values()) {
                        if (!statistic.derived()) {
                            paths.add("milestones.thresholds." + statistic.id());
                        }
                    }
                } else {
                    paths.add(section.getName() + "." + setting.getName());
                }
            }
        }
        assertThat(ConfigEditorGui.editablePaths()).containsExactlyInAnyOrderElementsOf(paths);
        assertThat(ConfigEditorGui.languageUsesPicker()).isTrue();
        assertThat(ConfigEditorGui.maximumCategorySize()).isLessThan(45);
        assertThat(ConfigEditorGui.navigationSlots()).doesNotContainAnyElementsOf(ConfigEditorGui.rootCategorySlots().keySet());
        assertThat(ConfigEditorGui.rootCategorySlots()).containsEntry(39, "RANKING").containsEntry(41, "HISTORY")
                .containsEntry(43, "MILESTONES").containsEntry(11, "SEASONS").containsEntry(12, "DETAILS");
    }

    @Test
    void milestoneLaddersRejectInvalidInputWithoutReplacingThePreviousLadder() {
        StaticConfig config = new StaticConfig();
        ConfigEditorGui.writeTargets(config, StatKey.BLOCKS_BROKEN, "0.5, 100, 1000");
        assertThat(config.milestones.thresholds.get("blocks_broken")).containsExactly(0.5, 100D, 1000D);
        for (String invalid : new String[]{"0", "-1", "NaN", "Infinity", "2, 1", "1, 1"}) {
            assertThatThrownBy(() -> ConfigEditorGui.writeTargets(config, StatKey.BLOCKS_BROKEN, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(config.milestones.thresholds.get("blocks_broken")).containsExactly(0.5, 100D, 1000D);
        ConfigEditorGui.writeTargets(config, StatKey.BLOCKS_BROKEN, "none");
        assertThat(config.milestones.thresholds).doesNotContainKey("blocks_broken");
    }

    @Test
    void detailedSelectionsCanBeAdjustedWithoutReplacingTheWholeList() {
        StaticConfig config = new StaticConfig();
        ConfigEditorGui.writeDetailKeys(config, "mined_diamond_ore, caught_items");
        ConfigEditorGui.writeDetailKeys(config, "+traded_items, -caught_items");
        assertThat(config.details.selectedKeys).containsExactly("mined_diamond_ore", "traded_items");
        assertThatThrownBy(() -> ConfigEditorGui.writeDetailKeys(config, "+caught_items, missing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigEditorGui.writeDetailKeys(config, "+missing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(config.details.selectedKeys).containsExactly("mined_diamond_ore", "traded_items");
        ConfigEditorGui.writeDetailKeys(config, "none");
        assertThat(config.details.selectedKeys).isEmpty();
    }
}
