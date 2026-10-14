package com.volmit.staticstats.detailed;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum DetailKey {
    MINED_COAL_ORE(DetailCategory.BLOCKS_MINED, "COAL_ORE"),
    MINED_DEEPSLATE_COAL_ORE(DetailCategory.BLOCKS_MINED, "DEEPSLATE_COAL_ORE"),
    MINED_IRON_ORE(DetailCategory.BLOCKS_MINED, "IRON_ORE"),
    MINED_DEEPSLATE_IRON_ORE(DetailCategory.BLOCKS_MINED, "DEEPSLATE_IRON_ORE"),
    MINED_COPPER_ORE(DetailCategory.BLOCKS_MINED, "COPPER_ORE"),
    MINED_DEEPSLATE_COPPER_ORE(DetailCategory.BLOCKS_MINED, "DEEPSLATE_COPPER_ORE"),
    MINED_GOLD_ORE(DetailCategory.BLOCKS_MINED, "GOLD_ORE"),
    MINED_DEEPSLATE_GOLD_ORE(DetailCategory.BLOCKS_MINED, "DEEPSLATE_GOLD_ORE"),
    MINED_REDSTONE_ORE(DetailCategory.BLOCKS_MINED, "REDSTONE_ORE"),
    MINED_DEEPSLATE_REDSTONE_ORE(DetailCategory.BLOCKS_MINED, "DEEPSLATE_REDSTONE_ORE"),
    MINED_LAPIS_ORE(DetailCategory.BLOCKS_MINED, "LAPIS_ORE"),
    MINED_DEEPSLATE_LAPIS_ORE(DetailCategory.BLOCKS_MINED, "DEEPSLATE_LAPIS_ORE"),
    MINED_DIAMOND_ORE(DetailCategory.BLOCKS_MINED, "DIAMOND_ORE"),
    MINED_DEEPSLATE_DIAMOND_ORE(DetailCategory.BLOCKS_MINED, "DEEPSLATE_DIAMOND_ORE"),
    MINED_EMERALD_ORE(DetailCategory.BLOCKS_MINED, "EMERALD_ORE"),
    MINED_DEEPSLATE_EMERALD_ORE(DetailCategory.BLOCKS_MINED, "DEEPSLATE_EMERALD_ORE"),
    MINED_NETHER_GOLD_ORE(DetailCategory.BLOCKS_MINED, "NETHER_GOLD_ORE"),
    MINED_NETHER_QUARTZ_ORE(DetailCategory.BLOCKS_MINED, "NETHER_QUARTZ_ORE"),
    MINED_ANCIENT_DEBRIS(DetailCategory.BLOCKS_MINED, "ANCIENT_DEBRIS"),
    PLACED_STONE(DetailCategory.BLOCKS_PLACED, "STONE"),
    PLACED_COBBLESTONE(DetailCategory.BLOCKS_PLACED, "COBBLESTONE"),
    PLACED_OAK_PLANKS(DetailCategory.BLOCKS_PLACED, "OAK_PLANKS"),
    PLACED_GLASS(DetailCategory.BLOCKS_PLACED, "GLASS"),
    PLACED_DIRT(DetailCategory.BLOCKS_PLACED, "DIRT"),
    PLACED_OBSIDIAN(DetailCategory.BLOCKS_PLACED, "OBSIDIAN"),
    KILLED_ZOMBIE(DetailCategory.MOBS_KILLED, "ZOMBIE"),
    KILLED_SKELETON(DetailCategory.MOBS_KILLED, "SKELETON"),
    KILLED_CREEPER(DetailCategory.MOBS_KILLED, "CREEPER"),
    KILLED_SPIDER(DetailCategory.MOBS_KILLED, "SPIDER"),
    KILLED_ENDERMAN(DetailCategory.MOBS_KILLED, "ENDERMAN"),
    KILLED_WITCH(DetailCategory.MOBS_KILLED, "WITCH"),
    KILLED_BLAZE(DetailCategory.MOBS_KILLED, "BLAZE"),
    KILLED_WITHER_SKELETON(DetailCategory.MOBS_KILLED, "WITHER_SKELETON"),
    KILLED_SLIME(DetailCategory.MOBS_KILLED, "SLIME"),
    KILLED_DROWNED(DetailCategory.MOBS_KILLED, "DROWNED"),
    CAUGHT_ITEMS(DetailCategory.FISH_CAUGHT, ""),
    CAUGHT_COD(DetailCategory.FISH_CAUGHT, "COD"),
    CAUGHT_SALMON(DetailCategory.FISH_CAUGHT, "SALMON"),
    CAUGHT_PUFFERFISH(DetailCategory.FISH_CAUGHT, "PUFFERFISH"),
    CAUGHT_TROPICAL_FISH(DetailCategory.FISH_CAUGHT, "TROPICAL_FISH"),
    BRED_ANIMALS(DetailCategory.ANIMALS_BRED, ""),
    BRED_COW(DetailCategory.ANIMALS_BRED, "COW"),
    BRED_SHEEP(DetailCategory.ANIMALS_BRED, "SHEEP"),
    BRED_PIG(DetailCategory.ANIMALS_BRED, "PIG"),
    BRED_CHICKEN(DetailCategory.ANIMALS_BRED, "CHICKEN"),
    CRAFTED_ITEMS(DetailCategory.ITEMS_CRAFTED, ""),
    CRAFTED_BREAD(DetailCategory.ITEMS_CRAFTED, "BREAD"),
    CRAFTED_TORCH(DetailCategory.ITEMS_CRAFTED, "TORCH"),
    CRAFTED_OAK_PLANKS(DetailCategory.ITEMS_CRAFTED, "OAK_PLANKS"),
    CRAFTED_IRON_PICKAXE(DetailCategory.ITEMS_CRAFTED, "IRON_PICKAXE"),
    TRADED_ITEMS(DetailCategory.ITEMS_TRADED, ""),
    TRADED_EMERALD(DetailCategory.ITEMS_TRADED, "EMERALD"),
    TRADED_BREAD(DetailCategory.ITEMS_TRADED, "BREAD"),
    TRADED_ENCHANTED_BOOK(DetailCategory.ITEMS_TRADED, "ENCHANTED_BOOK"),
    TRADED_DIAMOND_PICKAXE(DetailCategory.ITEMS_TRADED, "DIAMOND_PICKAXE");

    private static final Map<String, DetailKey> IDS;
    private static final Map<DetailCategory, Map<String, DetailKey>> TARGETS;

    static {
        Map<String, DetailKey> ids = new HashMap<>();
        Map<DetailCategory, Map<String, DetailKey>> targets = new EnumMap<>(DetailCategory.class);
        for (DetailKey key : values()) {
            ids.put(key.id(), key);
            targets.computeIfAbsent(key.category(), ignored -> new HashMap<>()).put(key.target(), key);
        }
        IDS = Map.copyOf(ids);
        Map<DetailCategory, Map<String, DetailKey>> immutableTargets = new EnumMap<>(DetailCategory.class);
        targets.forEach((category, entries) -> immutableTargets.put(category, Map.copyOf(entries)));
        TARGETS = Collections.unmodifiableMap(immutableTargets);
    }

    private final DetailCategory category;
    private final String target;
    private final String id;
    private final String displayName;

    DetailKey(DetailCategory category, String target) {
        this.category = category;
        this.target = target;
        id = name().toLowerCase(Locale.ROOT);
        displayName = describe(category, target);
    }

    public static Optional<DetailKey> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(IDS.get(id.toLowerCase(Locale.ROOT)));
    }

    public static Optional<DetailKey> find(DetailCategory category, String target) {
        if (category == null || target == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(TARGETS.get(category).get(target.toUpperCase(Locale.ROOT)));
    }

    public String id() {
        return id;
    }

    public DetailCategory category() {
        return category;
    }

    public String target() {
        return target;
    }

    public String displayName() {
        return displayName;
    }

    private static String describe(DetailCategory category, String target) {
        String subject = target.isEmpty() ? category == DetailCategory.ANIMALS_BRED ? "Animals" : "Items"
                : target.substring(0, 1) + target.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        return subject + switch (category) {
            case BLOCKS_MINED -> " mined";
            case BLOCKS_PLACED -> " placed";
            case MOBS_KILLED -> " killed";
            case FISH_CAUGHT -> " caught";
            case ANIMALS_BRED -> " bred";
            case ITEMS_CRAFTED -> " crafted";
            case ITEMS_TRADED -> " traded";
        };
    }
}
