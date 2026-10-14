package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import com.volmit.staticstats.detailed.DetailCategory;
import com.volmit.staticstats.detailed.DetailKey;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DetailMessages {
    public static final TextKey TITLE = TextKey.of("details.title", "&#663D12Details &8| &#333333{player}");
    public static final TextKey HEADER = TextKey.of("details.header", "<gradient:#FFB454:#FFE39C>{player}'s detailed counters</gradient> &7(page {page}/{pages})");
    public static final TextKey OPEN_HINT = TextKey.of("details.open_hint", "&7Left-click for lifetime material and entity counters.");
    public static final TextKey LIFETIME = TextKey.of("details.lifetime", "&7Lifetime observations · separate from calendar and event totals");
    public static final TextKey COUNT = TextKey.of("details.count", "&7Recorded count: &#FFE39C{value}");
    public static final TextKey ENTRY = TextKey.of("details.entry", "&#F2E7D5{counter}&8: &#FFE39C{value}");
    public static final TextKey FIRST_OBSERVED = TextKey.of("details.first_observed", "&7First counted observation: {date}");
    public static final TextKey UNOBSERVED = TextKey.of("details.unobserved", "&7No counted observation yet.");
    public static final TextKey COVERAGE = TextKey.of("details.coverage", "&7Counts begin when observed; earlier activity is not recovered.");
    public static final TextKey RECORDING = TextKey.of("details.recording", "&7Selected for recording under current tracking rules.");
    public static final TextKey RETAINED = TextKey.of("details.retained", "&7Recording is off for this counter; saved counts remain.");
    public static final TextKey ENABLED = TextKey.of("details.enabled", "&7Detailed recording is enabled.");
    public static final TextKey DISABLED = TextKey.of("details.disabled", "&7Detailed recording is disabled; saved counts remain.");
    public static final TextKey EMPTY = TextKey.of("details.empty", "&7No counted observations in this view.");
    public static final TextKey EMPTY_HINT = TextKey.of("details.empty_hint", "&7Show selected counters to include zero counts.");
    public static final TextKey CATEGORY = TextKey.of("details.category", "&#FFE39CCategory: {category}");
    public static final TextKey CATEGORY_HINT = TextKey.of("details.category_hint", "&7Click to cycle detailed counter categories.");
    public static final TextKey ALL = TextKey.of("details.category.all", "All categories");
    public static final TextKey RECORDED = TextKey.of("details.filter.recorded", "&#FFE39CShowing counted observations");
    public static final TextKey SELECTED = TextKey.of("details.filter.selected", "&#FFE39CShowing selected and saved counters");
    public static final TextKey SHOW_SELECTED = TextKey.of("details.filter.show_selected", "&7Click to include selected counters with zero counts.");
    public static final TextKey HIDE_ZERO = TextKey.of("details.filter.hide_zero", "&7Click to hide zero counts.");
    public static final TextKey BACK = TextKey.of("details.back", "&#FFE39CBack to lifetime profile");
    public static final TextKey UNKNOWN_CATEGORY = TextKey.of("details.unknown_category", "{prefix}&cUnknown detail category: {category}. Use tab completion to choose one.");
    public static final TextKey INVALID_PAGE = TextKey.of("details.invalid_page", "{prefix}&cThat page is outside the available detail counters.");
    public static final TextKey FAILED = TextKey.of("details.failed", "{prefix}&cDetailed counters could not be loaded. Details are in the console.");
    public static final TextKey DESCRIPTION = TextKey.of("command.description.details", "View lifetime material, entity, and activity counters");
    public static final TextKey PARAM_CATEGORY = TextKey.of("command.parameter.detail_category", "Detailed counter category; all by default");
    public static final TextKey PARAM_PAGE = TextKey.of("command.parameter.detail_page", "One-based detailed counters page");
    public static final TextKey CONFIG_CATEGORY = TextKey.of("gui.category.details", "&#FFB454Detailed counters&r");
    public static final TextKey CONFIG_ENABLED = TextKey.of("gui.setting.details.enabled", "&#FFB454Record detailed counters&r");
    public static final TextKey CONFIG_KEYS = TextKey.of("gui.setting.details.selected_keys", "&#FFB454Selected detailed counter identifiers&r");
    public static final TextKey CONFIG_KEYS_HINT = TextKey.of("details.config_keys_hint", "&7Comma-separated IDs replace the selection. Use +id or -id to add or remove.");
    public static final TextKey TOP_HINT = TextKey.of("details.top.hint", "&7Click to view lifetime rankings for this counter.");
    public static final TextKey TOP_SCOPE = TextKey.of("details.top.scope", "&7Lifetime detailed counts · {ranked}/{total} profiles with positive observations");
    public static final TextKey TOP_FIRST_OBSERVED = TextKey.of("details.top.first_observed", "&7Earliest observation among ranked profiles: {date}");
    public static final TextKey TOP_EMPTY = TextKey.of("details.top.empty", "&7No positive recorded counts for this counter yet.");
    public static final TextKey TOP_UNRANKED = TextKey.of("details.top.unranked", "&7You have no positive recorded count for this counter in this snapshot.");
    public static final TextKey TOP_LOADING = TextKey.of("details.top.loading", "{prefix}&7Loading detailed rankings...");
    public static final TextKey TOP_WARMING = TextKey.of("details.top.warming", "{prefix}&7Detailed rankings are loading. Run this command again shortly.");
    public static final TextKey UNKNOWN_COUNTER = TextKey.of("details.unknown_counter", "{prefix}&cUnknown detailed counter: {counter}. Use tab completion to choose one.");
    public static final TextKey TOP_DESCRIPTION = TextKey.of("command.description.detailtop", "Show lifetime rankings for one detailed counter");
    public static final TextKey PARAM_COUNTER = TextKey.of("command.parameter.detail_counter", "Detailed counter identifier");
    private static final Map<DetailKey, TextKey> LABELS = labels();
    private static final Map<DetailCategory, TextKey> CATEGORIES = categories();

    private DetailMessages() {
    }

    public static TextKey label(DetailKey key) {
        return LABELS.get(key);
    }

    public static TextKey category(DetailCategory category) {
        return category == null ? ALL : CATEGORIES.get(category);
    }

    public static List<MessageKey> keys() {
        List<MessageKey> keys = new ArrayList<>(List.of(TITLE, HEADER, OPEN_HINT, LIFETIME, COUNT, ENTRY,
                FIRST_OBSERVED, UNOBSERVED, COVERAGE, RECORDING, RETAINED, ENABLED, DISABLED, EMPTY, EMPTY_HINT,
                CATEGORY, CATEGORY_HINT, ALL, RECORDED, SELECTED, SHOW_SELECTED, HIDE_ZERO, BACK, UNKNOWN_CATEGORY,
                INVALID_PAGE, FAILED, DESCRIPTION, PARAM_CATEGORY, PARAM_PAGE, CONFIG_CATEGORY, CONFIG_ENABLED, CONFIG_KEYS, CONFIG_KEYS_HINT,
                TOP_HINT, TOP_SCOPE, TOP_FIRST_OBSERVED, TOP_EMPTY, TOP_UNRANKED, TOP_LOADING, TOP_WARMING, UNKNOWN_COUNTER, TOP_DESCRIPTION, PARAM_COUNTER));
        keys.addAll(CATEGORIES.values());
        keys.addAll(LABELS.values());
        return List.copyOf(keys);
    }

    private static Map<DetailKey, TextKey> labels() {
        Map<DetailKey, TextKey> labels = new EnumMap<>(DetailKey.class);
        for (DetailKey key : DetailKey.values()) {
            labels.put(key, TextKey.of("details.counter." + key.id(), key.displayName()));
        }
        return Map.copyOf(labels);
    }

    private static Map<DetailCategory, TextKey> categories() {
        Map<DetailCategory, TextKey> categories = new EnumMap<>(DetailCategory.class);
        for (DetailCategory category : DetailCategory.values()) {
            String english = switch (category) {
                case BLOCKS_MINED -> "Blocks mined";
                case BLOCKS_PLACED -> "Blocks placed";
                case MOBS_KILLED -> "Mobs killed";
                case FISH_CAUGHT -> "Fishing";
                case ANIMALS_BRED -> "Breeding";
                case ITEMS_CRAFTED -> "Crafting";
                case ITEMS_TRADED -> "Trading";
            };
            categories.put(category, TextKey.of("details.category." + category.name().toLowerCase(Locale.ROOT), english));
        }
        return Map.copyOf(categories);
    }
}
