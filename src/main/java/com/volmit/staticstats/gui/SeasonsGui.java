package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.localization.FormatMessages;
import com.volmit.staticstats.localization.SeasonMessages;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.StatsFormatter;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.season.SeasonProfileSnapshot;
import com.volmit.staticstats.season.SeasonRankingSnapshot;
import com.volmit.staticstats.season.SeasonSnapshot;
import com.volmit.staticstats.season.SeasonStatus;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankingQualification;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class SeasonsGui implements Listener {
    private static final int PAGE_SIZE = 27;
    private static final int CONSOLE_PAGE_SIZE = 5;

    private final StaticPlugin plugin;
    private final Map<UUID, PendingView> pending = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitInventoryShutdown.View> inventories = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public SeasonsGui(StaticPlugin plugin) {
        this.plugin = plugin;
    }

    public void close() {
        closed = true;
        pending.clear();
        BukkitInventoryShutdown.drain(plugin, inventories.values());
        inventories.clear();
    }

    public void catalog(CommandSender sender, int page) {
        if (!permitted(sender)) {
            return;
        }
        if (page < 1) {
            failure(sender, SeasonMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        Consumer<Runnable> dispatch = dispatcher(sender);
        PendingView request = sender instanceof Player player
                ? new PendingView(BukkitInventoryViews.top(player.getOpenInventory())) : null;
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        if (viewer != null) {
            pending.put(viewer, request);
        }
        plugin.executeAsync(() -> {
            List<SeasonSnapshot> seasons = plugin.getStatsService().seasons();
            dispatch.accept(() -> {
                if (closed || !sender.hasPermission("static.seasons")) {
                    return;
                }
                if (sender instanceof Player player && (!pending.remove(viewer, request)
                        || BukkitInventoryViews.top(player.getOpenInventory()) != request.inventory)) {
                    return;
                }
                int limit = sender instanceof Player ? PAGE_SIZE : CONSOLE_PAGE_SIZE;
                int pages = Math.max(1, (seasons.size() + limit - 1) / limit);
                if (page > pages) {
                    failure(sender, SeasonMessages.INVALID_PAGE, MessageArgs.empty());
                } else if (sender instanceof Player player) {
                    openOwned(player, seasons, page, pages);
                } else {
                    send(sender, SeasonMessages.HEADER, pages(sender, page, pages));
                    if (seasons.isEmpty()) {
                        send(sender, SeasonMessages.EMPTY, MessageArgs.empty());
                    }
                    for (SeasonSnapshot season : seasons.subList((page - 1) * limit, Math.min(page * limit, seasons.size()))) {
                        describe(sender, season);
                    }
                }
            });
        });
    }

    public void profile(CommandSender sender, String id, String target) {
        if (!permitted(sender)) {
            return;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        if (target.equalsIgnoreCase("self") && viewer == null) {
            failure(sender, StaticMessages.PLAYER_ONLY, MessageArgs.empty());
            return;
        }
        Consumer<Runnable> dispatch = dispatcher(sender);
        plugin.executeAsync(() -> {
            Optional<SeasonSnapshot> event = plugin.getStatsService().season(id);
            Optional<PlayerStatsSnapshot> player = target.equalsIgnoreCase("self")
                    ? plugin.getStatsService().snapshot(viewer, StatsPeriod.LIFETIME)
                    : plugin.getStatsService().find(target, StatsPeriod.LIFETIME);
            Optional<SeasonProfileSnapshot> selected = player.flatMap(value -> plugin.getStatsService().seasonSnapshot(value.uuid(), id));
            dispatch.accept(() -> {
                if (!permitted(sender)) {
                    return;
                }
                if (event.isEmpty()) {
                    failure(sender, SeasonMessages.UNKNOWN, args("id", id));
                } else if (player.isEmpty()) {
                    failure(sender, StatsMessages.UNKNOWN_PLAYER, args("player", target));
                } else if (viewer != null && !viewer.equals(player.get().uuid()) && !sender.hasPermission("static.seasons.others")) {
                    failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
                } else if (!plugin.getProfileVisibility().canView(sender, player.get().uuid())) {
                    failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
                } else if (selected.isEmpty()) {
                    describe(sender, event.get());
                    send(sender, SeasonMessages.NO_PROFILE, args("player", player.get().name()));
                } else {
                    showProfile(sender, selected.get());
                }
            });
        });
    }

    public void top(CommandSender sender, String id, int page) {
        if (!permitted(sender)) {
            return;
        }
        if (page < 1) {
            failure(sender, StatsMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        Consumer<Runnable> dispatch = dispatcher(sender);
        plugin.executeAsync(() -> {
            if (plugin.getStatsService().season(id).isEmpty()) {
                dispatch.accept(() -> {
                    if (permitted(sender)) {
                        failure(sender, SeasonMessages.UNKNOWN, args("id", id));
                    }
                });
                return;
            }
            plugin.getStatsService().seasonRanking(id).whenComplete((ranking, error) -> dispatch.accept(() -> {
                if (!permitted(sender)) {
                    return;
                }
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Could not load season ranking " + id, error);
                    failure(sender, SeasonMessages.FAILED, MessageArgs.empty());
                } else {
                    describe(sender, ranking.season());
                    plugin.getStatsGui().seasonRanking(sender, ranking.ranking(), page);
                    if (ranking.season().status() == SeasonStatus.ARCHIVED) {
                        winners(sender, ranking);
                    }
                }
            }));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(BukkitInventoryViews.top(event.getView()).getHolder() instanceof CatalogHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.viewer.equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        boolean ranking = event.isRightClick();
        FoliaScheduler.runEntity(plugin, player, () -> clickOwned(player, holder, slot, ranking), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (BukkitInventoryViews.top(event.getView()).getHolder() instanceof CatalogHolder) {
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

    private void clickOwned(Player player, CatalogHolder holder, int slot, boolean ranking) {
        if (closed || !player.isOnline() || BukkitInventoryViews.top(player.getOpenInventory()) != holder.inventory) {
            return;
        }
        if (!permitted(player) || slot == 53) {
            player.closeInventory();
        } else if (slot == 45 && holder.page > 1) {
            catalog(player, holder.page - 1);
        } else if (slot == 46 && holder.page < holder.pages) {
            catalog(player, holder.page + 1);
        } else if (slot == 49) {
            catalog(player, holder.page);
        } else if (slot >= 0 && slot < holder.seasons.size()) {
            String id = holder.seasons.get(slot).definition().id();
            if (ranking) {
                top(player, id, 1);
            } else {
                profile(player, id, "self");
            }
        }
    }

    private void openOwned(Player player, List<SeasonSnapshot> seasons, int page, int pages) {
        CatalogHolder holder = new CatalogHolder(new CatalogState(player.getUniqueId(), page, pages,
                List.copyOf(seasons.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, seasons.size())))));
        holder.inventory = Bukkit.createInventory(holder, 54, legacy(player, SeasonMessages.TITLE, MessageArgs.empty()));
        for (int index = 0; index < holder.seasons.size(); index++) {
            SeasonSnapshot season = holder.seasons.get(index);
            List<String> lore = new ArrayList<>();
            for (MessageLine line : description(player, season)) {
                lore.add(legacy(player, line.key(), line.arguments()));
            }
            lore.add(legacy(player, SeasonMessages.HINT, MessageArgs.empty()));
            holder.inventory.setItem(index, item(season.status() == SeasonStatus.ARCHIVED ? Material.BOOK : Material.CLOCK,
                    legacy(player, SeasonMessages.NAME, name(season)), lore));
        }
        if (seasons.isEmpty()) {
            holder.inventory.setItem(13, item(Material.PAPER, legacy(player, SeasonMessages.EMPTY, MessageArgs.empty()), List.of()));
        }
        if (page > 1) {
            holder.inventory.setItem(45, item(Material.ARROW, legacy(player, StatsMessages.PREVIOUS, MessageArgs.empty()), List.of()));
        }
        if (page < pages) {
            holder.inventory.setItem(46, item(Material.ARROW, legacy(player, StatsMessages.NEXT, MessageArgs.empty()), List.of()));
        }
        holder.inventory.setItem(49, item(Material.CLOCK, legacy(player, StatsMessages.REFRESH, MessageArgs.empty()), List.of()));
        holder.inventory.setItem(50, item(Material.PAPER, legacy(player, SeasonMessages.HEADER, pages(player, page, pages)), List.of()));
        holder.inventory.setItem(53, item(Material.BARRIER, legacy(player, StatsMessages.CLOSE, MessageArgs.empty()), List.of()));
        player.openInventory(holder.inventory);
        if (BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            inventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
        }
    }

    private void showProfile(CommandSender sender, SeasonProfileSnapshot data) {
        send(sender, SeasonMessages.PROFILE, MessageArgs.builder().untrusted("player", data.profile().name())
                .untrusted("name", data.season().definition().name()).build());
        describe(sender, data.season());
        StatKey statistic = data.season().definition().rules().statistic();
        send(sender, StatsMessages.ENTRY, statisticValue(sender, statistic, data.profile().value(statistic)));
        Optional<RankingQualification> qualification = data.season().definition().rules().rankingRules().evaluate(statistic, data.profile());
        if (qualification.isPresent()) {
            RankingQualification result = qualification.get();
            send(sender, StatsMessages.RATIO_NUMERATOR, sampleValue(sender, result.numeratorKey(), result.numerator()));
            send(sender, StatsMessages.RATIO_DENOMINATOR, sampleValue(sender, result.denominatorKey(), result.denominator()));
            send(sender, StatsMessages.RATIO_REQUIREMENT, sampleValue(sender, result.denominatorKey(), result.minimum()));
            send(sender, StatsMessages.RATIO_POSITIVE, MessageArgs.empty());
            send(sender, result.qualified() ? StatsMessages.RATIO_QUALIFIED : StatsMessages.RATIO_UNQUALIFIED, MessageArgs.empty());
        }
    }

    private void describe(CommandSender sender, SeasonSnapshot season) {
        send(sender, SeasonMessages.NAME, name(season));
        for (MessageLine line : description(sender, season)) {
            send(sender, line.key(), line.arguments());
        }
    }

    private List<MessageLine> description(CommandSender sender, SeasonSnapshot season) {
        List<MessageLine> lines = new ArrayList<>();
        lines.add(new MessageLine(SeasonMessages.STATUS, args("status", plain(sender, switch (season.status()) {
            case SCHEDULED -> SeasonMessages.SCHEDULED;
            case ACTIVE -> SeasonMessages.ACTIVE;
            case ARCHIVED -> SeasonMessages.ARCHIVED;
        }))));
        lines.add(new MessageLine(SeasonMessages.SCORE, args("stat", plain(sender, StatsMessages.label(season.definition().rules().statistic())))));
        lines.add(new MessageLine(StatsMessages.PERIOD_WINDOW, MessageArgs.builder()
                .untrusted("start", date(sender, season.definition().startsAt().toEpochMilli()))
                .untrusted("end", date(sender, season.definition().endsAt().toEpochMilli())).build()));
        lines.add(new MessageLine(SeasonMessages.PARTICIPANTS, args("count", number(sender, season.participantCount()))));
        lines.add(new MessageLine(season.recordedSince() > 0 ? SeasonMessages.COVERAGE : SeasonMessages.NO_COVERAGE,
                season.recordedSince() > 0 ? args("date", date(sender, season.recordedSince())) : MessageArgs.empty()));
        lines.add(new MessageLine(season.status() == SeasonStatus.ARCHIVED ? SeasonMessages.FROZEN : SeasonMessages.RULES,
                season.status() == SeasonStatus.ARCHIVED ? args("date", date(sender, season.completedAt())) : MessageArgs.empty()));
        return lines;
    }

    private void winners(CommandSender sender, SeasonRankingSnapshot data) {
        List<String> visibleWinners = data.winners().stream()
                .filter(winner -> sender.hasPermission("static.privacy.bypass")
                        || plugin.getProfileVisibility().isServerVisible(winner.profile().uuid()))
                .map(winner -> winner.profile().name()).toList();
        if (visibleWinners.isEmpty()) {
            send(sender, SeasonMessages.NO_WINNERS, MessageArgs.empty());
            return;
        }
        int limit = plugin.getConfigService().runtime().leaderboardLimit();
        String names = String.join(", ", visibleWinners.stream().limit(limit).toList());
        if (visibleWinners.size() > limit) {
            names += " …";
        }
        send(sender, SeasonMessages.WINNERS, args("players", names));
    }

    private MessageArgs statisticValue(CommandSender sender, StatKey statistic, double value) {
        return MessageArgs.builder().untrusted("stat", plain(sender, StatsMessages.label(statistic)))
                .untrusted("value", StatsFormatter.format(statistic, value, plugin.getLanguageService(), sender)).build();
    }

    private MessageArgs sampleValue(CommandSender sender, StatKey statistic, double value) {
        String formatted = statistic == StatKey.ONLINE_TIME
                ? ComponentText.markup(plugin.getLanguageService().render(sender, FormatMessages.HOURS,
                        args("value", StatsFormatter.decimal(value / 3_600_000D, plugin.getLanguageService(), sender)))).plain()
                : StatsFormatter.decimal(value, plugin.getLanguageService(), sender);
        return MessageArgs.builder().untrusted("stat", plain(sender, StatsMessages.label(statistic))).untrusted("value", formatted).build();
    }

    private boolean permitted(CommandSender sender) {
        if (closed) {
            return false;
        }
        if (!sender.hasPermission("static.seasons")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return false;
        }
        return true;
    }

    private Consumer<Runnable> dispatcher(CommandSender sender) {
        if (sender instanceof Entity entity) {
            return action -> FoliaScheduler.runEntity(plugin, entity, action);
        }
        if (sender instanceof BlockCommandSender blockSender) {
            Location location = blockSender.getBlock().getLocation();
            return action -> FoliaScheduler.runRegion(plugin, location, action);
        }
        return Runnable::run;
    }

    private MessageArgs pages(CommandSender sender, int page, int pages) {
        return MessageArgs.builder().untrusted("page", number(sender, page)).untrusted("pages", number(sender, pages)).build();
    }

    private String number(CommandSender sender, int number) {
        return StatsFormatter.number(number, plugin.getLanguageService(), sender);
    }

    private String date(CommandSender sender, long timestamp) {
        return StatsFormatter.date(timestamp, plugin.getLanguageService(), sender);
    }

    private String plain(CommandSender sender, TextKey key) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key)).plain();
    }

    private String legacy(CommandSender sender, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key, arguments)).legacy();
    }

    private void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        plugin.getLanguageService().send(sender, key, arguments);
    }

    private void failure(CommandSender sender, TextKey key, MessageArgs arguments) {
        plugin.getPresentationService().command(sender, key, arguments, FeedbackTone.FAILURE);
    }

    private static MessageArgs args(String key, String value) {
        return MessageArgs.builder().untrusted(key, value).build();
    }

    private static MessageArgs name(SeasonSnapshot season) {
        return MessageArgs.builder().untrusted("id", season.definition().id()).untrusted("name", season.definition().name()).build();
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

    private record MessageLine(TextKey key, MessageArgs arguments) { }
    private record CatalogState(UUID viewer, int page, int pages, List<SeasonSnapshot> seasons) { }

    private static final class PendingView {
        private final Inventory inventory;

        private PendingView(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private static final class CatalogHolder implements InventoryHolder {
        private final UUID viewer;
        private final int page;
        private final int pages;
        private final List<SeasonSnapshot> seasons;
        private Inventory inventory;

        private CatalogHolder(CatalogState state) {
            viewer = state.viewer();
            page = state.page();
            pages = state.pages();
            seasons = state.seasons();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
