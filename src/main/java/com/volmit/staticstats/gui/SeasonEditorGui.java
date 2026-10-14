package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.localization.SeasonMessages;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class SeasonEditorGui implements Listener {
    private static final int PAGE_SIZE = 27;

    private final StaticPlugin plugin;
    private final Map<UUID, EditorSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitInventoryShutdown.View> inventories = new ConcurrentHashMap<>();
    private final Set<UUID> changing = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public SeasonEditorGui(StaticPlugin plugin) {
        this.plugin = plugin;
    }

    public void close() {
        closed = true;
        sessions.clear();
        prompts.clear();
        BukkitInventoryShutdown.drain(plugin, inventories.values());
        inventories.clear();
    }

    public void open(Player player) {
        FoliaScheduler.runEntity(plugin, player, () -> catalog(player, 0));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(BukkitInventoryViews.top(event.getView()).getHolder() instanceof EditorHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        boolean remove = event.isShiftClick() && event.isRightClick();
        FoliaScheduler.runEntity(plugin, player, () -> click(player, holder, slot, remove), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (BukkitInventoryViews.top(event.getView()).getHolder() instanceof EditorHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String input = event.getMessage();
        FoliaScheduler.runEntity(plugin, player, () -> input(player, prompt, input));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        inventories.computeIfPresent(viewer, (id, view) -> view.inventory() == event.getInventory() ? null : view);
        if (event.getInventory().getHolder() instanceof EditorHolder && !changing.contains(viewer)) {
            sessions.remove(viewer);
            prompts.remove(viewer);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditorHolder)) {
            sessions.remove(event.getPlayer().getUniqueId());
            prompts.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        sessions.remove(viewer);
        prompts.remove(viewer);
        inventories.remove(viewer);
    }

    private void catalog(Player player, int requestedPage) {
        if (!permitted(player)) {
            return;
        }
        sessions.remove(player.getUniqueId());
        prompts.remove(player.getUniqueId());
        List<StaticConfig.Season> events = plugin.getConfigService().editableCopy().seasons.events;
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (events.size() - 1) / PAGE_SIZE)));
        EditorHolder holder = new EditorHolder(new EditorView(null, page,
                List.copyOf(events.subList(page * PAGE_SIZE, Math.min((page + 1) * PAGE_SIZE, events.size())))));
        holder.inventory = Bukkit.createInventory(holder, 54, legacy(player, SeasonMessages.EDITOR, MessageArgs.empty()));
        for (int index = 0; index < holder.view.events().size(); index++) {
            StaticConfig.Season event = holder.view.events().get(index);
            holder.inventory.setItem(index, item(Material.BOOK, legacy(player, SeasonMessages.NAME,
                    MessageArgs.builder().untrusted("name", event.name).untrusted("id", event.id).build()),
                    List.of(event.id, legacy(player, locked(event) ? SeasonMessages.LOCKED : SeasonMessages.EDIT_HINT, MessageArgs.empty()))));
        }
        if (page > 0) {
            holder.inventory.setItem(45, item(Material.ARROW, legacy(player, StatsMessages.PREVIOUS, MessageArgs.empty()), List.of()));
        }
        if ((page + 1) * PAGE_SIZE < events.size()) {
            holder.inventory.setItem(46, item(Material.ARROW, legacy(player, StatsMessages.NEXT, MessageArgs.empty()), List.of()));
        }
        holder.inventory.setItem(48, item(Material.ARROW, legacy(player, StaticMessages.GUI_BACK, MessageArgs.empty()), List.of()));
        holder.inventory.setItem(49, item(Material.WRITABLE_BOOK, legacy(player, SeasonMessages.ADD, MessageArgs.empty()), List.of()));
        holder.inventory.setItem(53, item(Material.BARRIER, legacy(player, StatsMessages.CLOSE, MessageArgs.empty()), List.of()));
        openOwned(player, holder);
    }

    private void edit(Player player, EditorSession session) {
        if (!permitted(player) || sessions.get(player.getUniqueId()) != session) {
            return;
        }
        EditorHolder holder = new EditorHolder(new EditorView(session, 0, List.of()));
        holder.inventory = Bukkit.createInventory(holder, 54, legacy(player, SeasonMessages.EDITOR, MessageArgs.empty()));
        for (Field field : Field.values()) {
            List<String> lore = new ArrayList<>(List.of(legacy(player, StaticMessages.GUI_STATE, args("value", read(session.draft, field))),
                    legacy(player, field.toggle() ? StaticMessages.GUI_TOGGLE : StaticMessages.GUI_TEXT, MessageArgs.empty())));
            if (field == Field.START || field == Field.END) {
                lore.add(legacy(player, SeasonMessages.DATE_HINT, MessageArgs.empty()));
            } else if (field == Field.MINIMUM) {
                lore.add(legacy(player, SeasonMessages.SAMPLE_HINT, MessageArgs.empty()));
            }
            holder.inventory.setItem(field.ordinal(), item(Material.PAPER, legacy(player, field.label, MessageArgs.empty()), lore));
        }
        holder.inventory.setItem(45, item(Material.ARROW, legacy(player, StaticMessages.GUI_BACK, MessageArgs.empty()), List.of()));
        holder.inventory.setItem(49, item(Material.LIME_DYE, legacy(player, SeasonMessages.SAVE, MessageArgs.empty()),
                List.of(legacy(player, SeasonMessages.DRAFT, MessageArgs.empty()))));
        if (session.original != null) {
            holder.inventory.setItem(51, item(Material.RED_DYE, legacy(player, SeasonMessages.REMOVE, MessageArgs.empty()),
                    List.of(legacy(player, SeasonMessages.REMOVE_HINT, MessageArgs.empty()))));
        }
        holder.inventory.setItem(53, item(Material.BARRIER, legacy(player, StatsMessages.CLOSE, MessageArgs.empty()), List.of()));
        openOwned(player, holder);
    }

    private void click(Player player, EditorHolder holder, int slot, boolean remove) {
        if (closed || !player.isOnline() || BukkitInventoryViews.top(player.getOpenInventory()) != holder.inventory) {
            return;
        }
        if (!permitted(player) || slot == 53) {
            player.closeInventory();
            return;
        }
        EditorSession session = holder.view.session();
        if (session == null) {
            if (slot == 45) {
                catalog(player, holder.view.page() - 1);
            } else if (slot == 46) {
                catalog(player, holder.view.page() + 1);
            } else if (slot == 48) {
                plugin.getConfigEditor().open(player);
            } else if (slot == 49) {
                StaticConfig.Season draft = new StaticConfig.Season();
                draft.startsAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
                draft.endsAt = Instant.parse(draft.startsAt).plus(7, ChronoUnit.DAYS).toString();
                EditorSession created = new EditorSession(null, draft);
                sessions.put(player.getUniqueId(), created);
                beginPrompt(player, created, Field.ID);
            } else if (slot >= 0 && slot < holder.view.events().size()) {
                StaticConfig.Season selected = holder.view.events().get(slot);
                if (locked(selected)) {
                    plugin.getLanguageService().send(player, SeasonMessages.LOCKED);
                    return;
                }
                EditorSession selectedSession = new EditorSession(selected.copy(), selected.copy());
                sessions.put(player.getUniqueId(), selectedSession);
                edit(player, selectedSession);
            }
            return;
        }
        if (sessions.get(player.getUniqueId()) != session || session.saving) {
            return;
        }
        if (slot == 45) {
            catalog(player, 0);
        } else if (slot == 49 || slot == 51 && remove && session.original != null) {
            save(player, holder, session, slot == 51);
        } else if (slot >= 0 && slot < Field.values().length) {
            Field field = Field.values()[slot];
            if (field.toggle()) {
                write(session.draft, field, Boolean.toString(!Boolean.parseBoolean(read(session.draft, field))));
                edit(player, session);
            } else {
                beginPrompt(player, session, field);
            }
        }
    }

    private void beginPrompt(Player player, EditorSession session, Field field) {
        Prompt prompt = new Prompt(session, field);
        prompts.put(player.getUniqueId(), prompt);
        changing.add(player.getUniqueId());
        try {
            player.closeInventory();
        } finally {
            changing.remove(player.getUniqueId());
        }
        plugin.getLanguageService().send(player, StaticMessages.GUI_PROMPT, args("setting", plain(player, field.label)));
        plugin.getLanguageService().send(player, StaticMessages.GUI_PROMPT_CANCEL);
        if (field == Field.WORLDS) {
            plugin.getLanguageService().send(player, StaticMessages.GUI_PROMPT_LIST);
        } else if (field == Field.START || field == Field.END) {
            plugin.getLanguageService().send(player, SeasonMessages.DATE_HINT);
        }
        boolean scheduled = FoliaScheduler.runEntity(plugin, player, () -> {
            if (prompts.remove(player.getUniqueId(), prompt)) {
                plugin.getLanguageService().send(player, StaticMessages.GUI_PROMPT_TIMEOUT);
                edit(player, session);
            }
        }, 1200L);
        if (!scheduled) {
            prompts.remove(player.getUniqueId(), prompt);
        }
    }

    private void input(Player player, Prompt prompt, String input) {
        if (!permitted(player) || sessions.get(player.getUniqueId()) != prompt.session()) {
            return;
        }
        if (input.equalsIgnoreCase("cancel")) {
            plugin.getLanguageService().send(player, StaticMessages.GUI_PROMPT_CANCELLED);
            edit(player, prompt.session());
            return;
        }
        try {
            if (input.length() > 512) {
                throw new IllegalArgumentException("Input is longer than 512 characters");
            }
            write(prompt.session().draft, prompt.field(), input.trim());
        } catch (IllegalArgumentException | DateTimeException exception) {
            plugin.getLanguageService().send(player, StaticMessages.CONFIG_SAVE_FAILED, MessageArgs.builder()
                    .untrusted("setting", plain(player, prompt.field().label)).untrusted("reason", exception.getMessage()).build());
        }
        edit(player, prompt.session());
    }

    private void save(Player player, EditorHolder holder, EditorSession session, boolean remove) {
        session.saving = true;
        StaticConfig.Season draft = session.draft.copy();
        plugin.executeAsync(() -> {
            Exception failure = null;
            try {
                plugin.applyConfigurationEdit(candidate -> apply(candidate, session.original, draft, remove), null);
            } catch (IOException | RuntimeException exception) {
                failure = exception;
                plugin.getLogger().log(Level.SEVERE, "Could not save season " + draft.id, exception);
            }
            Exception outcome = failure;
            FoliaScheduler.runEntity(plugin, player, () -> {
                session.saving = false;
                if (!permitted(player) || sessions.get(player.getUniqueId()) != session
                        || BukkitInventoryViews.top(player.getOpenInventory()) != holder.inventory) {
                    return;
                }
                if (outcome == null) {
                    plugin.getLanguageService().send(player, remove ? SeasonMessages.REMOVED : SeasonMessages.SAVED,
                            args("id", remove ? session.original.id : draft.id));
                    catalog(player, 0);
                } else {
                    plugin.getLanguageService().send(player, StaticMessages.CONFIG_SAVE_FAILED, MessageArgs.builder()
                            .untrusted("setting", draft.id).untrusted("reason", Objects.toString(outcome.getMessage(), outcome.getClass().getSimpleName())).build());
                }
            });
        });
    }

    static void apply(StaticConfig config, StaticConfig.Season original, StaticConfig.Season draft, boolean remove) {
        List<StaticConfig.Season> events = config.seasons.events;
        if (original == null) {
            if (remove || events.stream().anyMatch(event -> event.id.equals(draft.id))) {
                throw new IllegalArgumentException("Event identifier already exists");
            }
            events.add(draft.copy());
            return;
        }
        for (int index = 0; index < events.size(); index++) {
            StaticConfig.Season current = events.get(index);
            if (current.id.equals(original.id)) {
                if (!same(current, original)) {
                    throw new IllegalArgumentException("Event changed since this editor opened; reopen it before saving");
                }
                if (remove) {
                    events.remove(index);
                } else {
                    events.set(index, draft.copy());
                }
                return;
            }
        }
        throw new IllegalArgumentException("Event was removed since this editor opened");
    }

    private void openOwned(Player player, EditorHolder holder) {
        Inventory previous = BukkitInventoryViews.top(player.getOpenInventory());
        changing.add(player.getUniqueId());
        try {
            player.openInventory(holder.inventory);
            if (BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
                inventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
            } else if (previous.getHolder() instanceof EditorHolder old && old.view.session() != null) {
                sessions.put(player.getUniqueId(), old.view.session());
            } else {
                sessions.remove(player.getUniqueId());
            }
        } finally {
            changing.remove(player.getUniqueId());
        }
    }

    private boolean permitted(Player player) {
        if (closed || !player.isOnline()) {
            return false;
        }
        if (!player.hasPermission("static.config")) {
            plugin.getPresentationService().command(player, StaticMessages.NO_PERMISSION, FeedbackTone.FAILURE);
            return false;
        }
        return true;
    }

    private boolean locked(StaticConfig.Season event) {
        return !Instant.parse(event.startsAt).isAfter(Instant.now());
    }

    private String plain(Player player, TextKey key) {
        return ComponentText.markup(plugin.getLanguageService().render(player, key)).plain();
    }

    private String legacy(Player player, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(plugin.getLanguageService().render(player, key, arguments)).legacy();
    }

    private static boolean same(StaticConfig.Season first, StaticConfig.Season second) {
        for (Field field : Field.values()) {
            if (!read(first, field).equals(read(second, field))) {
                return false;
            }
        }
        return true;
    }

    private static String read(StaticConfig.Season event, Field field) {
        return switch (field) {
            case ID -> event.id;
            case NAME -> event.name;
            case START -> event.startsAt;
            case END -> event.endsAt;
            case STATISTIC -> event.statistic;
            case MINIMUM -> Double.toString(event.minimumSample);
            case WORLDS -> String.join(", ", event.excludedWorlds);
            case CREATIVE -> Boolean.toString(event.trackCreative);
            case SPECTATOR -> Boolean.toString(event.trackSpectator);
            case AFK -> Integer.toString(event.afkSeconds);
        };
    }

    private static void write(StaticConfig.Season event, Field field, String value) {
        switch (field) {
            case ID -> {
                event.id = value;
                if (event.name.isBlank()) {
                    event.name = value;
                }
            }
            case NAME -> event.name = value;
            case START -> event.startsAt = Instant.parse(value).toString();
            case END -> event.endsAt = Instant.parse(value).toString();
            case STATISTIC -> event.statistic = value;
            case MINIMUM -> event.minimumSample = Double.parseDouble(value);
            case WORLDS -> event.excludedWorlds = value.isBlank() || value.equalsIgnoreCase("none") ? new ArrayList<>()
                    : new ArrayList<>(Arrays.stream(value.split(",", -1)).map(String::trim).toList());
            case CREATIVE -> event.trackCreative = Boolean.parseBoolean(value);
            case SPECTATOR -> event.trackSpectator = Boolean.parseBoolean(value);
            case AFK -> event.afkSeconds = Integer.parseInt(value);
        }
    }

    private static MessageArgs args(String key, String value) {
        return MessageArgs.builder().untrusted(key, value).build();
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private enum Field {
        ID(SeasonMessages.ID), NAME(SeasonMessages.DISPLAY_NAME), START(SeasonMessages.START), END(SeasonMessages.END),
        STATISTIC(SeasonMessages.STATISTIC), MINIMUM(SeasonMessages.MINIMUM), WORLDS(StaticMessages.SETTING_TRACKING_EXCLUDED_WORLDS),
        CREATIVE(StaticMessages.SETTING_TRACKING_CREATIVE), SPECTATOR(StaticMessages.SETTING_TRACKING_SPECTATOR), AFK(StaticMessages.SETTING_HISTORY_AFK);

        private final TextKey label;

        Field(TextKey label) {
            this.label = label;
        }

        private boolean toggle() {
            return this == CREATIVE || this == SPECTATOR;
        }
    }

    private record Prompt(EditorSession session, Field field) { }
    private record EditorView(EditorSession session, int page, List<StaticConfig.Season> events) { }

    private static final class EditorSession {
        private final StaticConfig.Season original;
        private final StaticConfig.Season draft;
        private boolean saving;

        private EditorSession(StaticConfig.Season original, StaticConfig.Season draft) {
            this.original = original;
            this.draft = draft;
        }
    }

    private static final class EditorHolder implements InventoryHolder {
        private final EditorView view;
        private Inventory inventory;

        private EditorHolder(EditorView view) {
            this.view = view;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
