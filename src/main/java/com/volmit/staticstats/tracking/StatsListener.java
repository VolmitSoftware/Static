package com.volmit.staticstats.tracking;

import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class StatsListener implements Listener, AutoCloseable {
    private static final long SHOT_LIFETIME_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final StatsService stats;
    private final ConcurrentHashMap<UUID, Shot> shots = new ConcurrentHashMap<>();

    public StatsListener(StatsService stats) {
        this.stats = stats;
    }

    @Override
    public void close() {
        shots.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        stats.connect(player.getUniqueId(), player.getName(), context(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stats.quit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChanged(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        stats.updateContext(player.getUniqueId(), context(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChanged(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        stats.updateContext(player.getUniqueId(), new StatsService.TrackingContext(
                player.getWorld().getName(), event.getNewGameMode()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBroken(BlockBreakEvent event) {
        stats.activity(event.getPlayer().getUniqueId());
        stats.add(event.getPlayer().getUniqueId(), StatKey.BLOCKS_BROKEN, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) {
        if (!event.canBuild()) {
            return;
        }
        int count = event instanceof BlockMultiPlaceEvent multiple ? multiple.getReplacedBlockStates().size() : 1;
        stats.activity(event.getPlayer().getUniqueId());
        stats.add(event.getPlayer().getUniqueId(), StatKey.BLOCKS_PLACED, count);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimId = victim.getUniqueId();
        stats.add(victimId, StatKey.DEATHS, 1);
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victimId)) {
            stats.add(victimId, StatKey.PVP_DEATHS, 1);
            stats.add(killer.getUniqueId(), StatKey.PVP_KILLS, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player || event.getEntity() instanceof ArmorStand) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            stats.add(killer.getUniqueId(), StatKey.MOB_KILLS, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow) || arrow instanceof Trident) {
            return;
        }
        ProjectileSource source = arrow.getShooter();
        if (!(source instanceof Player player) || !stats.eligible(player.getUniqueId())) {
            return;
        }
        UUID shooter = player.getUniqueId();
        stats.activity(shooter);
        if (shots.putIfAbsent(arrow.getUniqueId(), new Shot(shooter, System.nanoTime())) != null) {
            return;
        }
        stats.add(shooter, StatKey.ARROWS_FIRED, 1);
        stats.add(shooter, arrow.isCritical() ? StatKey.CRITICAL_ARROWS : StatKey.NONCRITICAL_ARROWS, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getFinalDamage() <= 0 || !Double.isFinite(event.getFinalDamage())) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Player attacker && !attacker.getUniqueId().equals(event.getEntity().getUniqueId())) {
            stats.activity(attacker.getUniqueId());
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        UUID victimId = victim.getUniqueId();
        if (damager instanceof Player attacker) {
            UUID attackerId = attacker.getUniqueId();
            if (attackerId.equals(victimId)) {
                return;
            }
            stats.add(attackerId, StatKey.MELEE_HITS_DEALT, 1);
            stats.add(attackerId, StatKey.MELEE_DAMAGE_DEALT, event.getFinalDamage());
            stats.add(victimId, StatKey.MELEE_HITS_TAKEN, 1);
            stats.add(victimId, StatKey.MELEE_DAMAGE_TAKEN, event.getFinalDamage());
        } else if (damager instanceof AbstractArrow arrow) {
            recordArrowDamage(arrow, victimId, event.getFinalDamage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof AbstractArrow arrow
                && (event.getHitBlock() != null || event.getHitEntity() != null
                && !(event.getHitEntity() instanceof Player) && arrow.getPierceLevel() == 0)) {
            shots.remove(arrow.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        stats.activity(event.getPlayer().getUniqueId());
        stats.add(event.getPlayer().getUniqueId(), StatKey.CHAT_MESSAGES, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        stats.activity(event.getPlayer().getUniqueId());
        stats.add(event.getPlayer().getUniqueId(), StatKey.COMMANDS_EXECUTED, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        stats.activity(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!stats.eligible(uuid)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        double squared = from.distanceSquared(to);
        if (Double.isFinite(squared) && (squared >= 0.0001D
                || Math.abs(to.getYaw() - from.getYaw()) >= 1F
                || Math.abs(to.getPitch() - from.getPitch()) >= 1F)) {
            stats.activity(uuid);
        }
        if (squared <= 0 || !Double.isFinite(squared)) {
            return;
        }
        double distance = Math.sqrt(squared);
        stats.add(uuid, StatKey.DISTANCE_TRAVELED, distance);
        if (player.isInsideVehicle()) {
            return;
        }
        if (player.isFlying() || player.isGliding()) {
            stats.add(uuid, StatKey.DISTANCE_FLOWN, distance);
        } else if (player.isSwimming() || player.isInWater()) {
            stats.add(uuid, StatKey.DISTANCE_SWUM, distance);
        } else if (!player.isOnGround() && to.getY() < from.getY()) {
            stats.add(uuid, StatKey.DISTANCE_FALLEN, from.getY() - to.getY());
        } else if (player.isSprinting()) {
            stats.add(uuid, StatKey.DISTANCE_SPRINTED, distance);
        } else if (player.isSneaking()) {
            stats.add(uuid, StatKey.DISTANCE_SNEAKED, distance);
        } else {
            stats.add(uuid, StatKey.DISTANCE_WALKED, distance);
        }
    }

    public void removeExpiredShots() {
        long now = System.nanoTime();
        shots.entrySet().removeIf(entry -> now - entry.getValue().launchedAt > SHOT_LIFETIME_NANOS);
    }

    private void recordArrowDamage(AbstractArrow arrow, UUID victim, double damage) {
        Shot shot = shots.get(arrow.getUniqueId());
        if (shot == null || shot.shooter.equals(victim)) {
            return;
        }
        if (stats.eligible(victim)) {
            Set<String> credited = shot.hitPlayers.computeIfAbsent(victim, ignored -> ConcurrentHashMap.newKeySet());
            stats.recordArrowHit(victim, StatKey.ARROW_HITS_TAKEN, credited);
        }
        if (stats.eligible(shot.shooter)) {
            stats.recordArrowHit(shot.shooter, StatKey.ARROW_HITS_DEALT, shot.hitScopes);
        }
        stats.add(victim, StatKey.ARROW_DAMAGE_TAKEN, damage);
        stats.add(shot.shooter, StatKey.ARROW_DAMAGE_DEALT, damage);
        if (arrow.getPierceLevel() == 0) {
            shots.remove(arrow.getUniqueId(), shot);
        }
    }

    private static StatsService.TrackingContext context(Player player) {
        return new StatsService.TrackingContext(player.getWorld().getName(), player.getGameMode());
    }

    private static final class Shot {
        private final UUID shooter;
        private final long launchedAt;
        private final Set<String> hitScopes = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<UUID, Set<String>> hitPlayers = new ConcurrentHashMap<>();

        private Shot(UUID shooter, long launchedAt) {
            this.shooter = shooter;
            this.launchedAt = launchedAt;
        }
    }
}
