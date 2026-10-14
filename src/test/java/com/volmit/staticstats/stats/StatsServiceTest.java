package com.volmit.staticstats.stats;

import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.records.PersonalBestPeriod;
import com.volmit.staticstats.participation.ParticipationSnapshot;
import com.volmit.staticstats.milestone.MilestoneCompletionEvent;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatsServiceTest {
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @TempDir
    Path directory;

    @Test
    void appliesHotReloadedWorldAndGameModeFiltersAndFlushesOfflineData() throws Exception {
        StaticConfig config = new StaticConfig();
        config.tracking.trackCreative = false;
        config.tracking.excludedWorlds = List.of("Excluded");
        AtomicReference<RuntimeConfig> runtime = new AtomicReference<>(RuntimeConfig.from(config));
        StatsService service = new StatsService(plugin(), runtime::get);
        service.start();
        try {
            service.connect(PLAYER, "River", new StatsService.TrackingContext("world", GameMode.SURVIVAL));
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 2);
            service.updateContext(PLAYER, new StatsService.TrackingContext("world", GameMode.CREATIVE));
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 10);
            service.updateContext(PLAYER, new StatsService.TrackingContext("EXCLUDED", GameMode.SURVIVAL));
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 20);
            assertEquals(2, service.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
            assertEquals(2, service.personalBests(PLAYER).orElseThrow()
                    .find(PersonalBestPeriod.DAY, StatKey.BLOCKS_BROKEN).orElseThrow().value());
            config.tracking.excludedWorlds = List.of();
            runtime.set(RuntimeConfig.from(config));
            service.refreshConfiguration();
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 3);
            config.general.enabled = false;
            runtime.set(RuntimeConfig.from(config));
            service.refreshConfiguration();
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 30);
            service.flushAsync().get(5, TimeUnit.SECONDS);
            assertTrue(service.lastSaveAt() > 0);
            assertEquals(5, service.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
            assertEquals(5, service.personalBests(PLAYER).orElseThrow()
                    .find(PersonalBestPeriod.DAY, StatKey.BLOCKS_BROKEN).orElseThrow().value());
        } finally {
            service.close();
        }
        StatsStore restored = new StatsStore(directory.resolve("storage/players.json"), Clock.systemUTC());
        restored.load();
        assertEquals(5, restored.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
        assertEquals("River", restored.find(PLAYER.toString()).orElseThrow().name());
    }

    @Test
    void failedLoadCannotOverwriteCorruptDataDuringDisable() throws IOException {
        Path file = directory.resolve("storage/players.json");
        Files.createDirectories(file.getParent());
        String invalid = "{not valid statistics}";
        Files.writeString(file, invalid);
        StatsService service = new StatsService(plugin(), () -> RuntimeConfig.from(new StaticConfig()));
        assertThrows(IOException.class, service::start);
        service.close();
        assertEquals(invalid, Files.readString(file));
        assertTrue(service.flushAsync().isCompletedExceptionally());
    }

    @Test
    void shutdownPersistsIncrementsWithoutAnExplicitFlush() throws IOException {
        StatsService service = new StatsService(plugin(), () -> RuntimeConfig.from(new StaticConfig()));
        service.start();
        service.connect(PLAYER, "River", new StatsService.TrackingContext("world", GameMode.SURVIVAL));
        service.add(PLAYER, StatKey.VOTES, 4);
        assertFalse(Files.exists(directory.resolve("storage/players.json")));
        service.close();
        StatsStore restored = new StatsStore(directory.resolve("storage/players.json"), Clock.systemUTC());
        restored.load();
        assertEquals(4, restored.snapshot(PLAYER).orElseThrow().value(StatKey.VOTES));
        assertEquals(0, restored.snapshot(PLAYER).orElseThrow().value(StatKey.QUITS));
    }

    @Test
    void leaderboardUsesCachedPagesAndRankingFuturesStopWithTheService() throws Exception {
        StatsService service = new StatsService(plugin(), () -> RuntimeConfig.from(new StaticConfig()));
        assertTrue(service.leaderboard(StatKey.BLOCKS_BROKEN, 0, 10).isEmpty());
        assertTrue(service.ranking(StatKey.BLOCKS_BROKEN).isCompletedExceptionally());
        service.start();
        try {
            UUID second = UUID.fromString("20000000-0000-0000-0000-000000000001");
            UUID third = UUID.fromString("30000000-0000-0000-0000-000000000001");
            StatsService.TrackingContext context = new StatsService.TrackingContext("world", GameMode.SURVIVAL);
            service.connect(PLAYER, "Zed", context);
            service.connect(second, "Amy", context);
            service.connect(third, "Bee", context);
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 10);
            service.add(second, StatKey.BLOCKS_BROKEN, 10);
            service.add(third, StatKey.BLOCKS_BROKEN, 2);
            RankingSnapshot ranking = service.ranking(StatKey.BLOCKS_BROKEN).get(5, TimeUnit.SECONDS);
            assertEquals(ranking, service.cachedRanking(StatKey.BLOCKS_BROKEN).orElseThrow());
            List<PlayerStatsSnapshot> page = service.leaderboard(StatKey.BLOCKS_BROKEN, 0, 2);
            assertEquals(List.of("Amy", "Zed"), page.stream().map(PlayerStatsSnapshot::name).toList());
            assertEquals("Bee", service.leaderboard(StatKey.BLOCKS_BROKEN, 2, Integer.MAX_VALUE).getFirst().name());
            assertTrue(service.leaderboard(StatKey.BLOCKS_BROKEN, 30, 10).isEmpty());
            assertTrue(service.leaderboard(StatKey.BLOCKS_BROKEN, 0, 0).isEmpty());
            assertThrows(UnsupportedOperationException.class, page::clear);
            assertThrows(IllegalArgumentException.class, () -> service.leaderboard(StatKey.BLOCKS_BROKEN, -1, 10));
        } finally {
            service.close();
        }
        assertTrue(service.cachedRanking(StatKey.BLOCKS_BROKEN).isEmpty());
        assertTrue(service.ranking(StatKey.BLOCKS_BROKEN).isCompletedExceptionally());
    }

    @Test
    void detailedRankingsIncludeOfflineRetainedValuesAfterCollectionIsDisabledAndAcrossRestart() throws Exception {
        StaticConfig config = new StaticConfig();
        config.details.selectedKeys = List.of(DetailKey.MINED_DIAMOND_ORE.id());
        AtomicReference<RuntimeConfig> runtime = new AtomicReference<>(RuntimeConfig.from(config));
        StatsService service = new StatsService(plugin(), runtime::get);
        long exact = 9_007_199_254_740_993L;
        UUID lower = new UUID(0, 2);
        UUID zero = new UUID(0, 3);
        service.start();
        try {
            service.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, exact);
            service.addDetail(lower, DetailKey.MINED_DIAMOND_ORE, exact - 1);
            service.add(zero, StatKey.BLOCKS_BROKEN, 100);
            config.details.enabled = false;
            config.details.selectedKeys = List.of(DetailKey.CRAFTED_ITEMS.id());
            config.general.enabled = false;
            runtime.set(RuntimeConfig.from(config));
            service.refreshConfiguration();
            service.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 123);
            DetailRankingSnapshot ranking = service.detailRanking(DetailKey.MINED_DIAMOND_ORE).get(5, TimeUnit.SECONDS);
            assertEquals(2, ranking.entries().size());
            assertEquals(3, ranking.totalProfiles());
            assertEquals(exact, ranking.find(PLAYER).orElseThrow().value());
            assertEquals(2, ranking.find(lower).orElseThrow().rank());
            assertEquals(1, ranking.find(lower).orElseThrow().gapToNext());
            assertTrue(ranking.find(zero).isEmpty());
        } finally {
            service.close();
        }
        StatsService restarted = new StatsService(plugin(), runtime::get);
        restarted.start();
        try {
            DetailRankingSnapshot ranking = restarted.detailRanking(DetailKey.MINED_DIAMOND_ORE).get(5, TimeUnit.SECONDS);
            assertEquals(exact, ranking.find(PLAYER).orElseThrow().value());
            assertEquals(1, ranking.find(lower).orElseThrow().gapToNext());
        } finally {
            restarted.close();
        }
    }

    @Test
    void closingTheServiceSettlesAllDetailedRankingRequestsAndStopsFurtherReads() throws Exception {
        StatsService service = new StatsService(plugin(), () -> RuntimeConfig.from(new StaticConfig()));
        assertTrue(service.cachedDetailRanking(DetailKey.MINED_DIAMOND_ORE).isEmpty());
        assertTrue(service.detailRanking(DetailKey.MINED_DIAMOND_ORE).isCompletedExceptionally());
        service.start();
        List<CompletableFuture<DetailRankingSnapshot>> pending = new ArrayList<>();
        try {
            service.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 4);
            for (DetailKey counter : DetailKey.values()) {
                pending.add(service.detailRanking(counter));
            }
        } finally {
            service.close();
        }
        for (CompletableFuture<DetailRankingSnapshot> completion : pending) {
            assertTrue(completion.isDone());
        }
        assertTrue(service.cachedDetailRanking(DetailKey.MINED_DIAMOND_ORE).isEmpty());
        assertTrue(service.detailRanking(DetailKey.MINED_DIAMOND_ORE).isCompletedExceptionally());
        service.close();
    }

    @Test
    void participationKeepsRealConnectionsWhileStatisticsArePausedAndStopsWithTheService() throws Exception {
        StaticConfig config = new StaticConfig();
        config.general.enabled = false;
        StatsService service = new StatsService(plugin(), () -> RuntimeConfig.from(config));
        assertTrue(service.cachedParticipation(StatsPeriod.DAY, 0).isEmpty());
        assertTrue(service.participation(StatsPeriod.DAY, 0).isCompletedExceptionally());
        assertThrows(IllegalArgumentException.class, () -> service.participation(StatsPeriod.LIFETIME, 0));
        service.start();
        try {
            service.connect(PLAYER, "River", new StatsService.TrackingContext("world", GameMode.SURVIVAL));
            service.quit(PLAYER);
            ParticipationSnapshot summary = service.participation(StatsPeriod.DAY, 0).get(5, TimeUnit.SECONDS);
            assertEquals(1, summary.uniquePlayers());
            assertEquals(1, summary.newPlayers());
            assertEquals(1, summary.completedSessions());
            assertEquals(0, summary.medianSessionMillis().orElseThrow());
            assertEquals(0, service.snapshot(PLAYER).orElseThrow().value(StatKey.JOINS));
        } finally {
            service.close();
        }
        assertTrue(service.cachedParticipation(StatsPeriod.DAY, 0).isEmpty());
        assertTrue(service.participation(StatsPeriod.DAY, 0).isCompletedExceptionally());
    }

    @Test
    void hotReloadedQualificationRulesReplaceTheRankingWithoutChangingRecordedStats() throws Exception {
        StaticConfig config = new StaticConfig();
        AtomicReference<RuntimeConfig> runtime = new AtomicReference<>(RuntimeConfig.from(config));
        StatsService service = new StatsService(plugin(), runtime::get);
        service.start();
        try {
            service.add(PLAYER, StatKey.PVP_KILLS, 30);
            service.add(PLAYER, StatKey.PVP_DEATHS, 10);
            RankingSnapshot initial = service.ranking(StatKey.KILL_DEATH_RATIO).get(5, TimeUnit.SECONDS);
            assertEquals(1, initial.entries().size());
            config.ranking.minPvpDeaths = 20;
            runtime.set(RuntimeConfig.from(config));
            service.refreshConfiguration();
            RankingSnapshot updated = service.ranking(StatKey.KILL_DEATH_RATIO).get(5, TimeUnit.SECONDS);
            assertTrue(updated.entries().isEmpty());
            assertEquals(1, updated.excludedCount());
            assertEquals(20, updated.qualification(PLAYER).orElseThrow().minimum());
            assertEquals(3, service.snapshot(PLAYER).orElseThrow().value(StatKey.KILL_DEATH_RATIO));
            assertEquals(30, service.snapshot(PLAYER).orElseThrow().value(StatKey.PVP_KILLS));
            assertEquals(10, service.snapshot(PLAYER).orElseThrow().value(StatKey.PVP_DEATHS));
            service.flushAsync().get(5, TimeUnit.SECONDS);
            StatsStore restored = new StatsStore(directory.resolve("storage/players.json"), Clock.systemUTC());
            restored.load();
            assertEquals(service.snapshot(PLAYER).orElseThrow(), restored.snapshot(PLAYER).orElseThrow());
        } finally {
            service.close();
        }
    }

    @Test
    void concurrentShutdownReconfigurationAndIncrementsCompleteWithTheFinalStateSaved() throws Exception {
        AtomicReference<RuntimeConfig> runtime = new AtomicReference<>(RuntimeConfig.from(new StaticConfig()));
        StatsService service = new StatsService(plugin(), runtime::get);
        service.start();
        service.connect(PLAYER, "River", new StatsService.TrackingContext("world", GameMode.SURVIVAL));
        CountDownLatch started = new CountDownLatch(2);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> increments = workers.submit(() -> {
                for (int update = 0; update < 1_000; update++) {
                    service.add(PLAYER, StatKey.BLOCKS_BROKEN, 1);
                    if (update == 0) {
                        started.countDown();
                    }
                }
            });
            Future<?> reconfiguration = workers.submit(() -> {
                for (int update = 0; update < 100; update++) {
                    StaticConfig config = new StaticConfig();
                    config.general.enabled = update % 2 == 0;
                    runtime.set(RuntimeConfig.from(config));
                    service.refreshConfiguration();
                    if (update == 0) {
                        started.countDown();
                    }
                }
            });
            Future<?> shutdown = workers.submit(() -> {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                service.close();
                return null;
            });
            increments.get(5, TimeUnit.SECONDS);
            reconfiguration.get(5, TimeUnit.SECONDS);
            shutdown.get(5, TimeUnit.SECONDS);
            StatsStore restored = new StatsStore(directory.resolve("storage/players.json"), Clock.systemUTC());
            restored.load();
            assertEquals(service.snapshot(PLAYER).orElseThrow().values(), restored.snapshot(PLAYER).orElseThrow().values());
            assertTrue(service.flushAsync().isCompletedExceptionally());
        } finally {
            workers.shutdownNow();
            service.close();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsLiveTimezoneChangesEvenBeforeTheFirstPlayerCreatesHistory() throws IOException {
        StatsService service = new StatsService(plugin(), () -> RuntimeConfig.from(new StaticConfig()));
        service.start();
        try {
            HistorySettings candidate = new HistorySettings(ZoneId.of("America/Denver"), 5_000, 35, 12, 12, 20);
            assertThrows(IllegalArgumentException.class, () -> service.validateHistorySettings(candidate));
            for (String alias : List.of("Z", "Etc/UTC")) {
                HistorySettings aliased = new HistorySettings(ZoneId.of(alias), 5_000, 35, 12, 12, 20);
                assertEquals(service.historyZone().normalized(), aliased.zone().normalized());
                assertThrows(IllegalArgumentException.class, () -> service.validateHistorySettings(aliased));
            }
            assertEquals(0, service.playerCount());
            service.validateHistorySettings(new HistorySettings(service.historyZone(), 5_000, 35, 12, 12, 20));
        } finally {
            service.close();
        }
    }

    @Test
    void milestoneSubscriptionsRunAfterDurableSaveAndIsolateFailuresAndUnsubscription() throws Exception {
        StaticConfig config = new StaticConfig();
        config.milestones.thresholds = Map.of("blocks_broken", List.of(1D, 2D, 3D));
        StatsService service = new StatsService(plugin(), () -> RuntimeConfig.from(config));
        service.start();
        List<MilestoneCompletionEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicInteger persistedCount = new AtomicInteger();
        AutoCloseable failing = service.onMilestoneCompletion(event -> {
            throw new IllegalStateException("Subscriber failure");
        });
        AutoCloseable subscription = service.onMilestoneCompletion(event -> {
            callbackThread.set(Thread.currentThread());
            StatsStore persisted = new StatsStore(directory.resolve("storage/players.json"), Clock.systemUTC());
            try {
                persisted.load();
                persistedCount.set(persisted.milestoneCompletions(PLAYER).size());
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            events.add(event);
        });
        try {
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 1);
            service.flushAsync().get(5, TimeUnit.SECONDS);
            assertEquals(1, events.size());
            assertEquals(1, persistedCount.get());
            assertEquals("Static-Statistics", callbackThread.get().getName());
            assertTrue(events.getFirst().sessionId().isEmpty());
            failing.close();
            failing.close();
            subscription.close();
            subscription.close();
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 1);
            service.flushAsync().get(5, TimeUnit.SECONDS);
            assertEquals(1, events.size());
            assertEquals(2, service.milestoneCompletions(PLAYER).size());
        } finally {
            failing.close();
            subscription.close();
            service.close();
        }
        assertThrows(IllegalStateException.class, () -> service.onMilestoneCompletion(events::add));
        StatsService restarted = new StatsService(plugin(), () -> RuntimeConfig.from(config));
        restarted.start();
        try (AutoCloseable restartedSubscription = restarted.onMilestoneCompletion(events::add)) {
            restarted.flushAsync().get(5, TimeUnit.SECONDS);
            assertEquals(1, events.size());
        } finally {
            restarted.close();
        }
    }

    @Test
    void seasonEligibilityAndArrowDedupRemainIndependentOfLifetimeFilters() throws Exception {
        StaticConfig config = new StaticConfig();
        config.tracking.trackCreative = false;
        StaticConfig.Season event = new StaticConfig.Season();
        event.id = "creative";
        event.name = "Creative event";
        event.startsAt = Instant.now().minusSeconds(10).toString();
        event.endsAt = Instant.now().plusSeconds(60).toString();
        event.startsAt = Instant.ofEpochMilli(Instant.parse(event.startsAt).toEpochMilli()).toString();
        event.endsAt = Instant.ofEpochMilli(Instant.parse(event.endsAt).toEpochMilli()).toString();
        event.trackCreative = true;
        config.seasons.events = List.of(event);
        StatsService service = new StatsService(plugin(), () -> RuntimeConfig.from(config));
        service.start();
        try {
            Set<String> shotCredits = ConcurrentHashMap.newKeySet();
            service.connect(PLAYER, "River", new StatsService.TrackingContext("world", GameMode.CREATIVE));
            assertTrue(service.eligible(PLAYER));
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 4);
            service.recordArrowHit(PLAYER, StatKey.ARROW_HITS_DEALT, shotCredits);
            assertEquals(0, service.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
            assertEquals(4, service.seasonSnapshot(PLAYER, "creative").orElseThrow().profile().value(StatKey.BLOCKS_BROKEN));
            assertEquals(0, service.snapshot(PLAYER).orElseThrow().value(StatKey.ARROW_HITS_DEALT));
            assertEquals(1, service.seasonSnapshot(PLAYER, "creative").orElseThrow().profile().value(StatKey.ARROW_HITS_DEALT));
            service.updateContext(PLAYER, new StatsService.TrackingContext("world", GameMode.SURVIVAL));
            service.recordArrowHit(PLAYER, StatKey.ARROW_HITS_DEALT, shotCredits);
            service.recordArrowHit(PLAYER, StatKey.ARROW_HITS_DEALT, shotCredits);
            assertEquals(1, service.snapshot(PLAYER).orElseThrow().value(StatKey.ARROW_HITS_DEALT));
            assertEquals(1, service.seasonSnapshot(PLAYER, "creative").orElseThrow().profile().value(StatKey.ARROW_HITS_DEALT));
            assertEquals(Set.of("lifetime", "season/creative"), shotCredits);
            service.flushAsync().get(5, TimeUnit.SECONDS);
            StatsStore persisted = new StatsStore(directory.resolve("storage/players.json"), Clock.systemUTC());
            persisted.load();
            assertEquals(4, persisted.seasonEngine().profile(PLAYER, "creative").orElseThrow().profile().value(StatKey.BLOCKS_BROKEN));
            persisted.seasonEngine().close();
        } finally {
            service.close();
        }
    }

    @Test
    void detailedCountersUseLifetimeFiltersAndKeepDisabledSelectionsAcrossRestart() throws Exception {
        StaticConfig config = new StaticConfig();
        config.tracking.trackCreative = false;
        config.tracking.excludedWorlds = List.of("excluded");
        config.details.selectedKeys = List.of(DetailKey.MINED_DIAMOND_ORE.id());
        StaticConfig.Season event = new StaticConfig.Season();
        event.id = "creative";
        event.name = "Creative";
        event.startsAt = Instant.ofEpochMilli(System.currentTimeMillis() - 1_000).toString();
        event.endsAt = Instant.ofEpochMilli(System.currentTimeMillis() + 60_000).toString();
        event.trackCreative = true;
        config.seasons.events = List.of(event);
        AtomicReference<RuntimeConfig> runtime = new AtomicReference<>(RuntimeConfig.from(config));
        StatsService service = new StatsService(plugin(), runtime::get);
        service.start();
        try {
            service.connect(PLAYER, "River", new StatsService.TrackingContext("world", GameMode.CREATIVE));
            assertTrue(service.eligible(PLAYER));
            service.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 100);
            assertEquals(0, service.details(PLAYER).orElseThrow().value(DetailKey.MINED_DIAMOND_ORE));
            service.updateContext(PLAYER, new StatsService.TrackingContext("EXCLUDED", GameMode.SURVIVAL));
            service.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 100);
            service.updateContext(PLAYER, new StatsService.TrackingContext("world", GameMode.SPECTATOR));
            service.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 100);
            service.updateContext(PLAYER, new StatsService.TrackingContext("world", GameMode.SURVIVAL));
            service.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 2);
            service.addDetail(PLAYER, DetailKey.CRAFTED_ITEMS, 100);
            assertEquals(2, service.details(PLAYER).orElseThrow().value(DetailKey.MINED_DIAMOND_ORE));
            assertEquals(0, service.details(PLAYER).orElseThrow().value(DetailKey.CRAFTED_ITEMS));
            config.details.enabled = false;
            install(service, runtime, config);
            service.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 100);
            config.details.enabled = true;
            config.details.selectedKeys = List.of(DetailKey.CRAFTED_ITEMS.id());
            install(service, runtime, config);
            service.addDetail(PLAYER, DetailKey.MINED_DIAMOND_ORE, 100);
            service.addDetail(PLAYER, DetailKey.CRAFTED_ITEMS, 3);
            config.general.enabled = false;
            install(service, runtime, config);
            service.addDetail(PLAYER, DetailKey.CRAFTED_ITEMS, 100);
            assertEquals(2, service.details(PLAYER).orElseThrow().value(DetailKey.MINED_DIAMOND_ORE));
            assertEquals(3, service.details(PLAYER).orElseThrow().value(DetailKey.CRAFTED_ITEMS));
        } finally {
            service.close();
        }
        service.addDetail(PLAYER, DetailKey.CRAFTED_ITEMS, 100);
        StatsStore restored = new StatsStore(directory.resolve("storage/players.json"), Clock.systemUTC());
        restored.load();
        assertEquals(2, restored.details(PLAYER).orElseThrow().value(DetailKey.MINED_DIAMOND_ORE));
        assertEquals(3, restored.details(PLAYER).orElseThrow().value(DetailKey.CRAFTED_ITEMS));
        restored.seasonEngine().close();
    }

    @Test
    void rejectedSeasonCommitPreservesTrackingAndPublishedConfiguration() throws Exception {
        StaticConfig config = new StaticConfig();
        StaticConfig.Season event = new StaticConfig.Season();
        event.id = "building";
        event.name = "Building";
        event.startsAt = Instant.ofEpochMilli(System.currentTimeMillis() - 10_000).toString();
        event.endsAt = Instant.ofEpochMilli(System.currentTimeMillis() + 60_000).toString();
        config.seasons.events = List.of(event);
        AtomicReference<RuntimeConfig> runtime = new AtomicReference<>(RuntimeConfig.from(config));
        StatsService service = new StatsService(plugin(), runtime::get);
        service.start();
        try {
            StaticConfig invalid = config.copy();
            invalid.general.enabled = false;
            invalid.seasons.events.getFirst().name = "Changed";
            RuntimeConfig rejected = RuntimeConfig.from(invalid);
            assertThrows(IllegalArgumentException.class, () -> service.installConfiguration(rejected, () -> runtime.set(rejected)));
            assertTrue(runtime.get().enabled());
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 2);
            assertEquals(2, service.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
            assertEquals(2, service.seasonSnapshot(PLAYER, "building").orElseThrow().profile().value(StatKey.BLOCKS_BROKEN));
            StaticConfig valid = config.copy();
            valid.general.enabled = false;
            RuntimeConfig accepted = RuntimeConfig.from(valid);
            service.installConfiguration(accepted, () -> runtime.set(accepted));
            assertFalse(runtime.get().enabled());
            service.add(PLAYER, StatKey.BLOCKS_BROKEN, 3);
            assertEquals(2, service.snapshot(PLAYER).orElseThrow().value(StatKey.BLOCKS_BROKEN));
            assertEquals(2, service.seasonSnapshot(PLAYER, "building").orElseThrow().profile().value(StatKey.BLOCKS_BROKEN));
        } finally {
            service.close();
        }
    }

    private static void install(StatsService service, AtomicReference<RuntimeConfig> runtime, StaticConfig config) {
        RuntimeConfig next = RuntimeConfig.from(config);
        service.installConfiguration(next, () -> runtime.set(next));
    }

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StaticTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getServicesManager()).thenReturn(mock(ServicesManager.class));
        return plugin;
    }
}
