package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.localization.MilestoneMessages;
import com.volmit.staticstats.localization.FormatMessages;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.StatsFormatter;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.milestone.MilestoneCompletion;
import com.volmit.staticstats.milestone.MilestoneCompletionEvent;
import com.volmit.staticstats.milestone.MilestoneDefinition;
import com.volmit.staticstats.milestone.MilestoneProgress;
import com.volmit.staticstats.milestone.MilestoneSettings;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.SessionEndReason;
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
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class MilestonesGui implements Listener {
    private static final int PAGE_SIZE = 27;
    private static final int CONSOLE_PAGE_SIZE = 5;

    private final StaticPlugin plugin;
    private final Map<UUID, PendingView> pending = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitInventoryShutdown.View> inventories = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public MilestonesGui(StaticPlugin plugin) {
        this.plugin = plugin;
    }

    public void visibilityChanged(UUID target) {
        for (BukkitInventoryShutdown.View view : inventories.values()) {
            FoliaScheduler.runEntity(plugin, view.player(), () -> {
                if (BukkitInventoryViews.top(view.player().getOpenInventory()) == view.inventory()
                        && view.inventory().getHolder() instanceof MilestoneHolder holder
                        && target.equals(holder.profile) && !canView(view.player(), target)) {
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

    public void show(CommandSender sender, String target, int page) {
        if (closed) {
            return;
        }
        if (!sender.hasPermission("static.milestones")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        if (viewer == null && target.equalsIgnoreCase("self")) {
            failure(sender, StaticMessages.PLAYER_ONLY, MessageArgs.empty());
            return;
        }
        if (page < 1) {
            failure(sender, MilestoneMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        Consumer<Runnable> dispatch = dispatcher(sender);
        PendingView request = sender instanceof Player player
                ? new PendingView(BukkitInventoryViews.top(player.getOpenInventory())) : null;
        if (viewer != null) {
            pending.put(viewer, request);
        }
        plugin.executeAsync(() -> load(sender, new ViewRequest(target, viewer, page, request), dispatch));
    }

    public void completed(MilestoneCompletionEvent event) {
        if (closed || event.completion().historical() || event.sessionId().isEmpty()) {
            return;
        }
        FoliaScheduler.runGlobal(plugin, () -> {
            Player player = Bukkit.getPlayer(event.playerId());
            if (player != null) {
                FoliaScheduler.runEntity(plugin, player, () -> notifyCompleted(player, event));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(BukkitInventoryViews.top(event.getView()).getHolder() instanceof MilestoneHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.viewer.equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        FoliaScheduler.runEntity(plugin, player, () -> handleClick(player, holder, slot), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (BukkitInventoryViews.top(event.getView()).getHolder() instanceof MilestoneHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        pending.remove(viewer);
        inventories.computeIfPresent(viewer, (uuid, view) -> view.inventory() == event.getInventory() ? null : view);
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

    private void load(CommandSender sender, ViewRequest request, Consumer<Runnable> dispatch) {
        try {
            Optional<PlayerStatsSnapshot> selected = request.target().equalsIgnoreCase("self")
                    ? plugin.getStatsService().snapshot(request.viewer(), StatsPeriod.LIFETIME)
                    : plugin.getStatsService().find(request.target(), StatsPeriod.LIFETIME);
            MilestoneData data = selected.map(profile -> data(profile,
                    plugin.getStatsService().milestones(profile.uuid()),
                    plugin.getStatsService().milestoneCompletions(profile.uuid()),
                    plugin.getConfigService().runtime().milestoneSettings())).orElse(null);
            dispatch.accept(() -> deliver(sender, request, data));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load milestones for " + request.target(), exception);
            dispatch.accept(() -> {
                if (accept(sender, request)) {
                    failure(sender, MilestoneMessages.FAILED, MessageArgs.empty());
                }
            });
        }
    }

    private void deliver(CommandSender sender, ViewRequest request, MilestoneData data) {
        if (!accept(sender, request)) {
            return;
        }
        if (data == null) {
            failure(sender, StatsMessages.UNKNOWN_PLAYER, args("player", request.target()));
            return;
        }
        if (!data.settings().equals(plugin.getConfigService().runtime().milestoneSettings())) {
            show(sender, request.target(), request.page());
            return;
        }
        if (!plugin.getProfileVisibility().canView(sender, data.profile().uuid())
                || sender instanceof Player player && !canView(player, data.profile().uuid())) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        int limit = sender instanceof Player ? PAGE_SIZE : CONSOLE_PAGE_SIZE;
        int pages = Math.max(1, (data.badges().size() + limit - 1) / limit);
        if (request.page() > pages) {
            failure(sender, MilestoneMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        if (sender instanceof Player player) {
            openOwned(player, data, request.page(), pages);
        } else {
            showText(sender, data, request.page(), pages);
        }
    }

    private boolean accept(CommandSender sender, ViewRequest request) {
        if (closed) {
            return false;
        }
        if (sender instanceof Player player) {
            if (!pending.remove(request.viewer(), request.pending()) || !player.isOnline()
                    || BukkitInventoryViews.top(player.getOpenInventory()) != request.pending().inventory) {
                return false;
            }
        }
        return sender.hasPermission("static.milestones");
    }

    private void handleClick(Player player, MilestoneHolder holder, int slot) {
        if (!viewing(player, holder)) {
            return;
        }
        if (!canView(player, holder.profile)) {
            player.closeInventory();
            failure(player, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        if (slot == 53) {
            player.closeInventory();
        } else if (slot == 48) {
            plugin.getStatsGui().show(player, holder.profile.toString(), StatsPeriod.LIFETIME);
        } else if (slot == 49) {
            show(player, holder.profile.toString(), holder.page);
        } else if (slot == 45 && holder.page > 1) {
            show(player, holder.profile.toString(), holder.page - 1);
        } else if (slot == 46 && holder.page < holder.pages) {
            show(player, holder.profile.toString(), holder.page + 1);
        }
    }

    private void openOwned(Player player, MilestoneData data, int page, int pages) {
        MilestoneHolder holder = new MilestoneHolder(new MenuState(player.getUniqueId(), data.profile().uuid(), page, pages));
        holder.inventory = Bukkit.createInventory(holder, 54, legacy(player, MilestoneMessages.TITLE, args("player", data.profile().name())));
        int offset = (page - 1) * PAGE_SIZE;
        for (int index = offset; index < Math.min(data.badges().size(), offset + PAGE_SIZE); index++) {
            Badge badge = data.badges().get(index);
            holder.inventory.setItem(index - offset, item(badge.completion() == null ? Material.PAPER : Material.GOLD_NUGGET,
                    legacy(player, MilestoneMessages.ENTRY, badgeArguments(player, badge.definition())), lore(player, badge)));
        }
        if (data.badges().isEmpty()) {
            holder.inventory.setItem(13, item(Material.PAPER, legacy(player, MilestoneMessages.EMPTY, MessageArgs.empty()), List.of()));
        }
        if (page > 1) {
            holder.inventory.setItem(45, item(Material.ARROW, legacy(player, StatsMessages.PREVIOUS, MessageArgs.empty()), List.of()));
        }
        if (page < pages) {
            holder.inventory.setItem(46, item(Material.ARROW, legacy(player, StatsMessages.NEXT, MessageArgs.empty()), List.of()));
        }
        if (player.hasPermission("static.stats") && (player.getUniqueId().equals(data.profile().uuid()) || player.hasPermission("static.stats.others"))) {
            holder.inventory.setItem(48, item(Material.BOOK, legacy(player, MilestoneMessages.BACK, MessageArgs.empty()), List.of()));
        }
        holder.inventory.setItem(49, item(Material.CLOCK, legacy(player, MilestoneMessages.REFRESH, MessageArgs.empty()), List.of()));
        List<String> summary = new ArrayList<>();
        summary.add(legacy(player, MilestoneMessages.SCOPE, MessageArgs.empty()));
        summary.add(legacy(player, MilestoneMessages.HEADER, header(player, data, page, pages)));
        if (!plugin.getConfigService().runtime().milestoneSettings().enabled()) {
            summary.add(legacy(player, MilestoneMessages.PAUSED, MessageArgs.empty()));
        }
        holder.inventory.setItem(50, item(Material.GOLD_INGOT, legacy(player, MilestoneMessages.SUMMARY, summary(player, data)), summary));
        holder.inventory.setItem(53, item(Material.BARRIER, legacy(player, StatsMessages.CLOSE, MessageArgs.empty()), List.of()));
        player.openInventory(holder.inventory);
        if (BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            inventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
        }
    }

    private void showText(CommandSender sender, MilestoneData data, int page, int pages) {
        plugin.getLanguageService().send(sender, MilestoneMessages.HEADER, header(sender, data, page, pages));
        plugin.getLanguageService().send(sender, MilestoneMessages.SUMMARY, summary(sender, data));
        plugin.getLanguageService().send(sender, MilestoneMessages.SCOPE);
        if (!plugin.getConfigService().runtime().milestoneSettings().enabled()) {
            plugin.getLanguageService().send(sender, MilestoneMessages.PAUSED);
        }
        if (data.badges().isEmpty()) {
            plugin.getLanguageService().send(sender, MilestoneMessages.EMPTY);
        }
        int offset = (page - 1) * CONSOLE_PAGE_SIZE;
        for (int index = offset; index < Math.min(data.badges().size(), offset + CONSOLE_PAGE_SIZE); index++) {
            Badge badge = data.badges().get(index);
            plugin.getLanguageService().send(sender, MilestoneMessages.ENTRY, badgeArguments(sender, badge.definition()));
            for (MessageLine line : lines(sender, badge)) {
                plugin.getLanguageService().send(sender, line.key(), line.arguments());
            }
        }
    }

    private void notifyCompleted(Player player, MilestoneCompletionEvent event) {
        if (!closed && player.isOnline() && player.hasPermission("static.milestones")
                && plugin.getConfigService().runtime().milestoneSettings().enabled()
                && plugin.getConfigService().runtime().milestoneNotifications()
                && plugin.getStatsService().sessions(event.playerId()).stream().anyMatch(session ->
                        session.endReason() == SessionEndReason.OPEN && event.sessionId().filter(session.id()::equals).isPresent())) {
            plugin.getLanguageService().send(player, MilestoneMessages.NOTIFICATION, badgeArguments(player, event.completion().definition()));
        }
    }

    private List<String> lore(CommandSender sender, Badge badge) {
        List<String> result = new ArrayList<>();
        for (MessageLine line : lines(sender, badge)) {
            result.add(legacy(sender, line.key(), line.arguments()));
        }
        return result;
    }

    private List<MessageLine> lines(CommandSender sender, Badge badge) {
        List<MessageLine> result = new ArrayList<>();
        result.add(new MessageLine(MilestoneMessages.PROGRESS, MessageArgs.builder()
                .untrusted("value", format(sender, badge.definition(), badge.value()))
                .untrusted("target", format(sender, badge.definition(), badge.definition().threshold())).build()));
        if (badge.completion() == null) {
            result.add(new MessageLine(MilestoneMessages.IN_PROGRESS, MessageArgs.empty()));
            if (badge.next()) {
                result.add(new MessageLine(MilestoneMessages.NEXT_TARGET, MessageArgs.empty()));
            }
        } else {
            result.add(new MessageLine(MilestoneMessages.COMPLETE, args("date",
                    StatsFormatter.date(badge.completion().completedAt(), plugin.getLanguageService(), sender))));
            if (badge.completion().historical()) {
                result.add(new MessageLine(MilestoneMessages.HISTORICAL, MessageArgs.empty()));
            }
        }
        if (badge.archived()) {
            result.add(new MessageLine(MilestoneMessages.ARCHIVED, MessageArgs.empty()));
        }
        return result;
    }

    private MessageArgs badgeArguments(CommandSender sender, MilestoneDefinition definition) {
        return MessageArgs.builder().untrusted("stat", ComponentText.markup(plugin.getLanguageService()
                        .render(sender, StatsMessages.label(definition.statistic()))).plain())
                .untrusted("target", format(sender, definition, definition.threshold())).build();
    }

    private MessageArgs header(CommandSender sender, MilestoneData data, int page, int pages) {
        return MessageArgs.builder().untrusted("player", data.profile().name()).untrusted("page", number(sender, page))
                .untrusted("pages", number(sender, pages)).build();
    }

    private MessageArgs summary(CommandSender sender, MilestoneData data) {
        return MessageArgs.builder().untrusted("completed", number(sender, data.completed()))
                .untrusted("targets", number(sender, data.active())).build();
    }

    private String format(CommandSender sender, MilestoneDefinition definition, double value) {
        StatKey statistic = definition.statistic();
        if (statistic == StatKey.ONLINE_TIME || statistic == StatKey.ACTIVE_TIME || statistic == StatKey.AFK_TIME) {
            if (value < 1_000) {
                return ComponentText.markup(plugin.getLanguageService().render(sender, MilestoneMessages.MILLISECONDS,
                        args("value", StatsFormatter.decimal(value, plugin.getLanguageService(), sender)))).plain();
            }
            if (value >= 1_000 && value % 60_000 == 0) {
                return StatsFormatter.format(statistic, value, plugin.getLanguageService(), sender);
            }
            return ComponentText.markup(plugin.getLanguageService().render(sender, FormatMessages.SECONDS,
                    args("value", StatsFormatter.decimal(value / 1_000, plugin.getLanguageService(), sender)))).plain();
        }
        String amount = StatsFormatter.decimal(value, plugin.getLanguageService(), sender);
        return statistic.category().equals("movement") ? amount + " m" : amount;
    }

    private String number(CommandSender sender, int value) {
        return StatsFormatter.number(value, plugin.getLanguageService(), sender);
    }

    private String legacy(CommandSender sender, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key, arguments)).legacy();
    }

    private void failure(CommandSender sender, TextKey key, MessageArgs arguments) {
        plugin.getPresentationService().command(sender, key, arguments, FeedbackTone.FAILURE);
    }

    private boolean canView(Player player, UUID profile) {
        return player.hasPermission("static.milestones")
                && (player.getUniqueId().equals(profile) || player.hasPermission("static.milestones.others"))
                && plugin.getProfileVisibility().canView(player, profile);
    }

    private boolean viewing(Player player, MilestoneHolder holder) {
        return !closed && player.isOnline() && BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory;
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

    private static MilestoneData data(PlayerStatsSnapshot profile, List<MilestoneProgress> progress,
                                      List<MilestoneCompletion> completions, MilestoneSettings settings) {
        Map<String, MilestoneCompletion> completed = new LinkedHashMap<>();
        for (MilestoneCompletion completion : completions) {
            completed.put(completion.definition().id(), completion);
        }
        List<Badge> badges = new ArrayList<>(progress.size() + completed.size());
        Set<StatKey> next = EnumSet.noneOf(StatKey.class);
        for (MilestoneProgress entry : progress) {
            MilestoneCompletion completion = completed.remove(entry.definition().id());
            completion = completion == null ? entry.completion().orElse(null) : completion;
            badges.add(new Badge(entry.definition(), entry.value(), completion, false,
                    completion == null && next.add(entry.definition().statistic())));
        }
        for (MilestoneCompletion completion : completed.values()) {
            boolean archived = !settings.thresholds().getOrDefault(completion.definition().statistic(), List.of()).contains(completion.definition().threshold());
            badges.add(new Badge(completion.definition(), completion.observedValue(), completion, archived, false));
        }
        int count = 0;
        for (Badge badge : badges) {
            if (badge.completion() != null) {
                count++;
            }
        }
        return new MilestoneData(profile, List.copyOf(badges), progress.size(), count, settings);
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

    private record ViewRequest(String target, UUID viewer, int page, PendingView pending) { }
    private record Badge(MilestoneDefinition definition, double value, MilestoneCompletion completion, boolean archived, boolean next) { }
    private record MilestoneData(PlayerStatsSnapshot profile, List<Badge> badges, int active, int completed, MilestoneSettings settings) { }
    private record MessageLine(TextKey key, MessageArgs arguments) { }
    private record MenuState(UUID viewer, UUID profile, int page, int pages) { }

    private static final class PendingView {
        private final Inventory inventory;

        private PendingView(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private static final class MilestoneHolder implements InventoryHolder {
        private final UUID viewer;
        private final UUID profile;
        private final int page;
        private final int pages;
        private Inventory inventory;

        private MilestoneHolder(MenuState state) {
            viewer = state.viewer();
            profile = state.profile();
            page = state.page();
            pages = state.pages();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
