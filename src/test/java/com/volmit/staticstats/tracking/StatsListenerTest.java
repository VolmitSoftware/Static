package com.volmit.staticstats.tracking;

import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsService;
import com.volmit.staticstats.stats.StatsStore;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Trident;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

class StatsListenerTest {
    private static final UUID FIRST = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID THIRD = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @TempDir
    Path directory;

    @Test
    void attackingMobsSignalsActivityWithoutAddingPvpDamage() {
        StatsService service = mock(StatsService.class);
        StatsListener listener = new StatsListener(service);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Zombie target = mock(Zombie.class);
        Player attacker = player(FIRST);
        when(target.getUniqueId()).thenReturn(SECOND);
        when(event.getEntity()).thenReturn(target);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getFinalDamage()).thenReturn(3D);
        listener.onDamage(event);
        verify(service).activity(FIRST);
        verify(service, never()).add(any(UUID.class), any(StatKey.class), anyDouble());
    }

    @Test
    void meaningfulMovementAndLookResetIdleButHeartbeatsAndTeleportsDoNot() {
        StatsService service = mock(StatsService.class);
        when(service.eligible(FIRST)).thenReturn(true);
        StatsListener listener = new StatsListener(service);
        World world = mock(World.class);
        Player player = player(FIRST);
        Location origin = new Location(world, 0, 64, 0);
        listener.onMove(move(player, origin, origin.clone()));
        listener.onMove(move(player, origin, new Location(world, 0.001, 64, 0, 0.1F, 0.1F)));
        listener.onMove(mock(PlayerTeleportEvent.class));
        verify(service, never()).activity(FIRST);
        listener.onMove(move(player, origin, new Location(world, 1, 64, 0)));
        listener.onMove(move(player, origin, new Location(world, 0, 64, 0, 90, 0)));
        verify(service, times(2)).activity(FIRST);
        when(service.eligible(FIRST)).thenReturn(false);
        listener.onMove(move(player, origin, new Location(world, 2, 64, 0)));
        verify(service, times(2)).activity(FIRST);
    }

    @Test
    void measuresDistanceAndExcludesRotationTeleportAndWorldChanges() {
        StatsStore store = store();
        StatsListener listener = listener(store);
        World world = mock(World.class);
        Player player = player(FIRST);
        listener.onMove(move(player, new Location(world, 0, 64, 0), new Location(world, 3, 64, 4)));
        listener.onMove(move(player, new Location(world, 3, 64, 4), new Location(world, 3, 64, 4, 90, 45)));
        listener.onMove(mock(PlayerTeleportEvent.class));
        listener.onMove(move(player, new Location(world, 3, 64, 4), new Location(mock(World.class), 300, 80, 400)));
        PlayerStatsSnapshot snapshot = store.snapshot(FIRST).orElseThrow();
        assertEquals(5, snapshot.value(StatKey.DISTANCE_TRAVELED));
        assertEquals(5, snapshot.value(StatKey.DISTANCE_WALKED));
    }

    @Test
    void fallingMeasuresOnlyDownwardDistanceWhileJumpAscentCanBeSprinting() {
        StatsStore store = store();
        StatsListener listener = listener(store);
        World world = mock(World.class);
        Player player = player(FIRST);
        when(player.isSprinting()).thenReturn(true);
        listener.onMove(move(player, new Location(world, 0, 68, 0), new Location(world, 3, 64, 0)));
        listener.onMove(move(player, new Location(world, 3, 64, 0), new Location(world, 6, 68, 0)));
        PlayerStatsSnapshot snapshot = store.snapshot(FIRST).orElseThrow();
        assertEquals(10, snapshot.value(StatKey.DISTANCE_TRAVELED));
        assertEquals(4, snapshot.value(StatKey.DISTANCE_FALLEN));
        assertEquals(5, snapshot.value(StatKey.DISTANCE_SPRINTED));
    }

    @Test
    void deniedPlacementDoesNotCountAndMultiblocksCountEveryPlacedBlock() {
        StatsStore store = store();
        StatsListener listener = listener(store);
        Player player = player(FIRST);
        BlockPlaceEvent denied = mock(BlockPlaceEvent.class);
        when(denied.getPlayer()).thenReturn(player);
        when(denied.canBuild()).thenReturn(false);
        listener.onBlockPlaced(denied);
        BlockMultiPlaceEvent allowed = mock(BlockMultiPlaceEvent.class);
        when(allowed.getPlayer()).thenReturn(player);
        when(allowed.canBuild()).thenReturn(true);
        when(allowed.getReplacedBlockStates()).thenReturn(List.of(mock(BlockState.class), mock(BlockState.class)));
        listener.onBlockPlaced(allowed);
        assertEquals(2, store.snapshot(FIRST).orElseThrow().value(StatKey.BLOCKS_PLACED));
    }

    @Test
    void combatCountsFinalDamageInsteadOfPreArmorDamage() {
        StatsStore store = store();
        StatsListener listener = listener(store);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Player attacker = player(FIRST);
        Player victim = player(SECOND);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamage()).thenReturn(10D);
        when(event.getFinalDamage()).thenReturn(2.5);
        listener.onDamage(event);
        assertEquals(2.5, store.snapshot(FIRST).orElseThrow().value(StatKey.MELEE_DAMAGE_DEALT));
        assertEquals(2.5, store.snapshot(SECOND).orElseThrow().value(StatKey.MELEE_DAMAGE_TAKEN));
        assertEquals(1, store.snapshot(FIRST).orElseThrow().value(StatKey.MELEE_HITS_DEALT));
    }

    @Test
    void piercingArrowsCountOneSuccessfulShotAndEachVictimOnce() {
        StatsStore store = store();
        StatsListener listener = listener(store);
        Player shooter = player(FIRST);
        AbstractArrow arrow = mock(AbstractArrow.class);
        when(arrow.getUniqueId()).thenReturn(UUID.randomUUID());
        when(arrow.getShooter()).thenReturn(shooter);
        when(arrow.getPierceLevel()).thenReturn(1);
        when(arrow.isCritical()).thenReturn(true);
        ProjectileLaunchEvent launch = mock(ProjectileLaunchEvent.class);
        when(launch.getEntity()).thenReturn(arrow);
        listener.onArrowLaunch(launch);
        listener.onArrowLaunch(launch);
        damage(listener, arrow, player(SECOND));
        damage(listener, arrow, player(SECOND));
        damage(listener, arrow, player(THIRD));
        PlayerStatsSnapshot snapshot = store.snapshot(FIRST).orElseThrow();
        assertEquals(1, snapshot.value(StatKey.ARROWS_FIRED));
        assertEquals(1, snapshot.value(StatKey.CRITICAL_ARROWS));
        assertEquals(1, snapshot.value(StatKey.ARROW_HITS_DEALT));
        assertEquals(9, snapshot.value(StatKey.ARROW_DAMAGE_DEALT));
        assertEquals(1, snapshot.value(StatKey.ARROW_ACCURACY));
        assertEquals(1, store.snapshot(SECOND).orElseThrow().value(StatKey.ARROW_HITS_TAKEN));
        assertEquals(1, store.snapshot(THIRD).orElseThrow().value(StatKey.ARROW_HITS_TAKEN));
    }

    @Test
    void piercingArrowFilterTransitionsPreserveIndependentHitCredits() {
        StatsStore store = store();
        AtomicBoolean shooterEligible = new AtomicBoolean(true);
        AtomicBoolean victimEligible = new AtomicBoolean(true);
        StatsService service = mock(StatsService.class);
        when(service.eligible(any(UUID.class))).thenAnswer(invocation -> {
            UUID uuid = invocation.getArgument(0);
            return uuid.equals(FIRST) ? shooterEligible.get() : !uuid.equals(SECOND) || victimEligible.get();
        });
        doAnswer(invocation -> {
            UUID uuid = invocation.getArgument(0);
            if (service.eligible(uuid)) {
                store.add(uuid, invocation.getArgument(1), invocation.getArgument(2));
            }
            return null;
        }).when(service).add(any(UUID.class), any(StatKey.class), anyDouble());
        stubArrowHits(service, store);
        StatsListener listener = new StatsListener(service);
        Player shooter = player(FIRST);
        Player victim = player(SECOND);
        AbstractArrow arrow = mock(AbstractArrow.class);
        when(arrow.getUniqueId()).thenReturn(UUID.randomUUID());
        when(arrow.getShooter()).thenReturn(shooter);
        when(arrow.getPierceLevel()).thenReturn(1);
        ProjectileLaunchEvent launch = mock(ProjectileLaunchEvent.class);
        when(launch.getEntity()).thenReturn(arrow);
        listener.onArrowLaunch(launch);

        shooterEligible.set(false);
        victimEligible.set(false);
        damage(listener, arrow, victim);
        assertEquals(0, store.snapshot(FIRST).orElseThrow().value(StatKey.ARROW_HITS_DEALT));
        assertEquals(0, store.snapshot(SECOND).orElseThrow().value(StatKey.ARROW_HITS_TAKEN));

        victimEligible.set(true);
        damage(listener, arrow, victim);
        assertEquals(1, store.snapshot(SECOND).orElseThrow().value(StatKey.ARROW_HITS_TAKEN));
        assertEquals(0, store.snapshot(FIRST).orElseThrow().value(StatKey.ARROW_HITS_DEALT));

        shooterEligible.set(true);
        victimEligible.set(false);
        damage(listener, arrow, victim);
        assertEquals(1, store.snapshot(FIRST).orElseThrow().value(StatKey.ARROW_HITS_DEALT));
        assertEquals(3, store.snapshot(SECOND).orElseThrow().value(StatKey.ARROW_DAMAGE_TAKEN));

        victimEligible.set(true);
        damage(listener, arrow, victim);
        damage(listener, arrow, player(THIRD));
        damage(listener, arrow, player(THIRD));
        PlayerStatsSnapshot snapshot = store.snapshot(FIRST).orElseThrow();
        assertEquals(1, snapshot.value(StatKey.ARROW_HITS_DEALT));
        assertEquals(1, snapshot.value(StatKey.ARROW_ACCURACY));
        assertEquals(12, snapshot.value(StatKey.ARROW_DAMAGE_DEALT));
        assertEquals(1, store.snapshot(SECOND).orElseThrow().value(StatKey.ARROW_HITS_TAKEN));
        assertEquals(6, store.snapshot(SECOND).orElseThrow().value(StatKey.ARROW_DAMAGE_TAKEN));
        assertEquals(1, store.snapshot(THIRD).orElseThrow().value(StatKey.ARROW_HITS_TAKEN));
    }

    @Test
    void tridentsDoNotEnterArcheryStatistics() {
        StatsStore store = store();
        StatsListener listener = listener(store);
        Trident trident = mock(Trident.class);
        Player shooter = player(FIRST);
        when(trident.getUniqueId()).thenReturn(UUID.randomUUID());
        when(trident.getShooter()).thenReturn(shooter);
        ProjectileLaunchEvent launch = mock(ProjectileLaunchEvent.class);
        when(launch.getEntity()).thenReturn(trident);
        listener.onArrowLaunch(launch);
        assertEquals(0, store.snapshot(FIRST).orElseThrow().value(StatKey.ARROWS_FIRED));
    }

    private StatsStore store() {
        StatsStore store = new StatsStore(directory.resolve("players.json"), Clock.systemUTC());
        store.join(FIRST, "First");
        store.join(SECOND, "Second");
        store.join(THIRD, "Third");
        return store;
    }

    private static StatsListener listener(StatsStore store) {
        StatsService service = mock(StatsService.class);
        when(service.eligible(any(UUID.class))).thenReturn(true);
        doAnswer(invocation -> {
            store.add(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(service).add(any(UUID.class), any(StatKey.class), anyDouble());
        stubArrowHits(service, store);
        return new StatsListener(service);
    }

    private static void stubArrowHits(StatsService service, StatsStore store) {
        doAnswer(invocation -> {
            UUID uuid = invocation.getArgument(0);
            Set<String> credited = invocation.getArgument(2);
            if (service.eligible(uuid) && credited.add("lifetime")) {
                store.add(uuid, invocation.getArgument(1), 1);
            }
            return null;
        }).when(service).recordArrowHit(any(UUID.class), any(StatKey.class), any());
    }

    private static Player player(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }

    private static PlayerMoveEvent move(Player player, Location from, Location to) {
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        return event;
    }

    private static void damage(StatsListener listener, AbstractArrow arrow, Player victim) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(arrow);
        when(event.getEntity()).thenReturn(victim);
        when(event.getFinalDamage()).thenReturn(3D);
        listener.onDamage(event);
    }
}
