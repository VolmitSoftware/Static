package com.volmit.staticfixture;

import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailSnapshot;

import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.PeriodStatsSnapshot;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.RankingQualification;
import com.volmit.staticstats.stats.RankedPlayer;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class StaticFixture extends JavaPlugin implements Listener {
    private final Set<UUID> cancelledPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger cancelledBreaks = new AtomicInteger();
    private final AtomicInteger cancelledPlaces = new AtomicInteger();
    private final AtomicInteger cancelledChats = new AtomicInteger();
    private final ConcurrentHashMap<UUID, Arrow> projectiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<StatKey, CompletableFuture<RankingSnapshot>> rankings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PermissionAttachment> permissions = new ConcurrentHashMap<>();
    private CompletableFuture<Void> pendingFlush = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> pendingProfileDelay = CompletableFuture.completedFuture(null);
    private StaticStatistics statistics;
    private ActivityProbe activityProbe;
    private MilestoneProbe milestoneProbe;
    private SeasonProbe seasonProbe;
    private BestsProbe bestsProbe;
    private DetailRankingProbe detailRankingProbe;
    private ParticipationProbe participationProbe;

    @Override
    public void onEnable() {
        statistics = getServer().getServicesManager().load(StaticStatistics.class);
        if (statistics == null) {
            throw new IllegalStateException("Static statistics service is unavailable");
        }
        getServer().getPluginManager().registerEvents(this, this);
        activityProbe = new ActivityProbe(statistics, getDataFolder().toPath().resolve("activity-samples.jsonl"), getLogger());
        milestoneProbe = new MilestoneProbe(statistics, getDataFolder().toPath().resolve("milestone-events.jsonl"),
                getDataFolder().toPath().resolveSibling("Static").resolve("storage").resolve("players.json"), getLogger());
        seasonProbe = new SeasonProbe(statistics, getDataFolder().toPath(), getLogger());
        bestsProbe = new BestsProbe(statistics, getDataFolder().toPath().resolve("bests-results"), getLogger());
        detailRankingProbe = new DetailRankingProbe(statistics, getDataFolder().toPath().resolve("detail-ranking-results"), getLogger());
        participationProbe = new ParticipationProbe(statistics, getDataFolder().toPath().resolve("participation-results"), getLogger());
    }

    @Override
    public void onDisable() {
        if (participationProbe != null) { participationProbe.close(); }
        if (detailRankingProbe != null) { detailRankingProbe.close(); }
        if (bestsProbe != null) { bestsProbe.close(); }
        if (seasonProbe != null) { seasonProbe.close(); }
        if (milestoneProbe != null) {
            milestoneProbe.close();
        }
        if (activityProbe != null) {
            activityProbe.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player player) || !sender.hasPermission("staticfixture.admin")) {
            sender.sendMessage("Fixture permission denied");
            return true;
        }
        if (arguments.length == 0) {
            sender.sendMessage("Fixture ready");
            return true;
        }
        switch (arguments[0]) {
            case "participation" -> participation(player, arguments);
            case "participation-ready" -> {
                UUID request = UUID.fromString(arguments[1]);
                player.sendMessage("Fixture participation " + participationProbe.status(request) + " " + request);
            }
            case "detail" -> {
                DetailKey key = DetailKey.find(arguments[1]).orElseThrow();
                Optional<DetailSnapshot> detail = statistics.details(player.getUniqueId());
                player.sendMessage("Fixture detail " + key.id() + " " + detail.map(snapshot -> snapshot.value(key)).orElse(0L)
                        + " " + detail.map(snapshot -> snapshot.recordedSince(key)).orElse(0L));
            }
            case "detail-add" -> {
                UUID target = arguments.length > 3 ? UUID.fromString(arguments[3]) : player.getUniqueId();
                statistics.addDetail(target, DetailKey.find(arguments[1]).orElseThrow(), Long.parseLong(arguments[2]));
                player.sendMessage("Fixture detail added");
            }
            case "detail-board" -> player.sendMessage("Fixture detail board requested "
                    + detailRankingProbe.capture(DetailKey.find(arguments[1]).orElseThrow(),
                    arguments.length > 2 && arguments[2].equals("cached")));
            case "detail-board-ready" -> {
                UUID request = UUID.fromString(arguments[1]);
                player.sendMessage("Fixture detail board " + detailRankingProbe.status(request) + " " + request);
            }
            case "cancel" -> {
                boolean enabled = arguments.length > 1 && arguments[1].equals("on");
                if (enabled) {
                    cancelledPlayers.add(player.getUniqueId());
                } else {
                    cancelledPlayers.remove(player.getUniqueId());
                }
                sender.sendMessage("Fixture cancellation " + (enabled ? "on" : "off"));
            }
            case "counts" -> sender.sendMessage("Fixture cancellations " + cancelledBreaks.get()
                    + " " + cancelledPlaces.get() + " " + cancelledChats.get());
            case "value" -> {
                StatKey key = StatKey.parse(arguments[1]);
                Optional<PlayerStatsSnapshot> snapshot = arguments.length > 2
                        ? statistics.find(arguments[2]) : statistics.snapshot(player.getUniqueId());
                if (snapshot.isEmpty()) {
                    throw new IllegalStateException("No profile for " + player.getName());
                }
                sender.sendMessage("Fixture value " + key.id() + " " + snapshot.orElseThrow().value(key));
            }
            case "flush" -> {
                pendingFlush = statistics.flushAsync();
                pendingFlush.whenComplete((ignored, error) -> {
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Fixture statistics flush failed", error);
                    }
                });
                sender.sendMessage("Fixture flush requested");
            }
            case "saved" -> sender.sendMessage("Fixture flush " + (pendingFlush.isCompletedExceptionally()
                    ? "failed" : pendingFlush.isDone() ? "complete" : "pending"));
            case "projectile" -> projectile(player, arguments);
            case "add" -> add(player, arguments);
            case "profile" -> profile(player, arguments);
            case "ranking" -> ranking(player, arguments);
            case "qualification" -> qualification(player, arguments);
            case "period" -> period(player, arguments);
            case "bests" -> bests(player, arguments);
            case "bests-ready" -> {
                UUID request = UUID.fromString(arguments[1]);
                player.sendMessage("Fixture bests " + bestsProbe.status(request) + " " + request);
            }
            case "milestones" -> {
                UUID target = arguments.length > 1
                        ? statistics.find(arguments[1]).orElseThrow().uuid() : player.getUniqueId();
                player.sendMessage("Fixture milestones " + milestoneProbe.snapshot(target));
            }
            case "season" -> player.sendMessage("Fixture season " + seasonProbe.profile(arguments[1], player.getUniqueId()));
            case "season-request" -> {
                seasonProbe.request(arguments[1]);
                player.sendMessage("Fixture season requested");
            }
            case "season-result" -> player.sendMessage("Fixture season result " + seasonProbe.result(arguments[1]));
            case "add-uuid" -> {
                statistics.add(UUID.fromString(arguments[1]), StatKey.parse(arguments[2]), Double.parseDouble(arguments[3]));
                player.sendMessage("Fixture UUID added");
            }
            case "observe" -> {
                activityProbe.start(player.getUniqueId());
                player.sendMessage("Fixture activity observation started");
            }
            case "permission" -> permission(player, arguments);
            case "delay-profile" -> delayProfile(player);
            case "delay-status" -> player.sendMessage("Fixture profile queue "
                    + (pendingProfileDelay.isDone() ? "ready" : "delayed"));
            default -> sender.sendMessage("Fixture unknown command");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (cancelledPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            cancelledBreaks.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (cancelledPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            cancelledPlaces.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (cancelledPlayers.contains(event.getPlayer().getUniqueId()) && event.getMessage().equals("STATIC_CANCEL_CHAT")) {
            event.setCancelled(true);
            cancelledChats.incrementAndGet();
        }
    }

    private void projectile(Player player, String[] arguments) {
        switch (arguments[1]) {
            case "start" -> {
                clearProjectile(player);
                Arrow arrow = player.getWorld().spawnArrow(player.getEyeLocation().add(0, 4, 0),
                        new Vector(0, 1, 0), 0, 0);
                arrow.setShooter(player);
                arrow.setGravity(false);
                arrow.setVelocity(new Vector(0, 0, 0));
                arrow.setPierceLevel(2);
                arrow.setPersistent(false);
                projectiles.put(player.getUniqueId(), arrow);
                getServer().getPluginManager().callEvent(new ProjectileLaunchEvent(arrow));
                player.sendMessage("Fixture synthetic projectile launched");
            }
            case "hit" -> {
                Arrow arrow = projectiles.get(player.getUniqueId());
                Player target = getServer().getPlayerExact(arguments[2]);
                if (arrow == null || target == null || target.getUniqueId().equals(player.getUniqueId())) {
                    throw new IllegalStateException("A distinct online target and active fixture projectile are required");
                }
                if (!target.getWorld().equals(player.getWorld())
                        || target.getLocation().distanceSquared(player.getLocation()) > 4) {
                    throw new IllegalStateException("The synthetic target must remain beside the shooter");
                }
                getServer().getPluginManager().callEvent(new EntityDamageByEntityEvent(arrow, target,
                        EntityDamageEvent.DamageCause.PROJECTILE, 2));
                player.sendMessage("Fixture synthetic projectile hit dispatched");
            }
            case "clear" -> {
                clearProjectile(player);
                player.sendMessage("Fixture synthetic projectile cleared");
            }
            default -> throw new IllegalArgumentException("Unknown fixture projectile operation");
        }
    }

    private void clearProjectile(Player player) {
        Arrow arrow = projectiles.remove(player.getUniqueId());
        if (arrow != null) {
            arrow.remove();
        }
    }

    private void add(Player player, String[] arguments) {
        StatKey key = StatKey.parse(arguments[1]);
        double amount = Double.parseDouble(arguments[2]);
        UUID target = arguments.length > 3
                ? statistics.find(arguments[3]).orElseThrow().uuid() : player.getUniqueId();
        statistics.add(target, key, amount);
        player.sendMessage("Fixture added " + key.id() + " " + amount);
    }

    private void period(Player player, String[] arguments) {
        StatsPeriod period = StatsPeriod.parse(arguments[1]);
        StatKey key = StatKey.parse(arguments[2]);
        PeriodStatsSnapshot snapshot = statistics.periodSnapshot(player.getUniqueId(), period).orElseThrow();
        player.sendMessage("Fixture period " + period.id() + " " + key.id() + " " + snapshot.profile().value(key)
                + " " + snapshot.window().id() + " " + snapshot.window().startedAt() + " " + snapshot.window().endsAt()
                + " " + snapshot.recordedSince() + " " + statistics.historyZone());
    }

    private void bests(Player player, String[] arguments) {
        UUID target = player.getUniqueId();
        if (arguments.length > 1) {
            try {
                target = UUID.fromString(arguments[1]);
            } catch (IllegalArgumentException exception) {
                target = statistics.find(arguments[1]).orElseThrow().uuid();
            }
        }
        player.sendMessage("Fixture bests requested " + bestsProbe.capture(target));
    }

    private void participation(Player player, String[] arguments) {
        List<UUID> players = new ArrayList<>();
        players.add(player.getUniqueId());
        for (int index = 4; index < arguments.length; index++) {
            players.add(UUID.fromString(arguments[index]));
        }
        ParticipationProbe.Request request = new ParticipationProbe.Request(StatsPeriod.parse(arguments[1]),
                Integer.parseInt(arguments[2]), arguments[3].equals("cached"), players);
        player.sendMessage("Fixture participation requested " + participationProbe.capture(request));
    }

    private void profile(Player player, String[] arguments) {
        PlayerStatsSnapshot snapshot = arguments.length > 1
                ? statistics.find(arguments[1]).orElseThrow()
                : statistics.snapshot(player.getUniqueId()).orElseThrow();
        player.sendMessage("Fixture profile " + snapshot.uuid() + " " + snapshot.recordedSince());
    }

    private void ranking(Player player, String[] arguments) {
        StatKey key = StatKey.parse(arguments[1]);
        switch (arguments[2]) {
            case "request" -> {
                CompletableFuture<RankingSnapshot> future = statistics.ranking(key);
                future.whenComplete((snapshot, error) -> {
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Fixture ranking request failed", error);
                    }
                });
                rankings.put(key, future);
                player.sendMessage("Fixture ranking requested");
            }
            case "ready" -> {
                CompletableFuture<RankingSnapshot> future = rankings.get(key);
                if (future == null || !future.isDone()) {
                    player.sendMessage("Fixture ranking pending");
                    return;
                }
                showRanking(player, future.join());
            }
            case "cached" -> {
                Optional<RankingSnapshot> snapshot = statistics.cachedRanking(key);
                if (snapshot.isEmpty()) {
                    player.sendMessage("Fixture ranking pending");
                    return;
                }
                showRanking(player, snapshot.orElseThrow());
            }
            default -> throw new IllegalArgumentException("Unknown fixture ranking operation");
        }
    }

    private void showRanking(Player player, RankingSnapshot snapshot) {
        StringJoiner entries = new StringJoiner(";");
        for (RankedPlayer entry : snapshot.entries()) {
            entries.add(entry.profile().uuid() + "," + entry.position() + "," + entry.rank()
                    + "," + entry.value() + "," + entry.gapToNext());
        }
        player.sendMessage("Fixture ranking " + snapshot.statistic().id() + " "
                + snapshot.refreshedAt() + " " + entries);
    }

    private void permission(Player player, String[] arguments) {
        String node = arguments[1];
        if (!node.startsWith("static.")) {
            throw new IllegalArgumentException("Only Static permissions may be overridden");
        }
        PermissionAttachment attachment = permissions.computeIfAbsent(player.getUniqueId(),
                ignored -> player.addAttachment(this));
        if (arguments[2].equals("clear")) {
            attachment.unsetPermission(node);
        } else {
            attachment.setPermission(node, Boolean.parseBoolean(arguments[2]));
        }
        player.sendMessage("Fixture permission " + node + " " + player.hasPermission(node));
    }

    private void qualification(Player player, String[] arguments) {
        StatKey key = StatKey.parse(arguments[1]);
        UUID target = arguments.length > 2
                ? statistics.find(arguments[2]).orElseThrow().uuid() : player.getUniqueId();
        Optional<RankingQualification> qualification = statistics.cachedRanking(key)
                .flatMap(snapshot -> snapshot.qualification(target));
        if (qualification.isEmpty()) {
            player.sendMessage("Fixture qualification unavailable");
            return;
        }
        RankingQualification value = qualification.orElseThrow();
        player.sendMessage("Fixture qualification " + key.id() + " " + value.qualified()
                + " " + value.numerator() + " " + value.denominator() + " " + value.minimum());
    }

    private void delayProfile(Player player) {
        pendingProfileDelay = new CompletableFuture<>();
        CompletableFuture<Void> gate = pendingProfileDelay;
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> gate.complete(null));
        StaticPlugin plugin = (StaticPlugin) getServer().getPluginManager().getPlugin("Static");
        plugin.executeAsync(gate::join);
        player.sendMessage("Fixture profile queue delayed");
    }
}
