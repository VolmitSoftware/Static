package com.volmit.staticstats.detailed;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record DetailSettings(boolean enabled, Set<DetailKey> selectedKeys) {
    public static final int MAX_SELECTED_KEYS = 64;

    public DetailSettings {
        Objects.requireNonNull(selectedKeys);
        if (selectedKeys.size() > MAX_SELECTED_KEYS) {
            throw new IllegalArgumentException("At most " + MAX_SELECTED_KEYS + " detailed counters can be selected");
        }
        Set<DetailKey> copy = EnumSet.noneOf(DetailKey.class);
        copy.addAll(selectedKeys);
        selectedKeys = Collections.unmodifiableSet(copy);
    }

    public static DetailSettings defaults() {
        Set<DetailKey> selected = EnumSet.allOf(DetailKey.class);
        selected.removeAll(Set.of(DetailKey.PLACED_DIRT, DetailKey.PLACED_OBSIDIAN,
                DetailKey.KILLED_BLAZE, DetailKey.KILLED_WITHER_SKELETON, DetailKey.KILLED_SLIME, DetailKey.KILLED_DROWNED,
                DetailKey.CRAFTED_BREAD, DetailKey.CRAFTED_TORCH, DetailKey.CRAFTED_OAK_PLANKS, DetailKey.CRAFTED_IRON_PICKAXE,
                DetailKey.TRADED_EMERALD, DetailKey.TRADED_BREAD, DetailKey.TRADED_ENCHANTED_BOOK, DetailKey.TRADED_DIAMOND_PICKAXE));
        return new DetailSettings(true, selected);
    }

    public boolean records(DetailKey key) {
        return enabled && selectedKeys.contains(key);
    }
}
