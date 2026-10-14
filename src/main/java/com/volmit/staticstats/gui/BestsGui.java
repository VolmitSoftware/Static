package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.localization.BestsMessages;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.StatsFormatter;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.records.PersonalBest;
import com.volmit.staticstats.records.PersonalBestPeriod;
import com.volmit.staticstats.records.PersonalBestSnapshot;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.StatKey;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class BestsGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int CONSOLE_PAGE_SIZE = 10;

    private final StaticPlugin plugin;
    private final Map<UUID, PendingView> pending = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitInventoryShutdown.View> inventories = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public BestsGui(StaticPlugin plugin) {
        this.plugin = plugin;
    }

    public void visibilityChanged(UUID target) {
        for (BukkitInventoryShutdown.View view : inventories.values()) {
            FoliaScheduler.runEntity(plugin, view.player(), () -> {
                if (BukkitInventoryViews.top(view.player().getOpenInventory()) == view.inventory()
                        && view.inventory().getHolder() instanceof BestsHolder holder
                        && target.equals(holder.view.snapshot().uuid()) && !canView(view.player(), target)) {
                    view.player().closeInventory();
                }
            });
        }
    }

    public void close() {
        closed = true;
        pending.clear();
        BukkitInventoryShutdown.drain(plugin, inventories.values());
        inventories.clear();
    }

    public void show(CommandSender sender, String target, String period, int page) {
        PersonalBestPeriod selected;
        try {
            selected = PersonalBestPeriod.parse(period);
        } catch (IllegalArgumentException exception) {
            failure(sender, BestsMessages.UNKNOWN_PERIOD, args("period", period));
            return;
        }
        request(sender, new ViewState(target, selected, page));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(BukkitInventoryViews.top(event.getView()).getHolder() instanceof BestsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.viewer.equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        FoliaScheduler.runEntity(plugin, player, () -> clickOwned(player, holder, slot), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (BukkitInventoryViews.top(event.getView()).getHolder() instanceof BestsHolder) {
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
        inventories.remove(event.getPlayer().getUniqueId());
    }

    static List<PersonalBest> records(PersonalBestSnapshot snapshot, PersonalBestPeriod period) {
        return snapshot.records().stream().filter(record -> record.period() == period)
                .sorted(Comparator.comparing(PersonalBest::statistic)).toList();
    }

    private void request(CommandSender sender, ViewState state) {
        if (!permitted(sender)) {
            return;
        }
        if (state.page() < 1) {
            failure(sender, BestsMessages.INVALID_PAGE, MessageArgs.empty());
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
        } else {
            plugin.executeAsync(() -> load(sender, state, viewer, request, dispatch));
        }
    }

    private void load(CommandSender sender, ViewState state, UUID viewer, PendingView request, Consumer<Runnable> dispatch) {
        try {
            Optional<PlayerStatsSnapshot> profile = state.target().equalsIgnoreCase("self")
                    ? plugin.getStatsService().snapshot(viewer, StatsPeriod.LIFETIME)
                    : plugin.getStatsService().find(state.target(), StatsPeriod.LIFETIME);
            Optional<PersonalBestSnapshot> bests = profile.flatMap(value -> plugin.getStatsService().personalBests(value.uuid()));
            dispatch.accept(() -> receive(sender, state, request, bests));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load personal bests for " + state.target(), exception);
            dispatch.accept(() -> {
                if (accept(sender, request)) {
                    failure(sender, BestsMessages.FAILED, MessageArgs.empty());
                }
            });
        }
    }

    private void receive(CommandSender sender, ViewState requested, PendingView request, Optional<PersonalBestSnapshot> selected) {
        if (!accept(sender, request)) {
            return;
        }
        if (selected.isEmpty()) {
            failure(sender, StatsMessages.UNKNOWN_PLAYER, args("player", requested.target()));
            return;
        }
        PersonalBestSnapshot snapshot = selected.get();
        if (!canView(sender, snapshot.uuid())) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        List<PersonalBest> records = records(snapshot, requested.period());
        int limit = sender instanceof Player ? PAGE_SIZE : CONSOLE_PAGE_SIZE;
        int pages = Math.max(1, (records.size() + limit - 1) / limit);
        if (requested.page() > pages) {
            failure(sender, BestsMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        ViewState state = new ViewState(snapshot.uuid().toString(), requested.period(), requested.page());
        BestsView view = new BestsView(snapshot, state, records, pages);
        if (sender instanceof Player player) {
            openOwned(player, view);
        } else {
            sendChat(sender, view);
        }
    }

    private boolean accept(CommandSender sender, PendingView request) {
        if (sender instanceof Player player && (!player.isOnline()
                || !pending.remove(player.getUniqueId(), request)
                || BukkitInventoryViews.top(player.getOpenInventory()) != request.inventory)) {
            return false;
        }
        return permitted(sender);
    }

    private void clickOwned(Player player, BestsHolder holder, int slot) {
        if (closed || !player.isOnline() || BukkitInventoryViews.top(player.getOpenInventory()) != holder.inventory) {
            return;
        }
        if (!canView(player, holder.view.snapshot().uuid()) || slot == 53) {
            player.closeInventory();
            return;
        }
        ViewState state = holder.view.state();
        if (slot == 45) {
            PersonalBestPeriod[] periods = PersonalBestPeriod.values();
            request(player, new ViewState(state.target(), periods[(state.period().ordinal() + 1) % periods.length], 1));
        } else if (slot == 47 && state.page() > 1) {
            request(player, new ViewState(state.target(), state.period(), state.page() - 1));
        } else if (slot == 48 && state.page() < holder.view.pages()) {
            request(player, new ViewState(state.target(), state.period(), state.page() + 1));
        } else if (slot == 49) {
            request(player, state);
        } else if (slot == 51 && canViewProfile(player, holder.view.snapshot().uuid())) {
            plugin.getStatsGui().show(player, state.target(), StatsPeriod.LIFETIME);
        }
    }

    private void openOwned(Player player, BestsView view) {
        BestsHolder holder = new BestsHolder(player.getUniqueId(), view);
        holder.inventory = Bukkit.createInventory(holder, 54, legacy(player, BestsMessages.TITLE, args("player", view.snapshot().name())));
        int start = (view.state().page() - 1) * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, view.records().size()); index++) {
            PersonalBest best = view.records().get(index);
            List<String> lore = recordLines(player, best).stream().map(line -> legacy(player, line.key(), line.arguments())).toList();
            holder.inventory.setItem(index - start, item(icon(best.statistic()),
                    "§x§F§F§E§3§9§C" + plain(player, StatsMessages.label(best.statistic())), lore));
        }
        if (view.records().isEmpty()) {
            holder.inventory.setItem(22, item(Material.PAPER, legacy(player, BestsMessages.EMPTY, MessageArgs.empty()), List.of()));
        }
        holder.inventory.setItem(45, item(Material.COMPASS, legacy(player, BestsMessages.SCOPE,
                args("period", plain(player, BestsMessages.period(view.state().period())))),
                List.of(legacy(player, BestsMessages.SCOPE_HINT, MessageArgs.empty()))));
        if (view.state().page() > 1) {
            holder.inventory.setItem(47, item(Material.ARROW, legacy(player, StatsMessages.PREVIOUS, MessageArgs.empty()), List.of()));
        }
        if (view.state().page() < view.pages()) {
            holder.inventory.setItem(48, item(Material.ARROW, legacy(player, StatsMessages.NEXT, MessageArgs.empty()), List.of()));
        }
        holder.inventory.setItem(49, item(Material.CLOCK, legacy(player, StatsMessages.REFRESH, MessageArgs.empty()), List.of()));
        List<String> coverage = coverageLines(player, view).stream().map(line -> legacy(player, line.key(), line.arguments())).toList();
        holder.inventory.setItem(50, item(Material.PAPER, legacy(player, StatsMessages.COVERAGE, MessageArgs.empty()), coverage));
        if (canViewProfile(player, view.snapshot().uuid())) {
            holder.inventory.setItem(51, item(Material.BOOK, legacy(player, BestsMessages.BACK, MessageArgs.empty()), List.of()));
        }
        holder.inventory.setItem(53, item(Material.BARRIER, legacy(player, StatsMessages.CLOSE, MessageArgs.empty()), List.of()));
        player.openInventory(holder.inventory);
        if (BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            inventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
        }
    }

    private void sendChat(CommandSender sender, BestsView view) {
        plugin.getLanguageService().send(sender, BestsMessages.HEADER, MessageArgs.builder().untrusted("player", view.snapshot().name())
                .untrusted("period", plain(sender, BestsMessages.period(view.state().period())))
                .untrusted("page", number(sender, view.state().page())).untrusted("pages", number(sender, view.pages())).build());
        for (TextLine line : coverageLines(sender, view)) {
            plugin.getLanguageService().send(sender, line.key(), line.arguments());
        }
        if (view.records().isEmpty()) {
            plugin.getLanguageService().send(sender, BestsMessages.EMPTY);
        }
        int start = (view.state().page() - 1) * CONSOLE_PAGE_SIZE;
        for (PersonalBest best : view.records().subList(start, Math.min(start + CONSOLE_PAGE_SIZE, view.records().size()))) {
            for (TextLine line : recordLines(sender, best)) {
                plugin.getLanguageService().send(sender, line.key(), line.arguments());
            }
        }
    }

    private List<TextLine> recordLines(CommandSender sender, PersonalBest best) {
        List<TextLine> lines = new ArrayList<>(8);
        lines.add(new TextLine(StatsMessages.ENTRY, MessageArgs.builder().untrusted("stat", plain(sender, StatsMessages.label(best.statistic())))
                .untrusted("value", StatsFormatter.format(best.statistic(), best.value(), plugin.getLanguageService(), sender)).build()));
        if (best.period() == PersonalBestPeriod.SESSION) {
            lines.add(new TextLine(BestsMessages.SESSION_START, args("date", date(sender, best.startedAt()))));
            if (best.endsAt() > 0) {
                lines.add(new TextLine(BestsMessages.SESSION_END, args("date", date(sender, best.endsAt()))));
            }
        } else {
            lines.add(new TextLine(StatsMessages.PERIOD_WINDOW, MessageArgs.builder()
                    .untrusted("start", date(sender, best.startedAt())).untrusted("end", date(sender, best.endsAt())).build()));
        }
        lines.add(new TextLine(best.recordedSince() > 0 ? StatsMessages.COVERAGE_SINCE : StatsMessages.COVERAGE_UNKNOWN,
                best.recordedSince() > 0 ? args("date", date(sender, best.recordedSince())) : MessageArgs.empty()));
        if (best.recordedSince() > best.startedAt()) {
            lines.add(new TextLine(StatsMessages.PERIOD_PARTIAL, MessageArgs.empty()));
        }
        lines.add(new TextLine(BestsMessages.OBSERVED, args("date", date(sender, best.observedAt()))));
        lines.add(new TextLine(best.inProgress() ? BestsMessages.IN_PROGRESS : BestsMessages.COMPLETE, MessageArgs.empty()));
        if (best.recovered()) {
            lines.add(new TextLine(BestsMessages.RECOVERED, MessageArgs.empty()));
        }
        if (best.statistic() == StatKey.ACTIVE_TIME || best.statistic() == StatKey.AFK_TIME) {
            lines.add(new TextLine(StatsMessages.ACTIVITY_HINT, MessageArgs.empty()));
        }
        return List.copyOf(lines);
    }

    private List<TextLine> coverageLines(CommandSender sender, BestsView view) {
        long since = view.snapshot().trackingSince();
        return List.of(new TextLine(since > 0 ? BestsMessages.TRACKING_SINCE : BestsMessages.TRACKING_UNKNOWN,
                        since > 0 ? args("date", date(sender, since)) : MessageArgs.empty()),
                new TextLine(StatsMessages.PERIOD_ZONE, args("zone", view.snapshot().historyZone().getId())),
                new TextLine(BestsMessages.COVERAGE, MessageArgs.empty()),
                new TextLine(BestsMessages.RETENTION, MessageArgs.empty()),
                new TextLine(view.state().period() == PersonalBestPeriod.SESSION
                        ? BestsMessages.SESSION_ELIGIBLE : BestsMessages.CALENDAR_ELIGIBLE, MessageArgs.empty()));
    }

    private boolean permitted(CommandSender sender) {
        if (closed) {
            return false;
        }
        if (!sender.hasPermission("static.bests")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return false;
        }
        return true;
    }

    private boolean canView(CommandSender sender, UUID target) {
        return sender.hasPermission("static.bests") && (!(sender instanceof Player player)
                || player.getUniqueId().equals(target) || sender.hasPermission("static.bests.others"))
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

    private String number(CommandSender sender, long value) {
        return StatsFormatter.number(value, plugin.getLanguageService(), sender);
    }

    private String date(CommandSender sender, long value) {
        return StatsFormatter.date(value, plugin.getLanguageService(), sender);
    }

    private String plain(CommandSender sender, TextKey key) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key)).plain();
    }

    private String legacy(CommandSender sender, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key, arguments)).legacy();
    }

    private MessageArgs args(String key, String value) {
        return MessageArgs.builder().untrusted(key, value).build();
    }

    private Material icon(StatKey statistic) {
        return switch (statistic.category()) {
            case "building" -> Material.IRON_PICKAXE;
            case "connections" -> Material.CLOCK;
            case "combat" -> Material.IRON_SWORD;
            case "archery" -> Material.BOW;
            case "movement" -> Material.LEATHER_BOOTS;
            default -> Material.BOOK;
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

    private record ViewState(String target, PersonalBestPeriod period, int page) {
    }

    private record BestsView(PersonalBestSnapshot snapshot, ViewState state, List<PersonalBest> records, int pages) {
    }

    private record TextLine(TextKey key, MessageArgs arguments) {
    }

    private static final class PendingView {
        private final Inventory inventory;

        private PendingView(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private static final class BestsHolder implements InventoryHolder {
        private final UUID viewer;
        private final BestsView view;
        private Inventory inventory;

        private BestsHolder(UUID viewer, BestsView view) {
            this.viewer = viewer;
            this.view = view;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
