package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.detailed.DetailCategory;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.detailed.DetailSettings;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.detailed.RankedDetailPlayer;
import com.volmit.staticstats.localization.DetailMessages;
import com.volmit.staticstats.localization.StatsFormatter;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.StatsPeriod;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class DetailGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int CONSOLE_PAGE_SIZE = 10;

    private final StaticPlugin plugin;
    private final Map<UUID, PendingView> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Object> rankingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitInventoryShutdown.View> inventories = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public DetailGui(StaticPlugin plugin) {
        this.plugin = plugin;
    }

    public void visibilityChanged(UUID target) {
        for (BukkitInventoryShutdown.View view : inventories.values()) {
            FoliaScheduler.runEntity(plugin, view.player(), () -> {
                if (BukkitInventoryViews.top(view.player().getOpenInventory()) == view.inventory()
                        && view.inventory().getHolder() instanceof DetailHolder holder
                        && target.equals(holder.view.snapshot().uuid()) && !canView(view.player(), target)) {
                    view.player().closeInventory();
                }
            });
        }
    }

    public void close() {
        closed = true;
        pending.clear();
        rankingRequests.clear();
        BukkitInventoryShutdown.drain(plugin, inventories.values());
        inventories.clear();
    }

    public void show(CommandSender sender, String target, String category, int page) {
        DetailCategory selected = null;
        if (!category.equalsIgnoreCase("all")) {
            try {
                selected = DetailCategory.valueOf(category.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                failure(sender, DetailMessages.UNKNOWN_CATEGORY, args("category", category));
                return;
            }
        }
        request(sender, new ViewState(target, selected, page, true));
    }

    public void top(CommandSender sender, String id, int page) {
        if (closed) {
            return;
        }
        if (!sender.hasPermission("static.detailtop")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        Optional<DetailKey> selected = DetailKey.find(id);
        if (selected.isEmpty()) {
            failure(sender, DetailMessages.UNKNOWN_COUNTER, args("counter", id));
            return;
        }
        if (page < 1) {
            failure(sender, StatsMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        DetailKey counter = selected.get();
        if (sender instanceof RemoteConsoleCommandSender) {
            Optional<DetailRankingSnapshot> cached = plugin.getStatsService().cachedDetailRanking(counter);
            if (cached.isPresent()) {
                renderRanking(sender, cached.get(), page);
            } else {
                send(sender, DetailMessages.TOP_WARMING, MessageArgs.empty());
            }
            return;
        }
        Consumer<Runnable> dispatch = dispatcher(sender);
        Object request = new Object();
        if (sender instanceof Player player) {
            rankingRequests.put(player.getUniqueId(), request);
        }
        CompletableFuture<DetailRankingSnapshot> ranking = plugin.getStatsService().detailRanking(counter);
        if (!ranking.isDone()) {
            send(sender, DetailMessages.TOP_LOADING, MessageArgs.empty());
        }
        ranking.whenComplete((snapshot, error) -> dispatch.accept(() -> {
            if (!acceptRanking(sender, request)) {
                return;
            }
            if (error != null) {
                failure(sender, StatsMessages.TOP_FAILED, MessageArgs.empty());
                return;
            }
            renderRanking(sender, snapshot, page);
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(BukkitInventoryViews.top(event.getView()).getHolder() instanceof DetailHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.viewer)) {
            return;
        }
        int slot = event.getRawSlot();
        FoliaScheduler.runEntity(plugin, player, () -> clickOwned(player, holder, slot), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (BukkitInventoryViews.top(event.getView()).getHolder() instanceof DetailHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        pending.remove(viewer);
        inventories.computeIfPresent(viewer, (id, view) -> view.inventory() == event.getInventory() ? null : view);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
        rankingRequests.remove(event.getPlayer().getUniqueId());
        inventories.remove(event.getPlayer().getUniqueId());
    }

    static List<DetailKey> visibleKeys(DetailSnapshot snapshot, DetailSettings settings, DetailCategory category, boolean recordedOnly) {
        List<DetailKey> keys = new ArrayList<>();
        for (DetailKey key : DetailKey.values()) {
            if ((category == null || key.category() == category)
                    && (snapshot.value(key) > 0 || !recordedOnly && settings.selectedKeys().contains(key))) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    private void request(CommandSender sender, ViewState state) {
        if (!permitted(sender)) {
            return;
        }
        if (state.page() < 1) {
            failure(sender, DetailMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        if (viewer == null && state.target().equalsIgnoreCase("self")) {
            failure(sender, StaticMessages.PLAYER_ONLY, MessageArgs.empty());
            return;
        }
        Consumer<Runnable> dispatch = dispatcher(sender);
        PendingView request = sender instanceof Player player
                ? new PendingView(BukkitInventoryViews.top(player.getOpenInventory())) : null;
        if (viewer != null) {
            pending.put(viewer, request);
        }
        if (sender instanceof RemoteConsoleCommandSender) {
            load(sender, state, viewer, request, dispatch);
            return;
        }
        plugin.executeAsync(() -> load(sender, state, viewer, request, dispatch));
    }

    private void load(CommandSender sender, ViewState state, UUID viewer, PendingView request, Consumer<Runnable> dispatch) {
        try {
            Optional<PlayerStatsSnapshot> profile = state.target().equalsIgnoreCase("self")
                    ? plugin.getStatsService().snapshot(viewer, StatsPeriod.LIFETIME)
                    : plugin.getStatsService().find(state.target(), StatsPeriod.LIFETIME);
            Optional<DetailSnapshot> snapshot = profile.flatMap(value -> plugin.getStatsService().details(value.uuid()));
            dispatch.accept(() -> receive(sender, state, request, snapshot));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load detailed counters for " + state.target(), exception);
            dispatch.accept(() -> {
                if (accept(sender, request)) {
                    failure(sender, DetailMessages.FAILED, MessageArgs.empty());
                }
            });
        }
    }

    private void receive(CommandSender sender, ViewState requested, PendingView request, Optional<DetailSnapshot> selected) {
        if (!accept(sender, request)) {
            return;
        }
        if (selected.isEmpty()) {
            failure(sender, StatsMessages.UNKNOWN_PLAYER, args("player", requested.target()));
            return;
        }
        DetailSnapshot snapshot = selected.get();
        if (!canView(sender, snapshot.uuid())) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        DetailSettings settings = plugin.getConfigService().runtime().detailSettings();
        List<DetailKey> keys = visibleKeys(snapshot, settings, requested.category(), requested.recordedOnly());
        int limit = sender instanceof Player ? PAGE_SIZE : CONSOLE_PAGE_SIZE;
        int pages = Math.max(1, (keys.size() + limit - 1) / limit);
        if (requested.page() > pages) {
            failure(sender, DetailMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        ViewState state = new ViewState(snapshot.uuid().toString(), requested.category(), requested.page(), requested.recordedOnly());
        if (sender instanceof Player player) {
            openOwned(player, new DetailView(snapshot, settings, state, keys, pages));
        } else {
            sendChat(sender, new DetailView(snapshot, settings, state, keys, pages));
        }
    }

    private boolean accept(CommandSender sender, PendingView request) {
        if (!permitted(sender)) {
            return false;
        }
        return !(sender instanceof Player player) || player.isOnline()
                && pending.remove(player.getUniqueId(), request)
                && BukkitInventoryViews.top(player.getOpenInventory()) == request.inventory;
    }

    private void clickOwned(Player player, DetailHolder holder, int slot) {
        if (closed || !player.isOnline() || BukkitInventoryViews.top(player.getOpenInventory()) != holder.inventory) {
            return;
        }
        if (!canView(player, holder.view.snapshot().uuid()) || slot == 53) {
            player.closeInventory();
            return;
        }
        ViewState state = holder.view.state();
        if (slot == 45) {
            DetailCategory[] categories = DetailCategory.values();
            DetailCategory next = state.category() == null ? categories[0]
                    : state.category().ordinal() + 1 == categories.length ? null : categories[state.category().ordinal() + 1];
            request(player, new ViewState(state.target(), next, 1, state.recordedOnly()));
        } else if (slot == 46) {
            request(player, new ViewState(state.target(), state.category(), 1, !state.recordedOnly()));
        } else if (slot == 47 && state.page() > 1) {
            request(player, new ViewState(state.target(), state.category(), state.page() - 1, state.recordedOnly()));
        } else if (slot == 48 && state.page() < holder.view.pages()) {
            request(player, new ViewState(state.target(), state.category(), state.page() + 1, state.recordedOnly()));
        } else if (slot == 49) {
            request(player, state);
        } else if (slot == 51 && canViewProfile(player, holder.view.snapshot().uuid())) {
            plugin.getStatsGui().show(player, state.target(), StatsPeriod.LIFETIME);
        } else if (slot >= 0 && slot < PAGE_SIZE) {
            int index = (state.page() - 1) * PAGE_SIZE + slot;
            if (index < holder.view.keys().size()) {
                top(player, holder.view.keys().get(index).id(), 1);
            }
        }
    }

    private void openOwned(Player player, DetailView view) {
        DetailHolder holder = new DetailHolder(player.getUniqueId(), view);
        holder.inventory = Bukkit.createInventory(holder, 54, legacy(player, DetailMessages.TITLE, args("player", view.snapshot().name())));
        int start = (view.state().page() - 1) * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, view.keys().size()); index++) {
            DetailKey key = view.keys().get(index);
            holder.inventory.setItem(index - start, item(icon(key), "§x§F§F§E§3§9§C" + plain(player, DetailMessages.label(key)),
                    counterLore(player, view, key)));
        }
        if (view.keys().isEmpty()) {
            holder.inventory.setItem(22, item(Material.PAPER, legacy(player, DetailMessages.EMPTY, MessageArgs.empty()),
                    List.of(legacy(player, DetailMessages.EMPTY_HINT, MessageArgs.empty()))));
        }
        holder.inventory.setItem(45, item(Material.COMPASS, legacy(player, DetailMessages.CATEGORY,
                args("category", plain(player, DetailMessages.category(view.state().category())))),
                List.of(legacy(player, DetailMessages.CATEGORY_HINT, MessageArgs.empty()))));
        holder.inventory.setItem(46, item(Material.HOPPER, legacy(player,
                view.state().recordedOnly() ? DetailMessages.RECORDED : DetailMessages.SELECTED, MessageArgs.empty()),
                List.of(legacy(player, view.state().recordedOnly() ? DetailMessages.SHOW_SELECTED : DetailMessages.HIDE_ZERO, MessageArgs.empty()))));
        if (view.state().page() > 1) {
            holder.inventory.setItem(47, item(Material.ARROW, legacy(player, StatsMessages.PREVIOUS, MessageArgs.empty()), List.of()));
        }
        if (view.state().page() < view.pages()) {
            holder.inventory.setItem(48, item(Material.ARROW, legacy(player, StatsMessages.NEXT, MessageArgs.empty()), List.of()));
        }
        holder.inventory.setItem(49, item(Material.CLOCK, legacy(player, StatsMessages.REFRESH, MessageArgs.empty()), List.of()));
        List<String> coverage = List.of(legacy(player, DetailMessages.LIFETIME, MessageArgs.empty()),
                legacy(player, DetailMessages.COVERAGE, MessageArgs.empty()),
                legacy(player, view.settings().enabled() ? DetailMessages.ENABLED : DetailMessages.DISABLED, MessageArgs.empty()));
        holder.inventory.setItem(50, item(Material.PAPER, legacy(player, StatsMessages.COVERAGE, MessageArgs.empty()), coverage));
        if (canViewProfile(player, view.snapshot().uuid())) {
            holder.inventory.setItem(51, item(Material.BOOK, legacy(player, DetailMessages.BACK, MessageArgs.empty()), List.of()));
        }
        holder.inventory.setItem(53, item(Material.BARRIER, legacy(player, StatsMessages.CLOSE, MessageArgs.empty()), List.of()));
        player.openInventory(holder.inventory);
        if (BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            inventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
        }
    }

    private void sendChat(CommandSender sender, DetailView view) {
        send(sender, DetailMessages.HEADER, MessageArgs.builder().untrusted("player", view.snapshot().name())
                .untrusted("page", number(sender, view.state().page())).untrusted("pages", number(sender, view.pages())).build());
        send(sender, DetailMessages.LIFETIME, MessageArgs.empty());
        send(sender, DetailMessages.COVERAGE, MessageArgs.empty());
        send(sender, view.settings().enabled() ? DetailMessages.ENABLED : DetailMessages.DISABLED, MessageArgs.empty());
        if (view.keys().isEmpty()) {
            send(sender, DetailMessages.EMPTY, MessageArgs.empty());
        }
        int start = (view.state().page() - 1) * CONSOLE_PAGE_SIZE;
        for (DetailKey key : view.keys().subList(start, Math.min(start + CONSOLE_PAGE_SIZE, view.keys().size()))) {
            send(sender, DetailMessages.ENTRY, MessageArgs.builder().untrusted("counter", plain(sender, DetailMessages.label(key)))
                    .untrusted("value", number(sender, view.snapshot().value(key))).build());
            send(sender, DetailMessages.FIRST_OBSERVED,
                    args("date", StatsFormatter.date(view.snapshot().recordedSince(key), plugin.getLanguageService(), sender)));
            send(sender, StatsMessages.TOP_UPDATED,
                    args("date", StatsFormatter.date(view.snapshot().lastObservedAt(key), plugin.getLanguageService(), sender)));
            send(sender, view.settings().records(key) ? DetailMessages.RECORDING : DetailMessages.RETAINED, MessageArgs.empty());
        }
    }

    private List<String> counterLore(Player player, DetailView view, DetailKey key) {
        List<String> lore = new ArrayList<>(4);
        lore.add(legacy(player, DetailMessages.COUNT, args("value", number(player, view.snapshot().value(key)))));
        long since = view.snapshot().recordedSince(key);
        lore.add(legacy(player, since > 0 ? DetailMessages.FIRST_OBSERVED : DetailMessages.UNOBSERVED,
                since > 0 ? args("date", StatsFormatter.date(since, plugin.getLanguageService(), player)) : MessageArgs.empty()));
        if (since > 0) {
            lore.add(legacy(player, StatsMessages.TOP_UPDATED,
                    args("date", StatsFormatter.date(view.snapshot().lastObservedAt(key), plugin.getLanguageService(), player))));
        }
        lore.add(legacy(player, view.settings().records(key) ? DetailMessages.RECORDING : DetailMessages.RETAINED, MessageArgs.empty()));
        if (player.hasPermission("static.detailtop")) {
            lore.add(legacy(player, DetailMessages.TOP_HINT, MessageArgs.empty()));
        }
        return lore;
    }

    private boolean acceptRanking(CommandSender sender, Object request) {
        if (closed) {
            return false;
        }
        if (sender instanceof Player player && (!player.isOnline() || !rankingRequests.remove(player.getUniqueId(), request))) {
            return false;
        }
        if (!sender.hasPermission("static.detailtop")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return false;
        }
        return true;
    }

    private void renderRanking(CommandSender sender, DetailRankingSnapshot ranking, int page) {
        if (!sender.hasPermission("static.privacy.bypass")) {
            ranking = plugin.getProfileVisibility().filter(ranking);
        }
        RuntimeConfig configuration = plugin.getConfigService().runtime();
        int count = ranking.entries().size();
        int limit = configuration.leaderboardLimit();
        int pages = Math.max(1, (count - 1) / limit + 1);
        if (page > pages) {
            failure(sender, StatsMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        int start = (page - 1) * limit;
        int end = Math.min(start + limit, count);
        send(sender, StatsMessages.TOP_HEADER, MessageArgs.builder()
                .untrusted("stat", plain(sender, DetailMessages.label(ranking.counter())))
                .untrusted("page", number(sender, page)).untrusted("pages", number(sender, pages)).build());
        send(sender, DetailMessages.TOP_SCOPE, MessageArgs.builder().untrusted("ranked", number(sender, count))
                .untrusted("total", number(sender, ranking.totalProfiles())).build());
        send(sender, DetailMessages.COVERAGE, MessageArgs.empty());
        if (ranking.recordedSince() > 0) {
            send(sender, DetailMessages.TOP_FIRST_OBSERVED,
                    args("date", StatsFormatter.date(ranking.recordedSince(), plugin.getLanguageService(), sender)));
        }
        send(sender, configuration.enabled() && configuration.detailSettings().records(ranking.counter())
                ? DetailMessages.RECORDING : DetailMessages.RETAINED, MessageArgs.empty());
        if (count == 0) {
            send(sender, DetailMessages.TOP_EMPTY, MessageArgs.empty());
        }
        for (RankedDetailPlayer entry : ranking.entries().subList(start, end)) {
            sendRankingEntry(sender, entry);
        }
        send(sender, StatsMessages.TOP_UPDATED,
                args("date", StatsFormatter.date(ranking.refreshedAt(), plugin.getLanguageService(), sender)));
        if (sender instanceof Player player) {
            showPersonalRanking(player, ranking, start, end);
        }
    }

    private void showPersonalRanking(Player player, DetailRankingSnapshot ranking, int pageStart, int pageEnd) {
        Optional<RankedDetailPlayer> match = ranking.find(player.getUniqueId());
        if (match.isEmpty()) {
            send(player, DetailMessages.TOP_UNRANKED, MessageArgs.empty());
            return;
        }
        RankedDetailPlayer personal = match.get();
        send(player, StatsMessages.TOP_PERSONAL, MessageArgs.builder()
                .untrusted("rank", number(player, personal.rank())).untrusted("players", number(player, ranking.entries().size()))
                .untrusted("value", number(player, personal.value())).build());
        if (personal.rank() == 1) {
            send(player, StatsMessages.TOP_LEADING, MessageArgs.empty());
        } else {
            send(player, StatsMessages.TOP_GAP, args("value", number(player, personal.gapToNext())));
        }
        int position = personal.position() - 1;
        if (position >= pageStart && position < pageEnd) {
            return;
        }
        send(player, StatsMessages.TOP_NEARBY, MessageArgs.empty());
        for (int index = Math.max(0, position - 2); index <= Math.min(ranking.entries().size() - 1, position + 2); index++) {
            sendRankingEntry(player, ranking.entries().get(index));
        }
    }

    private void sendRankingEntry(CommandSender sender, RankedDetailPlayer entry) {
        send(sender, StatsMessages.TOP_ENTRY, MessageArgs.builder().untrusted("rank", number(sender, entry.rank()))
                .untrusted("player", entry.profile().name()).untrusted("value", number(sender, entry.value())).build());
    }

    private boolean permitted(CommandSender sender) {
        if (closed) {
            return false;
        }
        if (!sender.hasPermission("static.details")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return false;
        }
        return true;
    }

    private boolean canView(CommandSender sender, UUID target) {
        return sender.hasPermission("static.details") && (!(sender instanceof Player player)
                || player.getUniqueId().equals(target) || sender.hasPermission("static.details.others"))
                && plugin.getProfileVisibility().canView(sender, target);
    }

    private boolean canViewProfile(Player player, UUID target) {
        return player.hasPermission("static.stats") && (player.getUniqueId().equals(target) || player.hasPermission("static.stats.others"))
                && plugin.getProfileVisibility().canView(player, target);
    }

    private Consumer<Runnable> dispatcher(CommandSender sender) {
        if (sender instanceof Entity entity) {
            return action -> FoliaScheduler.runEntity(plugin, entity, action);
        }
        if (sender instanceof BlockCommandSender block) {
            Location location = block.getBlock().getLocation();
            return action -> FoliaScheduler.runRegion(plugin, location, action);
        }
        return Runnable::run;
    }

    private void failure(CommandSender sender, TextKey key, MessageArgs arguments) {
        plugin.getPresentationService().command(sender, key, arguments, FeedbackTone.FAILURE);
    }

    private void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        plugin.getLanguageService().send(sender, key, arguments);
    }

    private String number(CommandSender sender, long value) {
        UUID player = sender instanceof Player audience ? audience.getUniqueId() : null;
        return NumberFormat.getIntegerInstance(plugin.getLanguageService().formattingLocale(player)).format(value);
    }

    private String plain(CommandSender sender, TextKey key) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key, MessageArgs.empty())).plain();
    }

    private String legacy(CommandSender sender, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key, arguments)).legacy();
    }

    private MessageArgs args(String key, String value) {
        return MessageArgs.builder().untrusted(key, value).build();
    }

    private Material icon(DetailKey key) {
        boolean entity = key.category() == DetailCategory.MOBS_KILLED || key.category() == DetailCategory.ANIMALS_BRED;
        Material material = key.target().isEmpty() ? null : Material.matchMaterial(key.target() + (entity ? "_SPAWN_EGG" : ""));
        if (material != null && material.isItem()) {
            return material;
        }
        return switch (key.category()) {
            case BLOCKS_MINED -> Material.IRON_PICKAXE;
            case BLOCKS_PLACED -> Material.BRICKS;
            case MOBS_KILLED -> Material.IRON_SWORD;
            case FISH_CAUGHT -> Material.FISHING_ROD;
            case ANIMALS_BRED -> Material.WHEAT;
            case ITEMS_CRAFTED -> Material.CRAFTING_TABLE;
            case ITEMS_TRADED -> Material.EMERALD;
        };
    }

    private ItemStack item(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private record ViewState(String target, DetailCategory category, int page, boolean recordedOnly) {
    }

    private record DetailView(DetailSnapshot snapshot, DetailSettings settings, ViewState state, List<DetailKey> keys, int pages) {
    }

    private static final class PendingView {
        private final Inventory inventory;

        private PendingView(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private static final class DetailHolder implements InventoryHolder {
        private final UUID viewer;
        private final DetailView view;
        private Inventory inventory;

        private DetailHolder(UUID viewer, DetailView view) {
            this.viewer = viewer;
            this.view = view;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
