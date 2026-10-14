package com.volmit.staticstats.gui;

import com.volmit.staticstats.localization.DashboardMessages;

import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.MilestoneMessages;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.localization.SeasonMessages;
import com.volmit.staticstats.localization.DetailMessages;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.presentation.PresentationService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class ConfigEditorGui implements Listener {
    private static final int SIZE = 54;
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 53;
    private static final int[] CATEGORY_SLOTS = {20, 22, 24, 29, 31, 33, 39, 41, 43, 11, 12};
    private static final long PROMPT_TICKS = 20L * 60L;
    private static final int MAXIMUM_INPUT_LENGTH = 512;

    private final StaticPlugin plugin;
    private final ConfigService configService;
    private final LanguageService language;
    private final PresentationService presentation;
    private final List<Setting> settings;
    private final Map<UUID, PromptSession> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitInventoryShutdown.View> openInventories = new ConcurrentHashMap<>();
    private final AtomicLong promptIds = new AtomicLong();
    private final ExecutorService writer;
    private volatile boolean closed;

    public ConfigEditorGui(Dependencies dependencies) {
        this.plugin = dependencies.plugin();
        this.configService = dependencies.configService();
        this.language = dependencies.language();
        this.presentation = dependencies.presentation();
        settings = createSettings();
        writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Static-Config-Editor");
            thread.setDaemon(true);
            return thread;
        });
    }

    public record Dependencies(StaticPlugin plugin, ConfigService configService,
                               LanguageService language, PresentationService presentation) {
    }

    public void open(Player player) {
        FoliaScheduler.runEntity(plugin, player, () -> openRootOwned(player));
    }

    public void shutdown() {
        closed = true;
        prompts.clear();
        writer.shutdownNow();
        BukkitInventoryShutdown.drain(plugin, openInventories.values());
        openInventories.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(BukkitInventoryViews.top(event.getView()).getHolder() instanceof EditorHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= SIZE) {
            return;
        }
        if (!player.hasPermission("static.config")) {
            player.closeInventory();
            presentation.command(player, StaticMessages.NO_PERMISSION, FeedbackTone.FAILURE);
            return;
        }

        LanguageAudience.run(player.getUniqueId(), () -> handleClick(player, holder, event));
    }

    private void handleClick(Player player, EditorHolder holder, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (holder.category() == null) {
            Category category = categoryAt(slot);
            if (category != null) {
                if (category == Category.LANGUAGES) {
                    plugin.getLanguageSwitcher().openEditor(player, this::openRootOwned);
                } else if (category == Category.SEASONS) {
                    plugin.getSeasonEditor().open(player);
                } else {
                    openCategoryOwned(player, category);
                }
            }
            return;
        }
        if (slot == BACK_SLOT) {
            openRootOwned(player);
            return;
        }

        List<Setting> categorySettings = settings(holder.category());
        if (slot >= categorySettings.size()) {
            return;
        }
        Setting setting = categorySettings.get(slot);
        handleSettingClick(player, holder.category(), setting, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (BukkitInventoryViews.top(event.getView()).getHolder() instanceof EditorHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PromptSession prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String input = event.getMessage();
        FoliaScheduler.runEntity(plugin, player, () -> processPrompt(player, prompt, input), 0L,
                () -> prompts.remove(player.getUniqueId(), prompt));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        prompts.remove(playerId);
        openInventories.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof EditorHolder) {
            openInventories.computeIfPresent(event.getPlayer().getUniqueId(), (id, view) ->
                    view.inventory() == event.getInventory() ? null : view);
        }
    }

    private void openRootOwned(Player player) {
        if (!closed) {
            LanguageAudience.run(player.getUniqueId(), () -> renderRootOwned(player));
        }
    }

    private void renderRootOwned(Player player) {
        prompts.remove(player.getUniqueId());
        EditorHolder holder = EditorHolder.root();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, language.legacy(StaticMessages.GUI_ROOT_TITLE));
        holder.setInventory(inventory);
        fill(inventory);
        Category[] categories = Category.values();
        for (int index = 0; index < categories.length; index++) {
            Category category = categories[index];
            inventory.setItem(CATEGORY_SLOTS[index], item(
                    category.material(),
                    language.legacy(category.displayName()),
                    List.of(language.legacy(StaticMessages.GUI_CATEGORY_OPEN))
            ));
        }
        navigation(inventory, false);
        player.openInventory(inventory);
        openInventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, inventory));
    }

    private void openCategoryOwned(Player player, Category category) {
        if (!closed) {
            LanguageAudience.run(player.getUniqueId(), () -> renderCategoryOwned(player, category));
        }
    }

    private void renderCategoryOwned(Player player, Category category) {
        EditorHolder holder = EditorHolder.category(category);
        MessageArgs titleArguments = MessageArgs.builder()
                .untrusted("category", ComponentText.markup(language.render(category.displayName())).plain())
                .build();
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                language.legacy(StaticMessages.GUI_CATEGORY_TITLE, titleArguments)
        );
        holder.setInventory(inventory);
        fill(inventory);
        StaticConfig config = configService.editableCopy();
        List<Setting> categorySettings = settings(category);
        for (int slot = 0; slot < categorySettings.size(); slot++) {
            inventory.setItem(slot, settingItem(categorySettings.get(slot), config));
        }
        navigation(inventory, true);
        player.openInventory(inventory);
        openInventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, inventory));
    }

    private void navigation(Inventory inventory, boolean back) {
        if (back) {
            inventory.setItem(BACK_SLOT, item(Material.ARROW, language.legacy(StaticMessages.GUI_BACK), List.of()));
        }
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, language.legacy(StaticMessages.GUI_CLOSE), List.of()));
    }

    private void handleSettingClick(Player player, Category category, Setting setting, InventoryClickEvent event) {
        if (setting.kind() == SettingKind.LOCALE) {
            player.closeInventory();
            plugin.getLanguageSwitcher().command(player, new String[]{"server"});
            return;
        }
        if (setting.kind() == SettingKind.BOOLEAN) {
            saveMutation(player, category, setting, candidate -> {
                boolean current = Boolean.parseBoolean(setting.reader().apply(candidate));
                setting.writer().write(candidate, Boolean.toString(!current));
            });
            return;
        }
        if (setting.kind().numeric() && !isPromptClick(event.getClick())) {
            double direction = event.isRightClick() ? -1D : 1D;
            double multiplier = event.isShiftClick() ? 10D : 1D;
            double adjustment = direction * multiplier;
            saveMutation(player, category, setting, candidate -> {
                String current = setting.reader().apply(candidate);
                setting.writer().write(candidate, adjust(setting, current, adjustment));
            });
            return;
        }
        beginPrompt(player, category, setting);
    }

    private void beginPrompt(Player player, Category category, Setting setting) {
        long id = promptIds.incrementAndGet();
        PromptSession prompt = new PromptSession(id, category, setting);
        prompts.put(player.getUniqueId(), prompt);
        player.closeInventory();
        MessageArgs arguments = MessageArgs.builder().untrusted("setting", plainName(setting)).build();
        language.sendPrefixed(player, StaticMessages.GUI_PROMPT, arguments);
        if (setting.kind() == SettingKind.LIST) {
            language.sendPrefixed(player, StaticMessages.GUI_PROMPT_LIST);
        }
        if (setting.path().equals("details.selectedKeys")) {
            language.sendPrefixed(player, DetailMessages.CONFIG_KEYS_HINT);
        }
        language.sendPrefixed(player, StaticMessages.GUI_PROMPT_CANCEL);
        boolean scheduled = FoliaScheduler.runEntity(plugin, player,
                () -> expirePrompt(player, prompt), PROMPT_TICKS,
                () -> prompts.remove(player.getUniqueId(), prompt));
        if (!scheduled) {
            prompts.remove(player.getUniqueId(), prompt);
            presentation.command(player, StaticMessages.GUI_PROMPT_CANCELLED, FeedbackTone.FAILURE);
        }
    }

    private void processPrompt(Player player, PromptSession prompt, String input) {
        if (!player.hasPermission("static.config")) {
            presentation.command(player, StaticMessages.NO_PERMISSION, FeedbackTone.FAILURE);
            return;
        }
        if (input.equalsIgnoreCase("cancel")) {
            presentation.command(player, StaticMessages.GUI_PROMPT_CANCELLED, FeedbackTone.INFO);
            openCategoryOwned(player, prompt.category());
            return;
        }
        if (input.length() > MAXIMUM_INPUT_LENGTH) {
            saveFailedOwned(player, prompt.category(), prompt.setting(),
                    "the value is longer than 512 characters");
            return;
        }
        save(player, prompt.category(), prompt.setting(), input);
    }

    private void expirePrompt(Player player, PromptSession prompt) {
        if (!prompts.remove(player.getUniqueId(), prompt)) {
            return;
        }
        presentation.command(player, StaticMessages.GUI_PROMPT_TIMEOUT, FeedbackTone.INFO);
        openCategoryOwned(player, prompt.category());
    }

    private void save(Player player, Category category, Setting setting, String input) {
        saveMutation(player, category, setting, config -> setting.writer().write(config, input));
    }

    private void saveMutation(
            Player player,
            Category category,
            Setting setting,
            Consumer<StaticConfig> mutation
    ) {
        SaveOperation operation = new SaveOperation(player, category, setting, mutation);
        submitSave(operation);
    }

    private void submitSave(SaveOperation operation) {
        try {
            writer.execute(() -> saveOffThread(operation));
        } catch (RejectedExecutionException exception) {
            scheduleFailure(operation, "the editor is shutting down");
        }
    }

    private void saveOffThread(SaveOperation operation) {
        if (closed) {
            return;
        }
        try {
            String previousValue = operation.setting().reader().apply(configService.editableCopy());
            plugin.applyConfigurationEdit(operation.mutation(), null);
            String appliedValue = operation.setting().reader().apply(configService.editableCopy());
            scheduleResult(operation.player(), () -> {
                MessageArgs arguments = MessageArgs.builder()
                        .untrusted("setting", plainName(operation.setting()))
                        .untrusted("old", displayValue(previousValue))
                        .untrusted("new", displayValue(appliedValue))
                        .build();
                sendConfigResult(operation.player(), StaticMessages.CONFIG_SAVED, arguments);
                openDestination(operation);
            });
        } catch (IOException | RuntimeException exception) {
            saveFailed(operation, exception);
        }
    }

    private void saveFailed(SaveOperation operation, Exception exception) {
        plugin.getLogger().log(Level.SEVERE,
                "Failed to save Static setting " + operation.setting().path(), exception);
        scheduleFailure(operation, reason(exception));
    }

    private void scheduleResult(Player player, Runnable result) {
        FoliaScheduler.runEntity(plugin, player, () -> LanguageAudience.run(player.getUniqueId(), result), 0L,
                () -> prompts.remove(player.getUniqueId()));
    }

    private void scheduleFailure(SaveOperation operation, String failure) {
        scheduleResult(operation.player(), () -> saveFailedOwned(
                operation.player(),
                operation.category(),
                plainName(operation.setting()),
                failure
        ));
    }

    private void saveFailedOwned(
            Player player,
            Category category,
            Setting setting,
            String reason
    ) {
        saveFailedOwned(player, category, plainName(setting), reason);
    }

    private void saveFailedOwned(
            Player player,
            Category category,
            String settingName,
            String reason
    ) {
        MessageArgs arguments = MessageArgs.builder()
                .untrusted("setting", settingName)
                .untrusted("reason", reason)
                .build();
        sendConfigResult(player, StaticMessages.CONFIG_SAVE_FAILED, arguments);
        openCategoryOwned(player, category);
    }

    private void sendConfigResult(Player player, TextKey message, MessageArgs arguments) {
        language.sendPrefixed(player, message, arguments);
    }

    private void openDestination(SaveOperation operation) {
        openCategoryOwned(operation.player(), operation.category());
    }

    private ItemStack settingItem(Setting setting, StaticConfig config) {
        String value = displayValue(setting.reader().apply(config));
        MessageArgs valueArguments;
        if (setting.kind() == SettingKind.BOOLEAN) {
            boolean enabled = Boolean.parseBoolean(value);
            valueArguments = MessageArgs.builder().trusted("value",
                    language.render(enabled ? StaticMessages.GUI_ENABLED : StaticMessages.GUI_DISABLED)).build();
        } else {
            valueArguments = MessageArgs.builder().untrusted("value", value).build();
        }
        TextKey instruction = switch (setting.kind()) {
            case BOOLEAN -> StaticMessages.GUI_TOGGLE;
            case INTEGER, LONG, DECIMAL -> StaticMessages.GUI_NUMBER;
            case TEXT, LIST -> StaticMessages.GUI_TEXT;
            case LOCALE -> StaticMessages.GUI_LANGUAGE_SELECT;
        };
        List<String> lore = new ArrayList<>(List.of(language.legacy(StaticMessages.GUI_STATE, valueArguments), language.legacy(instruction)));
        if (setting.path().equals("details.selectedKeys")) {
            lore.add(language.legacy(DetailMessages.CONFIG_KEYS_HINT));
        }
        if (setting.path().startsWith("milestones.thresholds.")) {
            lore.add(language.legacy(MilestoneMessages.TARGET_HINT));
            StatKey statistic = StatKey.parse(setting.path().substring("milestones.thresholds.".length()));
            if (statistic == StatKey.ONLINE_TIME || statistic == StatKey.ACTIVE_TIME || statistic == StatKey.AFK_TIME) {
                lore.add(language.legacy(MilestoneMessages.TIME_HINT));
            }
        }
        return item(setting.material(), language.legacy(setting.name()), lore);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(name);
        meta.setLore(new ArrayList<>(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inventory) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private List<Setting> settings(Category category) {
        return settings.stream().filter(setting -> setting.category() == category).toList();
    }

    private static Category categoryAt(int slot) {
        Category[] categories = Category.values();
        for (int index = 0; index < CATEGORY_SLOTS.length; index++) {
            if (CATEGORY_SLOTS[index] == slot) {
                return categories[index];
            }
        }
        return null;
    }

    private boolean isPromptClick(ClickType click) {
        return click == ClickType.DROP || click == ClickType.CONTROL_DROP;
    }

    private String adjust(Setting setting, String current, double multiplier) {
        double adjusted = Double.parseDouble(current) + setting.step() * multiplier;
        return switch (setting.kind()) {
            case INTEGER -> Integer.toString((int) Math.round(adjusted));
            case LONG -> Long.toString(Math.round(adjusted));
            case DECIMAL -> Double.toString(adjusted);
            default -> current;
        };
    }

    private String plainName(Setting setting) {
        return ComponentText.markup(language.render(setting.name())).plain();
    }

    private String displayValue(String value) {
        if (value.isBlank()) {
            return "(empty)";
        }
        return value.length() <= 120 ? value : value.substring(0, 117) + "…";
    }

    private String reason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "validation or disk write failed; see the console";
        }
        return message;
    }

    static Set<String> editablePaths() {
        Set<String> paths = createSettings().stream().map(Setting::path).collect(Collectors.toSet());
        paths.add("seasons.events");
        return Set.copyOf(paths);
    }

    static boolean languageUsesPicker() {
        return createSettings().stream()
                .anyMatch(setting -> setting.path().equals("general.language")
                        && setting.kind() == SettingKind.LOCALE);
    }

    static int inventorySize() {
        return SIZE;
    }

    static Map<Integer, String> rootCategorySlots() {
        Category[] categories = Category.values();
        LinkedHashMap<Integer, String> slots = new LinkedHashMap<>();
        for (int index = 0; index < CATEGORY_SLOTS.length; index++) {
            slots.put(CATEGORY_SLOTS[index], categories[index].name());
        }
        return Map.copyOf(slots);
    }

    static Set<Integer> navigationSlots() {
        return Set.of(BACK_SLOT, CLOSE_SLOT);
    }

    static int maximumCategorySize() {
        int maximum = 0;
        for (Category category : Category.values()) {
            int size = (int) createSettings().stream().filter(setting -> setting.category() == category).count();
            maximum = Math.max(maximum, size);
        }
        return maximum;
    }

    private static List<Setting> createSettings() {
        List<Setting> settings = new ArrayList<>(List.of(
                setting(Category.GENERAL, "general.enabled", StaticMessages.SETTING_GENERAL_ENABLED,
                        Material.WRITABLE_BOOK, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.general.enabled),
                        (config, value) -> config.general.enabled = parseBoolean(value)),
                setting(Category.GENERAL, "general.language", StaticMessages.SETTING_GENERAL_LANGUAGE,
                        Material.WRITABLE_BOOK, SettingKind.LOCALE, 1D,
                        config -> config.general.language,
                        (config, value) -> config.general.language = value.trim()),
                setting(Category.GENERAL, "dashboard.enabled", DashboardMessages.SETTING_ENABLED,
                        Material.COMPARATOR, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.dashboard.enabled),
                        (config, value) -> config.dashboard.enabled = parseBoolean(value.trim())),
                setting(Category.GENERAL, "dashboard.publicProfilesEnabled", DashboardMessages.SETTING_PUBLIC_PROFILES,
                        Material.COMPARATOR, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.dashboard.publicProfilesEnabled),
                        (config, value) -> config.dashboard.publicProfilesEnabled = parseBoolean(value.trim())),
                setting(Category.GENERAL, "dashboard.port", DashboardMessages.SETTING_PORT,
                        Material.COMPARATOR, SettingKind.INTEGER, 1D,
                        config -> Integer.toString(config.dashboard.port),
                        (config, value) -> config.dashboard.port = Integer.parseInt(value.trim())),
                setting(Category.GENERAL, "dashboard.publicOrigin", DashboardMessages.SETTING_ORIGIN,
                        Material.COMPASS, SettingKind.TEXT, 1D,
                        config -> config.dashboard.publicOrigin,
                        (config, value) -> config.dashboard.publicOrigin = value.trim()),
                setting(Category.TRACKING, "tracking.trackSpectator", StaticMessages.SETTING_TRACKING_SPECTATOR,
                        Material.SPYGLASS, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.tracking.trackSpectator),
                        (config, value) -> config.tracking.trackSpectator = parseBoolean(value.trim())),
                setting(Category.TRACKING, "tracking.trackCreative", StaticMessages.SETTING_TRACKING_CREATIVE,
                        Material.COMMAND_BLOCK, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.tracking.trackCreative),
                        (config, value) -> config.tracking.trackCreative = parseBoolean(value.trim())),
                setting(Category.TRACKING, "tracking.excludedWorlds", StaticMessages.SETTING_TRACKING_EXCLUDED_WORLDS,
                        Material.GRASS_BLOCK, SettingKind.LIST, 1D,
                        config -> join(config.tracking.excludedWorlds),
                        (config, value) -> config.tracking.excludedWorlds = parseList(value.trim())),
                setting(Category.TRACKING, "tracking.leaderboardLimit", StaticMessages.SETTING_TRACKING_LEADERBOARD,
                        Material.GOLD_INGOT, SettingKind.INTEGER, 1D,
                        config -> Integer.toString(config.tracking.leaderboardLimit),
                        (config, value) -> config.tracking.leaderboardLimit = Integer.parseInt(value.trim())),
                setting(Category.TRACKING, "tracking.autosaveSeconds", StaticMessages.SETTING_TRACKING_AUTOSAVE,
                        Material.CLOCK, SettingKind.INTEGER, 1D,
                        config -> Integer.toString(config.tracking.autosaveSeconds),
                        (config, value) -> config.tracking.autosaveSeconds = Integer.parseInt(value.trim())),
                setting(Category.RANKING, "ranking.minPvpDeaths", StaticMessages.SETTING_RANKING_PVP_DEATHS,
                        Material.IRON_SWORD, SettingKind.DECIMAL, 1D,
                        config -> Double.toString(config.ranking.minPvpDeaths),
                        (config, value) -> config.ranking.minPvpDeaths = Double.parseDouble(value.trim())),
                setting(Category.RANKING, "ranking.minMeleeDamageTaken", StaticMessages.SETTING_RANKING_MELEE_DAMAGE,
                        Material.SHIELD, SettingKind.DECIMAL, 1D,
                        config -> Double.toString(config.ranking.minMeleeDamageTaken),
                        (config, value) -> config.ranking.minMeleeDamageTaken = Double.parseDouble(value.trim())),
                setting(Category.RANKING, "ranking.minMeleeHitsTaken", StaticMessages.SETTING_RANKING_MELEE_HITS,
                        Material.IRON_CHESTPLATE, SettingKind.DECIMAL, 1D,
                        config -> Double.toString(config.ranking.minMeleeHitsTaken),
                        (config, value) -> config.ranking.minMeleeHitsTaken = Double.parseDouble(value.trim())),
                setting(Category.RANKING, "ranking.minArrowDamageTaken", StaticMessages.SETTING_RANKING_ARROW_DAMAGE,
                        Material.TARGET, SettingKind.DECIMAL, 1D,
                        config -> Double.toString(config.ranking.minArrowDamageTaken),
                        (config, value) -> config.ranking.minArrowDamageTaken = Double.parseDouble(value.trim())),
                setting(Category.RANKING, "ranking.minArrowHitsTaken", StaticMessages.SETTING_RANKING_ARROW_HITS,
                        Material.ARROW, SettingKind.DECIMAL, 1D,
                        config -> Double.toString(config.ranking.minArrowHitsTaken),
                        (config, value) -> config.ranking.minArrowHitsTaken = Double.parseDouble(value.trim())),
                setting(Category.RANKING, "ranking.minArrowsFired", StaticMessages.SETTING_RANKING_ARROWS_FIRED,
                        Material.BOW, SettingKind.DECIMAL, 1D,
                        config -> Double.toString(config.ranking.minArrowsFired),
                        (config, value) -> config.ranking.minArrowsFired = Double.parseDouble(value.trim())),
                setting(Category.RANKING, "ranking.minOnlineHours", StaticMessages.SETTING_RANKING_ONLINE_HOURS,
                        Material.CLOCK, SettingKind.DECIMAL, 1D,
                        config -> Double.toString(config.ranking.minOnlineHours),
                        (config, value) -> config.ranking.minOnlineHours = Double.parseDouble(value.trim())),
                setting(Category.HISTORY, "history.timeZone", StaticMessages.SETTING_HISTORY_ZONE,
                        Material.CLOCK, SettingKind.TEXT, 1D,
                        config -> config.history.timeZone,
                        (config, value) -> config.history.timeZone = value.trim()),
                setting(Category.HISTORY, "history.afkSeconds", StaticMessages.SETTING_HISTORY_AFK,
                        Material.RED_BED, SettingKind.INTEGER, 10D,
                        config -> Integer.toString(config.history.afkSeconds),
                        (config, value) -> config.history.afkSeconds = Integer.parseInt(value.trim())),
                setting(Category.HISTORY, "history.retainedDays", StaticMessages.SETTING_HISTORY_DAYS,
                        Material.SUNFLOWER, SettingKind.INTEGER, 1D,
                        config -> Integer.toString(config.history.retainedDays),
                        (config, value) -> config.history.retainedDays = Integer.parseInt(value.trim())),
                setting(Category.HISTORY, "history.retainedWeeks", StaticMessages.SETTING_HISTORY_WEEKS,
                        Material.PAPER, SettingKind.INTEGER, 1D,
                        config -> Integer.toString(config.history.retainedWeeks),
                        (config, value) -> config.history.retainedWeeks = Integer.parseInt(value.trim())),
                setting(Category.HISTORY, "history.retainedMonths", StaticMessages.SETTING_HISTORY_MONTHS,
                        Material.BOOK, SettingKind.INTEGER, 1D,
                        config -> Integer.toString(config.history.retainedMonths),
                        (config, value) -> config.history.retainedMonths = Integer.parseInt(value.trim())),
                setting(Category.HISTORY, "history.sessionsPerPlayer", StaticMessages.SETTING_HISTORY_SESSIONS,
                        Material.WRITABLE_BOOK, SettingKind.INTEGER, 1D,
                        config -> Integer.toString(config.history.sessionsPerPlayer),
                        (config, value) -> config.history.sessionsPerPlayer = Integer.parseInt(value.trim())),
                setting(Category.HOT_RELOAD, "hotReload.enabled", StaticMessages.SETTING_HOT_RELOAD_ENABLED,
                        Material.COMPARATOR, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.hotReload.enabled),
                        (config, value) -> config.hotReload.enabled = parseBoolean(value)),
                setting(Category.HOT_RELOAD, "hotReload.pollIntervalMillis", StaticMessages.SETTING_HOT_RELOAD_POLL,
                        Material.CLOCK, SettingKind.LONG, 100D,
                        config -> Long.toString(config.hotReload.pollIntervalMillis),
                        (config, value) -> config.hotReload.pollIntervalMillis = Long.parseLong(value.trim())),
                setting(Category.HOT_RELOAD, "hotReload.cooldownMillis", StaticMessages.SETTING_HOT_RELOAD_COOLDOWN,
                        Material.REDSTONE_TORCH, SettingKind.LONG, 100D,
                        config -> Long.toString(config.hotReload.cooldownMillis),
                        (config, value) -> config.hotReload.cooldownMillis = Long.parseLong(value.trim())),
                setting(Category.HOT_RELOAD, "hotReload.notifyOperators", StaticMessages.SETTING_HOT_RELOAD_NOTIFY,
                        Material.BELL, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.hotReload.notifyOperators),
                        (config, value) -> config.hotReload.notifyOperators = parseBoolean(value)),
                setting(Category.PRESENTATION, "presentation.splashScreen", StaticMessages.SETTING_PRESENTATION_SPLASH,
                        Material.FIREWORK_STAR, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.presentation.splashScreen),
                        (config, value) -> config.presentation.splashScreen = parseBoolean(value)),
                setting(Category.PRESENTATION, "presentation.commandSounds", StaticMessages.SETTING_PRESENTATION_SOUNDS,
                        Material.MUSIC_DISC_CAT, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.presentation.commandSounds),
                        (config, value) -> config.presentation.commandSounds = parseBoolean(value)),
                setting(Category.DEBUG, "debug.uploadEnabled", StaticMessages.SETTING_DEBUG_UPLOAD,
                        Material.WRITABLE_BOOK, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.debug.uploadEnabled),
                        (config, value) -> config.debug.uploadEnabled = parseBoolean(value))
        ));
        settings.add(setting(Category.MILESTONES, "milestones.enabled", MilestoneMessages.ENABLED,
                Material.GOLD_NUGGET, SettingKind.BOOLEAN, 1D,
                config -> Boolean.toString(config.milestones.enabled),
                (config, value) -> config.milestones.enabled = parseBoolean(value)));
        settings.add(setting(Category.DETAILS, "details.enabled", DetailMessages.CONFIG_ENABLED,
                Material.BOOK, SettingKind.BOOLEAN, 1D,
                config -> Boolean.toString(config.details.enabled),
                (config, value) -> config.details.enabled = parseBoolean(value)));
        settings.add(setting(Category.DETAILS, "details.selectedKeys", DetailMessages.CONFIG_KEYS,
                Material.WRITABLE_BOOK, SettingKind.LIST, 1D,
                config -> join(config.details.selectedKeys),
                ConfigEditorGui::writeDetailKeys));
        settings.add(setting(Category.MILESTONES, "milestones.notifyPlayers", MilestoneMessages.NOTIFY,
                Material.PAPER, SettingKind.BOOLEAN, 1D,
                config -> Boolean.toString(config.milestones.notifyPlayers),
                (config, value) -> config.milestones.notifyPlayers = parseBoolean(value)));
        for (StatKey statistic : StatKey.values()) {
            if (!statistic.derived()) {
                settings.add(setting(Category.MILESTONES, "milestones.thresholds." + statistic.id(), StatsMessages.label(statistic),
                        Material.BOOK, SettingKind.LIST, 1D,
                        config -> config.milestones.thresholds.getOrDefault(statistic.id(), List.of()).stream()
                                .map(value -> Double.toString(value)).collect(Collectors.joining(", ")),
                        (config, value) -> writeTargets(config, statistic, value)));
            }
        }
        return List.copyOf(settings);
    }

    static void writeTargets(StaticConfig config, StatKey statistic, String value) {
        List<String> entries = parseList(value);
        if (entries.isEmpty()) {
            config.milestones.thresholds.remove(statistic.id());
            return;
        }
        List<Double> thresholds = new ArrayList<>(entries.size());
        double previous = 0;
        for (String entry : entries) {
            double threshold = Double.parseDouble(entry);
            if (!Double.isFinite(threshold) || threshold <= previous) {
                throw new IllegalArgumentException("Targets must be finite, positive, unique, and ascending");
            }
            thresholds.add(threshold);
            previous = threshold;
        }
        config.milestones.thresholds.put(statistic.id(), thresholds);
    }

    static void writeDetailKeys(StaticConfig config, String value) {
        List<String> entries = parseList(value);
        boolean edit = entries.stream().anyMatch(entry -> entry.startsWith("+") || entry.startsWith("-"));
        Set<String> selected = edit ? new LinkedHashSet<>(config.details.selectedKeys) : new LinkedHashSet<>();
        for (String entry : entries) {
            boolean add = entry.startsWith("+");
            boolean remove = entry.startsWith("-");
            if (edit && !add && !remove) {
                throw new IllegalArgumentException("Use +id or -id for every entry when adjusting the selection");
            }
            String id = edit ? entry.substring(1) : entry;
            DetailKey key = DetailKey.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown detailed counter: " + id));
            if (remove) {
                selected.remove(key.id());
            } else {
                selected.add(key.id());
            }
        }
        config.details.selectedKeys = new ArrayList<>(selected);
    }

    private static Setting setting(
            Category category,
            String path,
            TextKey name,
            Material material,
            SettingKind kind,
            double step,
            Function<StaticConfig, String> reader,
            SettingWriter writer
    ) {
        return new Setting(category, path, name, material, kind, step, reader, writer);
    }

    private static boolean parseBoolean(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
            throw new IllegalArgumentException("expected true or false");
        }
        return Boolean.parseBoolean(normalized);
    }

    private static ArrayList<String> parseList(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase("none") || normalized.equals("[]")) {
            return new ArrayList<>();
        }
        String[] split = normalized.split(",", -1);
        ArrayList<String> values = new ArrayList<>(split.length);
        for (String entry : split) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("list entries cannot be blank");
            }
            values.add(trimmed);
        }
        return values;
    }

    private static String join(List<String> values) {
        return String.join(", ", values);
    }

    private enum Category {
        GENERAL(StaticMessages.GUI_CATEGORY_GENERAL, Material.COMMAND_BLOCK),
        TRACKING(StaticMessages.GUI_CATEGORY_TRACKING, Material.FILLED_MAP),
        HOT_RELOAD(StaticMessages.GUI_CATEGORY_HOT_RELOAD, Material.COMPARATOR),
        PRESENTATION(StaticMessages.GUI_CATEGORY_PRESENTATION, Material.PAINTING),
        DEBUG(StaticMessages.GUI_CATEGORY_DEBUG, Material.SPYGLASS),
        LANGUAGES(StaticMessages.GUI_CATEGORY_LANGUAGES, Material.BOOKSHELF),
        RANKING(StaticMessages.GUI_CATEGORY_RANKING, Material.GOLD_INGOT),
        HISTORY(StaticMessages.GUI_CATEGORY_HISTORY, Material.CLOCK),
        MILESTONES(MilestoneMessages.CATEGORY, Material.GOLD_NUGGET),
        SEASONS(SeasonMessages.CATEGORY, Material.CLOCK),
        DETAILS(DetailMessages.CONFIG_CATEGORY, Material.BOOK);

        private final TextKey name;
        private final Material material;

        Category(TextKey name, Material material) {
            this.name = name;
            this.material = material;
        }

        private TextKey displayName() {
            return name;
        }

        private Material material() {
            return material;
        }
    }

    private enum SettingKind {
        BOOLEAN,
        INTEGER,
        LONG,
        DECIMAL,
        TEXT,
        LIST,
        LOCALE;

        private boolean numeric() {
            return this == INTEGER || this == LONG || this == DECIMAL;
        }
    }

    @FunctionalInterface
    private interface SettingWriter {
        void write(StaticConfig config, String value);
    }

    private record Setting(
            Category category,
            String path,
            TextKey name,
            Material material,
            SettingKind kind,
            double step,
            Function<StaticConfig, String> reader,
            SettingWriter writer
    ) {
    }

    private record PromptSession(long id, Category category, Setting setting) {
    }

    private record SaveOperation(
            Player player,
            Category category,
            Setting setting,
            Consumer<StaticConfig> mutation
    ) {
    }

    private static final class EditorHolder implements InventoryHolder {
        private final EditorState state;
        private Inventory inventory;

        private EditorHolder(EditorState state) {
            this.state = state;
        }

        private static EditorHolder root() {
            return new EditorHolder(new EditorState(null));
        }

        private static EditorHolder category(Category category) {
            return new EditorHolder(new EditorState(category));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private Category category() {
            return state.category();
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private record EditorState(Category category) {
    }
}
