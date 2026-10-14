package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.FormatMessages;
import com.volmit.staticstats.localization.StatsFormatter;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.localization.MilestoneMessages;
import com.volmit.staticstats.localization.SeasonMessages;
import com.volmit.staticstats.localization.DetailMessages;
import com.volmit.staticstats.localization.BestsMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.PeriodStatsSnapshot;
import com.volmit.staticstats.stats.PeriodWindow;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.SessionEndReason;
import com.volmit.staticstats.stats.StatsPeriod;
import com.volmit.staticstats.stats.RankedPlayer;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.RankingQualification;
import com.volmit.staticstats.stats.RankingRequirement;
import com.volmit.staticstats.stats.RankingRules;
import com.volmit.staticstats.stats.StatKey;
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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class StatsGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int SESSION_PAGE_SIZE = 5;

    private final StaticPlugin plugin;
    private final ProfilePreferences preferences;
    private final Map<UUID, BukkitInventoryShutdown.View> openInventories = new ConcurrentHashMap<>();
    private final Map<UUID, PendingProfile> pendingProfiles = new ConcurrentHashMap<>();
    private final Set<UUID> pendingFavoriteWrites = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public StatsGui(StaticPlugin plugin) {
        this.plugin = plugin;
        preferences = new ProfilePreferences(plugin.getDataFolder().toPath().resolve("storage/profile-preferences.json"), plugin.getLogger());
    }

    public void visibilityChanged(UUID target) {
        for (BukkitInventoryShutdown.View view : openInventories.values()) {
            FoliaScheduler.runEntity(plugin, view.player(), () -> {
                if (BukkitInventoryViews.top(view.player().getOpenInventory()) == view.inventory()
                        && view.inventory().getHolder() instanceof ProfileHolder holder
                        && target.equals(holder.state.profile()) && !canView(view.player(), target)) {
                    view.player().closeInventory();
                }
            });
        }
    }

    public void close() {
        closed = true;
        pendingProfiles.clear();
        BukkitInventoryShutdown.drain(plugin, openInventories.values());
        openInventories.clear();
        preferences.close();
    }

    public void show(CommandSender sender, String target, StatsPeriod period) {
        Consumer<Runnable> dispatch = dispatcher(sender);
        if (sender instanceof Player player) {
            dispatch.accept(() -> requestProfile(player, target, period));
            return;
        }
        if (!sender.hasPermission("static.stats")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        if (target.equalsIgnoreCase("self")) {
            failure(sender, StaticMessages.PLAYER_ONLY, MessageArgs.empty());
            return;
        }
        plugin.executeAsync(() -> {
            Optional<PeriodStatsSnapshot> selected = findPeriod(target, null, period);
            dispatch.accept(() -> {
                if (!sender.hasPermission("static.stats")) {
                    return;
                }
                if (selected.isEmpty()) {
                    failure(sender, StatsMessages.UNKNOWN_PLAYER, args("player", target));
                    return;
                }
                PeriodStatsSnapshot data = selected.get();
                if (!current(data)) {
                    show(sender, target, period);
                    return;
                }
                PlayerStatsSnapshot profile = data.profile();
                if (!plugin.getProfileVisibility().canView(sender, profile.uuid())) {
                    failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
                    return;
                }
                plugin.getLanguageService().send(sender, StatsMessages.PROFILE, args("player", profile.name()));
                sendPeriod(sender, data.window(), data.historyZone(), data.recordedSince());
                for (StatKey statistic : StatKey.values()) {
                    plugin.getLanguageService().send(sender, StatsMessages.ENTRY, MessageArgs.builder()
                            .untrusted("stat", plain(sender, StatsMessages.label(statistic)))
                            .untrusted("value", format(sender, statistic, profile.value(statistic))).build());
                }
            });
        });
    }

    public void top(CommandSender sender, String id, int page, StatsPeriod period) {
        Consumer<Runnable> dispatch = dispatcher(sender);
        if (!sender.hasPermission("static.top")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        StatKey chosen;
        try {
            chosen = StatKey.parse(id);
        } catch (IllegalArgumentException exception) {
            failure(sender, StatsMessages.UNKNOWN_STAT, args("stat", id));
            return;
        }
        if (page < 1) {
            failure(sender, StatsMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        plugin.getStatsService().ranking(chosen, period).whenComplete((ranking, error) -> {
            if (error != null) {
                if (!closed) {
                    dispatch.accept(() -> failure(sender, StatsMessages.TOP_FAILED, MessageArgs.empty()));
                }
                return;
            }
            dispatch.accept(() -> showRanking(sender, ranking, page));
        });
    }

    public void sessions(CommandSender sender, String target, int page) {
        Consumer<Runnable> dispatch = dispatcher(sender);
        if (!sender.hasPermission("static.sessions")) {
            failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        if (target.equalsIgnoreCase("self") && viewer == null) {
            failure(sender, StaticMessages.PLAYER_ONLY, MessageArgs.empty());
            return;
        }
        if (page < 1) {
            failure(sender, StatsMessages.SESSIONS_INVALID_PAGE, MessageArgs.empty());
            return;
        }
        plugin.executeAsync(() -> {
            Optional<PlayerStatsSnapshot> selected = target.equalsIgnoreCase("self")
                    ? plugin.getStatsService().snapshot(viewer, StatsPeriod.LIFETIME)
                    : plugin.getStatsService().find(target, StatsPeriod.LIFETIME);
            List<PlayerSession> records = selected.isPresent() ? plugin.getStatsService().sessions(selected.get().uuid()) : List.of();
            dispatch.accept(() -> {
                if (!sender.hasPermission("static.sessions")) {
                    return;
                }
                if (selected.isEmpty()) {
                    failure(sender, StatsMessages.UNKNOWN_PLAYER, args("player", target));
                } else if (viewer != null && !viewer.equals(selected.get().uuid()) && !sender.hasPermission("static.sessions.others")) {
                    failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
                } else if (!plugin.getProfileVisibility().canView(sender, selected.get().uuid())) {
                    failure(sender, StaticMessages.NO_PERMISSION, MessageArgs.empty());
                } else {
                    showSessions(sender, selected.get(), records, page);
                }
            });
        });
    }

    public void seasonRanking(CommandSender sender, RankingSnapshot ranking, int page) {
        if (sender.hasPermission("static.seasons")) {
            renderRanking(sender, ranking, page, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(BukkitInventoryViews.top(event.getView()).getHolder() instanceof ProfileHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.state.viewer().equals(player.getUniqueId())) {
            return;
        }
        if (!canView(player, holder.state.profile())) {
            FoliaScheduler.runEntity(plugin, player, () -> {
                if (viewing(player, holder)) {
                    player.closeInventory();
                }
            }, 1L);
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.inventory.getSize()) {
            return;
        }
        if (slot == 53) {
            FoliaScheduler.runEntity(plugin, player, () -> {
                if (viewing(player, holder)) {
                    player.closeInventory();
                }
            }, 1L);
        } else if (slot == 45) {
            refresh(player, holder, holder.state.category(holder.state.category().next()));
        } else if (slot == 46) {
            refresh(player, holder, holder.state.favorites(!holder.state.favorites()));
        } else if (slot == 49) {
            refresh(player, holder, holder.state);
        } else if (slot == 50) {
            boolean bests = event.isRightClick();
            String permission = bests ? "static.bests" : "static.details";
            if (player.hasPermission(permission) && (player.getUniqueId().equals(holder.state.profile())
                    || player.hasPermission(permission + ".others"))) {
                FoliaScheduler.runEntity(plugin, player, () -> {
                    if (viewing(player, holder) && canView(player, holder.state.profile())) {
                        if (bests) {
                            plugin.getBestsGui().show(player, holder.state.profile().toString(), "day", 1);
                        } else {
                            plugin.getDetailGui().show(player, holder.state.profile().toString(), "all", 1);
                        }
                    }
                }, 1L);
            }
        } else if (slot == 51) {
            if (event.isRightClick() && player.hasPermission("static.seasons")) {
                FoliaScheduler.runEntity(plugin, player, () -> {
                    if (viewing(player, holder)) {
                        plugin.getSeasonsGui().catalog(player, 1);
                    }
                }, 1L);
            } else {
                StatsPeriod[] periods = StatsPeriod.values();
                refresh(player, holder, holder.state.period(periods[(holder.state.period().ordinal() + 1) % periods.length]));
            }
        } else if (slot == 52) {
            FoliaScheduler.runEntity(plugin, player, () -> {
                if (viewing(player, holder) && canView(player, holder.state.profile())) {
                    plugin.getMilestonesGui().show(player, holder.state.profile().toString(), 1);
                }
            }, 1L);
        } else if (slot == 47 && holder.state.page() > 0) {
            refresh(player, holder, holder.state.page(holder.state.page() - 1));
        } else if (slot == 48 && (holder.state.page() + 1) * PAGE_SIZE < holder.statistics.size()) {
            refresh(player, holder, holder.state.page(holder.state.page() + 1));
        } else if (slot < PAGE_SIZE) {
            int index = holder.state.page() * PAGE_SIZE + slot;
            if (index >= holder.statistics.size()) {
                return;
            }
            StatKey statistic = holder.statistics.get(index);
            if (event.isRightClick()) {
                toggleFavorite(player, holder, statistic);
            } else if (event.isLeftClick()) {
                top(player, statistic.id(), 1, holder.state.period());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (BukkitInventoryViews.top(event.getView()).getHolder() instanceof ProfileHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        pendingProfiles.remove(viewer);
        if (event.getInventory().getHolder() instanceof ProfileHolder) {
            openInventories.computeIfPresent(viewer, (id, view) -> view.inventory() == event.getInventory() ? null : view);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        pendingProfiles.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        pendingProfiles.remove(viewer);
        openInventories.remove(viewer);
    }

    private void requestProfile(Player player, String target, StatsPeriod period) {
        if (!player.hasPermission("static.stats")) {
            failure(player, StaticMessages.NO_PERMISSION, MessageArgs.empty());
            return;
        }
        UUID viewer = player.getUniqueId();
        PendingProfile request = new PendingProfile(BukkitInventoryViews.top(player.getOpenInventory()));
        pendingProfiles.put(viewer, request);
        plugin.executeAsync(() -> {
            Optional<PeriodStatsSnapshot> selected = findPeriod(target, viewer, period);
            preferences.ready().whenComplete((ignored, error) -> deliver(player, () -> {
                if (!pendingProfiles.remove(viewer, request)
                        || BukkitInventoryViews.top(player.getOpenInventory()) != request.inventory()) {
                    return;
                }
                if (selected.isEmpty()) {
                    failure(player, StatsMessages.UNKNOWN_PLAYER, args("player", target));
                    return;
                }
                PeriodStatsSnapshot data = selected.get();
                if (!canView(player, data.profile().uuid())) {
                    failure(player, StaticMessages.NO_PERMISSION, MessageArgs.empty());
                } else if (!current(data)) {
                    requestProfile(player, target, period);
                } else {
                    openOwned(player, data, new ProfileState(viewer, data.profile().uuid(), Category.ALL, false, 0, period));
                }
            }));
        });
    }

    private void toggleFavorite(Player player, ProfileHolder holder, StatKey statistic) {
        UUID viewer = holder.state.viewer();
        if (!pendingFavoriteWrites.add(viewer)) {
            return;
        }
        preferences.toggle(viewer, statistic).whenComplete((result, error) -> {
            pendingFavoriteWrites.remove(viewer);
            deliver(player, () -> {
                if (!viewing(player, holder) || !canView(player, holder.state.profile())) {
                    return;
                }
                if (error != null) {
                    failure(player, StatsMessages.FAVORITES_FAILED, MessageArgs.empty());
                } else if (result == ProfilePreferences.ToggleResult.LIMIT) {
                    failure(player, StatsMessages.FAVORITES_LIMIT, args("limit", number(player, ProfilePreferences.MAX_FAVORITES)));
                } else {
                    refresh(player, holder, holder.state);
                }
            });
        });
    }

    private Optional<PeriodStatsSnapshot> findPeriod(String target, UUID viewer, StatsPeriod period) {
        if (target.equalsIgnoreCase("self")) {
            return plugin.getStatsService().periodSnapshot(viewer, period);
        }
        return plugin.getStatsService().find(target, StatsPeriod.LIFETIME)
                .flatMap(profile -> plugin.getStatsService().periodSnapshot(profile.uuid(), period));
    }

    private void refresh(Player player, ProfileHolder holder, ProfileState next) {
        FoliaScheduler.runEntity(plugin, player, () -> {
            if (!viewing(player, holder) || !canView(player, next.profile())) {
                return;
            }
            PendingProfile request = new PendingProfile(holder.inventory);
            pendingProfiles.put(player.getUniqueId(), request);
            plugin.executeAsync(() -> {
                Optional<PeriodStatsSnapshot> selected = plugin.getStatsService().periodSnapshot(next.profile(), next.period());
                deliver(player, () -> {
                    if (!pendingProfiles.remove(player.getUniqueId(), request) || !viewing(player, holder)
                            || !canView(player, next.profile()) || selected.isEmpty()) {
                        return;
                    }
                    if (!current(selected.get())) {
                        refresh(player, holder, next);
                    } else {
                        openOwned(player, selected.get(), next);
                    }
                });
            });
        }, 1L);
    }

    private void openOwned(Player player, PeriodStatsSnapshot data, ProfileState requested) {
        PlayerStatsSnapshot profile = data.profile();
        if (closed || !player.isOnline() || !canView(player, profile.uuid())) {
            return;
        }
        List<StatKey> favorites = preferences.favorites(player.getUniqueId());
        RankingRules rules = plugin.getConfigService().runtime().rankingRules();
        List<StatKey> statistics = requested.favorites() ? favorites : requested.category().statistics();
        int lastPage = Math.max(0, (statistics.size() - 1) / PAGE_SIZE);
        ProfileState state = requested.page(Math.max(0, Math.min(requested.page(), lastPage)));
        ProfileHolder holder = new ProfileHolder(state, statistics);
        holder.inventory = Bukkit.createInventory(holder, 54, legacy(player, StatsMessages.PROFILE_TITLE, args("player", profile.name())));
        for (int index = state.page() * PAGE_SIZE; index < Math.min(statistics.size(), (state.page() + 1) * PAGE_SIZE); index++) {
            StatKey statistic = statistics.get(index);
            List<String> lore = new ArrayList<>(8);
            lore.add(legacy(player, StatsMessages.ENTRY, MessageArgs.builder()
                    .untrusted("stat", plain(player, StatsMessages.label(statistic)))
                    .untrusted("value", format(player, statistic, profile.value(statistic))).build()));
            rules.evaluate(statistic, profile).ifPresent(qualification -> addQualificationLore(player, qualification, lore));
            if (statistic == StatKey.ACTIVE_TIME || statistic == StatKey.AFK_TIME) {
                lore.add(legacy(player, StatsMessages.ACTIVITY_HINT, MessageArgs.empty()));
                long since = plugin.getStatsService().activityRecordedSince(profile.uuid());
                lore.add(legacy(player, since > 0 ? StatsMessages.ACTIVITY_SINCE : StatsMessages.ACTIVITY_COVERAGE,
                        since > 0 ? args("date", StatsFormatter.date(since, plugin.getLanguageService(), player)) : MessageArgs.empty()));
            }
            if (player.hasPermission("static.top")) {
                lore.add(legacy(player, StatsMessages.TOP_HINT, MessageArgs.empty()));
            }
            lore.add(legacy(player, !preferences.available() ? StatsMessages.FAVORITES_UNAVAILABLE
                    : favorites.contains(statistic) ? StatsMessages.FAVORITES_REMOVE : StatsMessages.FAVORITES_ADD, MessageArgs.empty()));
            holder.inventory.setItem(index % PAGE_SIZE, item(icon(statistic),
                    "§x§F§F§E§3§9§C" + plain(player, StatsMessages.label(statistic)), lore));
        }
        if (statistics.isEmpty()) {
            holder.inventory.setItem(22, item(Material.PAPER, legacy(player, StatsMessages.FAVORITES_EMPTY, MessageArgs.empty()),
                    List.of(legacy(player, StatsMessages.FAVORITES_EMPTY_HINT, MessageArgs.empty()))));
        }
        holder.inventory.setItem(45, item(Material.COMPASS, legacy(player, StatsMessages.CATEGORY,
                args("category", plain(player, state.category().label))), List.of(legacy(player, StatsMessages.CATEGORY_HINT, MessageArgs.empty()))));
        List<String> favoriteLore = List.of(legacy(player, preferences.available()
                ? state.favorites() ? StatsMessages.FAVORITES_BACK : StatsMessages.FAVORITES_SHOW
                : StatsMessages.FAVORITES_UNAVAILABLE, MessageArgs.empty()));
        holder.inventory.setItem(46, item(Material.NETHER_STAR, legacy(player, StatsMessages.FAVORITES, MessageArgs.builder()
                .untrusted("count", number(player, favorites.size())).untrusted("limit", number(player, ProfilePreferences.MAX_FAVORITES)).build()), favoriteLore));
        if (state.page() > 0) {
            holder.inventory.setItem(47, item(Material.ARROW, legacy(player, StatsMessages.PREVIOUS, MessageArgs.empty()), List.of()));
        }
        if (state.page() < lastPage) {
            holder.inventory.setItem(48, item(Material.ARROW, legacy(player, StatsMessages.NEXT, MessageArgs.empty()), List.of()));
        }
        holder.inventory.setItem(49, item(Material.CLOCK, legacy(player, StatsMessages.REFRESH, MessageArgs.empty()), List.of()));
        List<String> coverage = new ArrayList<>();
        for (PeriodLine line : periodLines(player, data.window(), data.historyZone(), data.recordedSince())) {
            coverage.add(legacy(player, line.key(), line.arguments()));
        }
        if (player.hasPermission("static.details") && (player.getUniqueId().equals(profile.uuid())
                || player.hasPermission("static.details.others"))) {
            coverage.add(legacy(player, DetailMessages.OPEN_HINT, MessageArgs.empty()));
        }
        if (player.hasPermission("static.bests") && (player.getUniqueId().equals(profile.uuid())
                || player.hasPermission("static.bests.others"))) {
            coverage.add(legacy(player, BestsMessages.OPEN_HINT, MessageArgs.empty()));
        }
        holder.inventory.setItem(50, item(Material.PAPER, legacy(player, StatsMessages.COVERAGE, MessageArgs.empty()), coverage));
        List<String> periodHints = new ArrayList<>(List.of(legacy(player, StatsMessages.PERIOD_HINT, MessageArgs.empty())));
        if (player.hasPermission("static.seasons")) {
            periodHints.add(legacy(player, SeasonMessages.OPEN_HINT, MessageArgs.empty()));
        }
        holder.inventory.setItem(51, item(Material.SUNFLOWER, legacy(player, StatsMessages.PERIOD_SELECT,
                args("period", plain(player, periodLabel(requested.period())))),
                periodHints));
        if (player.hasPermission("static.milestones") && (player.getUniqueId().equals(profile.uuid())
                || player.hasPermission("static.milestones.others"))) {
            holder.inventory.setItem(52, item(Material.GOLD_NUGGET, legacy(player, MilestoneMessages.OPEN, MessageArgs.empty()),
                    List.of(legacy(player, MilestoneMessages.OPEN_HINT, MessageArgs.empty()))));
        }
        holder.inventory.setItem(53, item(Material.BARRIER, legacy(player, StatsMessages.CLOSE, MessageArgs.empty()), List.of()));
        player.openInventory(holder.inventory);
        if (BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            openInventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
        }
    }

    private void showRanking(CommandSender sender, RankingSnapshot ranking, int page) {
        if (!sender.hasPermission("static.top")) {
            return;
        }
        if (!ranking.rules().equals(plugin.getConfigService().runtime().rankingRules())
                || !current(ranking.window(), ranking.historyZone())) {
            top(sender, ranking.statistic().id(), page, ranking.period());
            return;
        }
        renderRanking(sender, ranking, page, true);
    }

    private void renderRanking(CommandSender sender, RankingSnapshot ranking, int page, boolean calendar) {
        if (!sender.hasPermission("static.privacy.bypass")) {
            ranking = plugin.getProfileVisibility().filter(ranking);
        }
        int count = ranking.entries().size();
        int limit = plugin.getConfigService().runtime().leaderboardLimit();
        int pages = Math.max(1, (count - 1) / limit + 1);
        if (page > pages) {
            failure(sender, StatsMessages.INVALID_PAGE, MessageArgs.empty());
            return;
        }
        int offset = (page - 1) * limit;
        int end = Math.min(offset + limit, count);
        plugin.getLanguageService().send(sender, StatsMessages.TOP_HEADER, MessageArgs.builder()
                .untrusted("stat", plain(sender, StatsMessages.label(ranking.statistic())))
                .untrusted("page", number(sender, page)).untrusted("pages", number(sender, pages)).build());
        if (calendar) {
            sendPeriod(sender, ranking.window(), ranking.historyZone(), ranking.recordedSince());
        }
        Optional<RankingRequirement> requirement = ranking.rules().requirement(ranking.statistic());
        if (requirement.isPresent()) {
            RankingRequirement rule = requirement.get();
            plugin.getLanguageService().send(sender, StatsMessages.RATIO_REQUIREMENT,
                    qualificationValue(sender, rule.denominatorKey(), rule.minimum()));
            plugin.getLanguageService().send(sender, StatsMessages.RATIO_POSITIVE);
            plugin.getLanguageService().send(sender, StatsMessages.TOP_EXCLUDED, MessageArgs.builder()
                    .untrusted("qualified", number(sender, count)).untrusted("total", number(sender, ranking.totalProfiles()))
                    .untrusted("excluded", number(sender, ranking.excludedCount())).build());
        }
        if (count == 0) {
            plugin.getLanguageService().send(sender, requirement.isPresent() ? StatsMessages.TOP_NO_QUALIFIED : StatsMessages.EMPTY);
        }
        for (RankedPlayer entry : ranking.entries().subList(offset, end)) {
            sendRankingEntry(sender, ranking.statistic(), entry);
        }
        plugin.getLanguageService().send(sender, StatsMessages.TOP_UPDATED,
                args("date", StatsFormatter.date(ranking.refreshedAt(), plugin.getLanguageService(), sender)));
        if (sender instanceof Player player) {
            showPersonalRanking(player, ranking, offset, end);
        }
    }

    private void showPersonalRanking(Player player, RankingSnapshot ranking, int pageStart, int pageEnd) {
        Optional<RankingQualification> qualification = ranking.qualification(player.getUniqueId());
        if (qualification.isPresent() && !qualification.get().qualified()) {
            RankingQualification result = qualification.get();
            plugin.getLanguageService().send(player, StatsMessages.TOP_PERSONAL_UNQUALIFIED);
            plugin.getLanguageService().send(player, StatsMessages.RATIO_NUMERATOR,
                    qualificationValue(player, result.numeratorKey(), result.numerator()));
            plugin.getLanguageService().send(player, StatsMessages.TOP_PERSONAL_PROGRESS, MessageArgs.builder()
                    .untrusted("stat", plain(player, StatsMessages.label(result.denominatorKey())))
                    .untrusted("value", qualificationNumber(player, result.denominatorKey(), result.denominator()))
                    .untrusted("minimum", qualificationNumber(player, result.denominatorKey(), result.minimum())).build());
            return;
        }
        Optional<RankedPlayer> match = ranking.find(player.getUniqueId());
        if (match.isEmpty()) {
            plugin.getLanguageService().send(player, StatsMessages.TOP_UNRANKED);
            return;
        }
        RankedPlayer personal = match.get();
        plugin.getLanguageService().send(player, StatsMessages.TOP_PERSONAL, MessageArgs.builder()
                .untrusted("rank", number(player, personal.rank())).untrusted("players", number(player, ranking.entries().size()))
                .untrusted("value", format(player, ranking.statistic(), personal.value())).build());
        if (personal.rank() == 1) {
            plugin.getLanguageService().send(player, StatsMessages.TOP_LEADING);
        } else {
            plugin.getLanguageService().send(player, StatsMessages.TOP_GAP,
                    args("value", format(player, ranking.statistic(), personal.gapToNext())));
        }
        int position = personal.position() - 1;
        if (position >= pageStart && position < pageEnd) {
            return;
        }
        plugin.getLanguageService().send(player, StatsMessages.TOP_NEARBY);
        for (int index = Math.max(0, position - 2); index <= Math.min(ranking.entries().size() - 1, position + 2); index++) {
            sendRankingEntry(player, ranking.statistic(), ranking.entries().get(index));
        }
    }

    private void sendRankingEntry(CommandSender sender, StatKey statistic, RankedPlayer entry) {
        plugin.getLanguageService().send(sender, StatsMessages.TOP_ENTRY, MessageArgs.builder()
                .untrusted("rank", number(sender, entry.rank())).untrusted("player", entry.profile().name())
                .untrusted("value", format(sender, statistic, entry.value())).build());
    }

    private void addQualificationLore(Player player, RankingQualification qualification, List<String> lore) {
        lore.add(legacy(player, StatsMessages.RATIO_NUMERATOR,
                qualificationValue(player, qualification.numeratorKey(), qualification.numerator())));
        lore.add(legacy(player, StatsMessages.RATIO_DENOMINATOR,
                qualificationValue(player, qualification.denominatorKey(), qualification.denominator())));
        lore.add(legacy(player, StatsMessages.RATIO_REQUIREMENT,
                qualificationValue(player, qualification.denominatorKey(), qualification.minimum())));
        lore.add(legacy(player, StatsMessages.RATIO_POSITIVE, MessageArgs.empty()));
        lore.add(legacy(player, qualification.qualified() ? StatsMessages.RATIO_QUALIFIED : StatsMessages.RATIO_UNQUALIFIED, MessageArgs.empty()));
    }

    private MessageArgs qualificationValue(CommandSender sender, StatKey statistic, double value) {
        return MessageArgs.builder().untrusted("stat", plain(sender, StatsMessages.label(statistic)))
                .untrusted("value", qualificationNumber(sender, statistic, value)).build();
    }

    private String qualificationNumber(CommandSender sender, StatKey statistic, double value) {
        if (statistic == StatKey.ONLINE_TIME) {
            return ComponentText.markup(plugin.getLanguageService().render(sender, FormatMessages.HOURS,
                    args("value", StatsFormatter.decimal(value / 3_600_000D, plugin.getLanguageService(), sender)))).plain();
        }
        return StatsFormatter.decimal(value, plugin.getLanguageService(), sender);
    }

    private boolean viewing(Player player, ProfileHolder holder) {
        return !closed && player.isOnline() && BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory;
    }

    private boolean canView(Player player, UUID profile) {
        return player.hasPermission("static.stats") && (profile.equals(player.getUniqueId()) || player.hasPermission("static.stats.others"))
                && plugin.getProfileVisibility().canView(player, profile);
    }

    private boolean current(PeriodStatsSnapshot data) {
        return current(data.window(), data.historyZone());
    }

    private boolean current(PeriodWindow window, ZoneId zone) {
        return window.period() == StatsPeriod.LIFETIME || (zone.equals(plugin.getStatsService().historyZone())
                && window.equals(window.period().window(System.currentTimeMillis(), zone)));
    }

    private void sendPeriod(CommandSender sender, PeriodWindow window, ZoneId zone, long recordedSince) {
        for (PeriodLine line : periodLines(sender, window, zone, recordedSince)) {
            plugin.getLanguageService().send(sender, line.key(), line.arguments());
        }
    }

    private List<PeriodLine> periodLines(CommandSender sender, PeriodWindow window, ZoneId zone, long recordedSince) {
        List<PeriodLine> lines = new ArrayList<>();
        lines.add(new PeriodLine(StatsMessages.PERIOD_TOTALS, args("period", plain(sender, periodLabel(window.period())))));
        if (window.period() == StatsPeriod.LIFETIME) {
            lines.add(new PeriodLine(recordedSince > 0 ? StatsMessages.COVERAGE_SINCE : StatsMessages.COVERAGE_UNKNOWN,
                    recordedSince > 0 ? args("date", StatsFormatter.date(recordedSince, plugin.getLanguageService(), sender)) : MessageArgs.empty()));
            lines.add(new PeriodLine(StatsMessages.COVERAGE_SCOPE, MessageArgs.empty()));
            lines.add(new PeriodLine(StatsMessages.COVERAGE_HISTORY, MessageArgs.empty()));
            return List.copyOf(lines);
        }
        lines.add(new PeriodLine(StatsMessages.PERIOD_WINDOW, MessageArgs.builder()
                .untrusted("start", StatsFormatter.date(window.startedAt(), plugin.getLanguageService(), sender))
                .untrusted("end", StatsFormatter.date(window.endsAt(), plugin.getLanguageService(), sender)).build()));
        lines.add(new PeriodLine(StatsMessages.PERIOD_ZONE, args("zone", zone.getId())));
        TextKey coverage = recordedSince == 0 ? StatsMessages.PERIOD_UNKNOWN
                : recordedSince > window.startedAt() ? StatsMessages.PERIOD_PARTIAL : StatsMessages.PERIOD_COMPLETE;
        lines.add(new PeriodLine(coverage, MessageArgs.empty()));
        return List.copyOf(lines);
    }

    private static TextKey periodLabel(StatsPeriod period) {
        return switch (period) {
            case LIFETIME -> StatsMessages.PERIOD_LIFETIME;
            case DAY -> StatsMessages.PERIOD_DAY;
            case WEEK -> StatsMessages.PERIOD_WEEK;
            case MONTH -> StatsMessages.PERIOD_MONTH;
        };
    }

    private void showSessions(CommandSender sender, PlayerStatsSnapshot profile, List<PlayerSession> records, int page) {
        if (records.isEmpty()) {
            plugin.getLanguageService().send(sender, StatsMessages.SESSIONS_EMPTY, args("player", profile.name()));
            return;
        }
        int pages = (records.size() - 1) / SESSION_PAGE_SIZE + 1;
        if (page > pages) {
            failure(sender, StatsMessages.SESSIONS_INVALID_PAGE, MessageArgs.empty());
            return;
        }
        plugin.getLanguageService().send(sender, StatsMessages.SESSIONS_HEADER, MessageArgs.builder()
                .untrusted("player", profile.name()).untrusted("page", number(sender, page)).untrusted("pages", number(sender, pages)).build());
        int offset = (page - 1) * SESSION_PAGE_SIZE;
        for (int index = offset; index < Math.min(offset + SESSION_PAGE_SIZE, records.size()); index++) {
            PlayerSession session = records.get(index);
            String end = session.endReason() == SessionEndReason.OPEN ? plain(sender, StatsMessages.SESSIONS_ONGOING)
                    : StatsFormatter.date(session.endedAt(), plugin.getLanguageService(), sender);
            plugin.getLanguageService().send(sender, StatsMessages.SESSIONS_ROW, MessageArgs.builder()
                    .untrusted("number", number(sender, index + 1))
                    .untrusted("start", StatsFormatter.date(session.startedAt(), plugin.getLanguageService(), sender)).untrusted("end", end).build());
            plugin.getLanguageService().send(sender, StatsMessages.SESSIONS_TIME, MessageArgs.builder()
                    .untrusted("online", format(sender, StatKey.ONLINE_TIME, session.eligibleMillis()))
                    .untrusted("active", format(sender, StatKey.ACTIVE_TIME, session.activeMillis()))
                    .untrusted("afk", format(sender, StatKey.AFK_TIME, session.afkMillis())).build());
            if (session.endReason() == SessionEndReason.INTERRUPTED) {
                plugin.getLanguageService().send(sender, StatsMessages.SESSIONS_INTERRUPTED);
            }
        }
        plugin.getLanguageService().send(sender, StatsMessages.SESSIONS_SCOPE);
        plugin.getLanguageService().send(sender, StatsMessages.ACTIVITY_HINT);
    }

    private void deliver(Player player, Runnable action) {
        dispatcher(player).accept(action);
    }

    private Consumer<Runnable> dispatcher(CommandSender sender) {
        if (sender instanceof Entity entity) {
            return action -> {
                if (!closed) {
                    FoliaScheduler.runEntity(plugin, entity, () -> {
                        if (!closed && (!(entity instanceof Player player) || player.isOnline())) {
                            action.run();
                        }
                    });
                }
            };
        }
        if (sender instanceof BlockCommandSender block) {
            Location location = block.getBlock().getLocation();
            return action -> {
                if (!closed) {
                    FoliaScheduler.runRegion(plugin, location, () -> {
                        if (!closed) {
                            action.run();
                        }
                    });
                }
            };
        }
        return action -> {
            if (!closed) {
                action.run();
            }
        };
    }

    private void failure(CommandSender sender, TextKey key, MessageArgs arguments) {
        plugin.getPresentationService().command(sender, key, arguments, FeedbackTone.FAILURE);
    }

    private String format(CommandSender sender, StatKey statistic, double value) {
        return StatsFormatter.format(statistic, value, plugin.getLanguageService(), sender);
    }

    private String number(CommandSender sender, double value) {
        return StatsFormatter.number(value, plugin.getLanguageService(), sender);
    }

    private String legacy(CommandSender sender, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key, arguments)).legacy();
    }

    private String plain(CommandSender sender, TextKey key) {
        return ComponentText.markup(plugin.getLanguageService().render(sender, key)).plain();
    }

    private static MessageArgs args(String key, String value) {
        return MessageArgs.builder().untrusted(key, value).build();
    }

    private static Material icon(StatKey stat) {
        String id = stat.id();
        if (id.contains("arrow") || id.contains("shot")) { return Material.BOW; }
        if (id.contains("damage") || id.contains("hits") || id.contains("kills") || id.contains("death")) { return Material.IRON_SWORD; }
        if (id.startsWith("blocks")) { return Material.IRON_PICKAXE; }
        if (id.contains("distance")) { return Material.LEATHER_BOOTS; }
        if (id.contains("time")) { return Material.CLOCK; }
        return Material.BOOK;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    enum Category {
        ALL(StatsMessages.CATEGORY_ALL, ""),
        BUILDING(StatsMessages.CATEGORY_BUILDING, "building"),
        CONNECTIONS(StatsMessages.CATEGORY_CONNECTIONS, "connections"),
        COMBAT(StatsMessages.CATEGORY_COMBAT, "combat"),
        ARCHERY(StatsMessages.CATEGORY_ARCHERY, "archery"),
        SOCIAL(StatsMessages.CATEGORY_SOCIAL, "social"),
        MOVEMENT(StatsMessages.CATEGORY_MOVEMENT, "movement");

        private final TextKey label;
        private final List<StatKey> statistics;

        Category(TextKey label, String category) {
            this.label = label;
            List<StatKey> selected = new ArrayList<>();
            for (StatKey statistic : StatKey.values()) {
                if (category.isEmpty() || statistic.category().equals(category)) {
                    selected.add(statistic);
                }
            }
            statistics = List.copyOf(selected);
        }

        Category next() {
            return values()[(ordinal() + 1) % values().length];
        }

        List<StatKey> statistics() {
            return statistics;
        }
    }

    private static final class ProfileHolder implements InventoryHolder {
        private final ProfileState state;
        private final List<StatKey> statistics;
        private Inventory inventory;

        private ProfileHolder(ProfileState state, List<StatKey> statistics) {
            this.state = state;
            this.statistics = statistics;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class PendingProfile {
        private final Inventory inventory;

        private PendingProfile(Inventory inventory) {
            this.inventory = inventory;
        }

        private Inventory inventory() {
            return inventory;
        }
    }

    private record PeriodLine(TextKey key, MessageArgs arguments) {
    }

    private record ProfileState(UUID viewer, UUID profile, Category category, boolean favorites, int page, StatsPeriod period) {
        private ProfileState period(StatsPeriod selected) {
            return new ProfileState(viewer, profile, category, favorites, 0, selected);
        }

        private ProfileState page(int selected) {
            return new ProfileState(viewer, profile, category, favorites, selected, period);
        }

        private ProfileState category(Category selected) {
            return new ProfileState(viewer, profile, selected, false, 0, period);
        }

        private ProfileState favorites(boolean selected) {
            return new ProfileState(viewer, profile, Category.ALL, selected, 0, period);
        }
    }
}
