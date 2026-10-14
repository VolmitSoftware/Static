package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.privacy.VisibilityFixtures;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.presentation.PresentationService;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.RankedPlayer;
import com.volmit.staticstats.stats.RankingRules;
import com.volmit.staticstats.stats.StatsService;
import com.volmit.staticstats.stats.StatsPeriod;
import com.volmit.staticstats.stats.PeriodStatsSnapshot;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.SessionEndReason;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StatsGuiTest {
    @TempDir
    Path directory;

    @Test
    void categoriesCoverEachStatisticOnceAndPreserveDefaultOrder() {
        assertEquals(List.of(StatKey.values()), StatsGui.Category.ALL.statistics());
        EnumSet<StatKey> found = EnumSet.noneOf(StatKey.class);
        StatsGui.Category category = StatsGui.Category.ALL.next();
        while (category != StatsGui.Category.ALL) {
            assertFalse(category.statistics().isEmpty());
            for (StatKey statistic : category.statistics()) {
                assertFalse(found.contains(statistic));
                found.add(statistic);
            }
            category = category.next();
        }
        assertEquals(EnumSet.allOf(StatKey.class), found);
    }

    @Test
    void newerProfileRequestSupersedesOlderRequestFromTheSameInventory() {
        exerciseAbandonedRequest(false);
    }

    @Test
    void closingInventoryCancelsPendingProfileLookup() {
        exerciseAbandonedRequest(true);
    }

    @Test
    void rankingCompletionRoutesBlockAndMinecartSendersToTheirOwners() {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        PresentationService presentation = mock(PresentationService.class);
        BlockCommandSender blockSender = mock(BlockCommandSender.class);
        CommandMinecart minecart = mock(CommandMinecart.class);
        Block block = mock(Block.class);
        Location location = mock(Location.class);
        CompletableFuture<RankingSnapshot> blockRanking = new CompletableFuture<>();
        CompletableFuture<RankingSnapshot> entityRanking = new CompletableFuture<>();
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger(StatsGuiTest.class.getName()));
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getPresentationService()).thenReturn(presentation);
        when(blockSender.getBlock()).thenReturn(block);
        when(block.getLocation()).thenReturn(location);
        when(blockSender.hasPermission("static.top")).thenReturn(true);
        when(minecart.hasPermission("static.top")).thenReturn(true);
        when(stats.ranking(StatKey.VOTES, StatsPeriod.LIFETIME)).thenReturn(blockRanking, entityRanking);
        try (MockedConstruction<ProfilePreferences> preferences = mockConstruction(ProfilePreferences.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            StatsGui gui = new StatsGui(plugin);
            gui.top(blockSender, "votes", 1, StatsPeriod.LIFETIME);
            gui.top(minecart, "votes", 1, StatsPeriod.LIFETIME);
            blockRanking.completeExceptionally(new IllegalStateException("unavailable"));
            entityRanking.completeExceptionally(new IllegalStateException("unavailable"));
            scheduler.verify(() -> FoliaScheduler.runRegion(eq(plugin), eq(location), any(Runnable.class)));
            scheduler.verify(() -> FoliaScheduler.runEntity(eq(plugin), eq(minecart), any(Runnable.class)));
            verifyNoInteractions(presentation);
            verify(block).getLocation();
        }
    }

    @Test
    void cancelledReplacementKeepsTheActuallyOpenInventoryTrackedForShutdown() {
        exerciseTrackedInventory(1, StatsPeriod.LIFETIME);
    }

    @Test
    void profileWithUnknownRecordingStartRendersWithStrictLanguageArguments() {
        exerciseTrackedInventory(0, StatsPeriod.LIFETIME);
    }

    @Test
    void calendarProfileShowsSelectedWindowAndPartialCoverage() {
        exerciseTrackedInventory(System.currentTimeMillis(), StatsPeriod.DAY);
    }

    @Test
    void leadingPlayerRankingRendersWithStrictLanguageArguments() {
        exerciseLeadingRanking(false);
    }

    @Test
    void rankingDeliveryRequestsCurrentRulesWhenReloadOvertakesItsCallback() {
        exerciseLeadingRanking(true);
    }

    private void exerciseLeadingRanking(boolean reload) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        ConfigService config = mock(ConfigService.class);
        RuntimeConfig runtime = mock(RuntimeConfig.class);
        Player player = mock(Player.class);
        RankingSnapshot ranking = mock(RankingSnapshot.class);
        RankingSnapshot refreshed = mock(RankingSnapshot.class);
        StaticConfig changed = new StaticConfig();
        changed.ranking.minPvpDeaths = 25;
        RankingRules revised = RuntimeConfig.from(changed).rankingRules();
        AtomicReference<RankingRules> currentRules = new AtomicReference<>(RankingRules.defaults());
        List<Runnable> deliveries = new ArrayList<>();
        UUID uuid = UUID.randomUUID();
        RankedPlayer leader = new RankedPlayer(new PlayerStatsSnapshot(uuid, "River", Map.of(StatKey.VOTES, 10D), 1, 1), 1, 1, 10, 0);
        List<String> messages = new ArrayList<>();
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger(StatsGuiTest.class.getName()));
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getConfigService()).thenReturn(config);
        when(config.runtime()).thenReturn(runtime);
        when(runtime.leaderboardLimit()).thenReturn(10);
        when(runtime.rankingRules()).thenAnswer(invocation -> currentRules.get());
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("static.top")).thenReturn(true);
        when(ranking.statistic()).thenReturn(StatKey.VOTES);
        when(ranking.rules()).thenReturn(RuntimeConfig.from(new StaticConfig()).rankingRules());
        when(ranking.entries()).thenReturn(List.of(leader));
        when(ranking.find(uuid)).thenReturn(Optional.of(leader));
        when(ranking.refreshedAt()).thenReturn(1L);
        when(ranking.period()).thenReturn(StatsPeriod.LIFETIME);
        when(ranking.window()).thenReturn(StatsPeriod.LIFETIME.window(1, ZoneId.of("UTC")));
        when(ranking.historyZone()).thenReturn(ZoneId.of("UTC"));
        when(refreshed.statistic()).thenReturn(StatKey.VOTES);
        when(refreshed.rules()).thenReturn(revised);
        when(refreshed.entries()).thenReturn(List.of(leader));
        when(refreshed.find(uuid)).thenReturn(Optional.of(leader));
        when(refreshed.refreshedAt()).thenReturn(2L);
        when(refreshed.period()).thenReturn(StatsPeriod.LIFETIME);
        when(refreshed.window()).thenReturn(StatsPeriod.LIFETIME.window(1, ZoneId.of("UTC")));
        when(refreshed.historyZone()).thenReturn(ZoneId.of("UTC"));
        when(stats.ranking(StatKey.VOTES, StatsPeriod.LIFETIME)).thenReturn(CompletableFuture.completedFuture(ranking), CompletableFuture.completedFuture(refreshed));
        LanguageService language = new LanguageService(directory.toFile(), plugin.getLogger());
        try (MockedConstruction<ProfilePreferences> preferences = mockConstruction(ProfilePreferences.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class)) {
            when(plugin.getLanguageService()).thenReturn(language);
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class))).thenAnswer(invocation -> {
                if (reload) {
                    deliveries.add(invocation.getArgument(2));
                } else {
                    invocation.<Runnable>getArgument(2).run();
                }
                return true;
            });
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(player), any(String.class))).thenAnswer(invocation -> {
                messages.add(invocation.getArgument(1));
                return null;
            });
            new StatsGui(plugin).top(player, "votes", 1, StatsPeriod.LIFETIME);
            if (reload) {
                currentRules.set(revised);
                deliveries.getFirst().run();
                assertTrue(messages.isEmpty());
                assertEquals(2, deliveries.size());
                deliveries.getLast().run();
            }
            assertTrue(messages.stream().anyMatch(message -> message.contains("Your rank: #1")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("You share or hold the highest score.")));
        } finally {
            language.close();
        }
    }

    @Test
    void emptyRatioRankingExplainsSnapshotRequirementsAndPersonalProgress() {
        StaticConfig configuration = new StaticConfig();
        configuration.ranking.minPvpDeaths = 2.5;
        RankingRules rules = RuntimeConfig.from(configuration).rankingRules();
        List<String> messages = emptyRatioMessages(rules, Map.of(StatKey.PVP_KILLS, 9D, StatKey.PVP_DEATHS, 2D));
        assertTrue(messages.stream().anyMatch(message -> message.contains("Ranking minimum · Player combat deaths: 2.5")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("Qualified: 0/1 profiles · Excluded: 1")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("No recorded profiles meet this ranking's requirements yet.")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("You are not qualified for this ranking yet.")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("Your Player combat deaths: 2 / minimum 2.5")));
        assertFalse(messages.stream().anyMatch(message -> message.contains("not in this ranking snapshot")));
    }

    @Test
    void zeroMinimumStillExplainsWhyZeroDenominatorIsNotQualified() {
        StaticConfig configuration = new StaticConfig();
        configuration.ranking.minPvpDeaths = 0;
        List<String> messages = emptyRatioMessages(RuntimeConfig.from(configuration).rankingRules(), Map.of(StatKey.PVP_KILLS, 9D));
        assertTrue(messages.stream().anyMatch(message -> message.contains("The denominator must also be greater than zero.")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("Your Player combat deaths: 0 / minimum 0")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("You are not qualified for this ranking yet.")));
    }

    private List<String> emptyRatioMessages(RankingRules rules, Map<StatKey, Double> values) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        ConfigService config = mock(ConfigService.class);
        RuntimeConfig runtime = mock(RuntimeConfig.class);
        Player player = mock(Player.class);
        RankingSnapshot ranking = mock(RankingSnapshot.class);
        UUID uuid = UUID.randomUUID();
        PlayerStatsSnapshot profile = new PlayerStatsSnapshot(uuid, "River", values, 1, 1);
        List<String> messages = new ArrayList<>();
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger(StatsGuiTest.class.getName()));
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getConfigService()).thenReturn(config);
        when(config.runtime()).thenReturn(runtime);
        when(runtime.leaderboardLimit()).thenReturn(10);
        when(runtime.rankingRules()).thenReturn(rules);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("static.top")).thenReturn(true);
        when(ranking.statistic()).thenReturn(StatKey.KILL_DEATH_RATIO);
        when(ranking.rules()).thenReturn(rules);
        when(ranking.entries()).thenReturn(List.of());
        when(ranking.qualification(uuid)).thenReturn(rules.evaluate(StatKey.KILL_DEATH_RATIO, profile));
        when(ranking.totalProfiles()).thenReturn(1);
        when(ranking.excludedCount()).thenReturn(1);
        when(ranking.refreshedAt()).thenReturn(1L);
        when(ranking.period()).thenReturn(StatsPeriod.LIFETIME);
        when(ranking.window()).thenReturn(StatsPeriod.LIFETIME.window(1, ZoneId.of("UTC")));
        when(ranking.historyZone()).thenReturn(ZoneId.of("UTC"));
        when(stats.ranking(StatKey.KILL_DEATH_RATIO, StatsPeriod.LIFETIME)).thenReturn(CompletableFuture.completedFuture(ranking));
        LanguageService language = new LanguageService(directory.toFile(), plugin.getLogger());
        try (MockedConstruction<ProfilePreferences> preferences = mockConstruction(ProfilePreferences.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class)) {
            when(plugin.getLanguageService()).thenReturn(language);
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(2).run();
                return true;
            });
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(player), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            new StatsGui(plugin).top(player, StatKey.KILL_DEATH_RATIO.id(), 1, StatsPeriod.LIFETIME);
            return messages;
        } finally {
            language.close();
        }
    }

    private void exerciseTrackedInventory(long recordedSince, StatsPeriod period) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        ConfigService config = mock(ConfigService.class);
        LanguageService language = new LanguageService(directory.toFile(), Logger.getLogger(StatsGuiTest.class.getName()));
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory initial = mock(Inventory.class);
        Inventory opened = mock(Inventory.class);
        Inventory cancelled = mock(Inventory.class);
        AtomicReference<Inventory> current = new AtomicReference<>(initial);
        List<BukkitInventoryShutdown.View> drained = new ArrayList<>();
        List<String> lore = new ArrayList<>();
        UUID uuid = UUID.randomUUID();
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger(StatsGuiTest.class.getName()));
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getConfigService()).thenReturn(config);
        when(config.runtime()).thenReturn(RuntimeConfig.from(new StaticConfig()));
        when(plugin.getLanguageService()).thenReturn(language);
        when(stats.historyZone()).thenReturn(ZoneId.of("UTC"));
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("static.stats")).thenReturn(true);
        when(stats.periodSnapshot(uuid, period)).thenReturn(Optional.of(new PeriodStatsSnapshot(
                period.window(System.currentTimeMillis(), ZoneId.of("UTC")), new PlayerStatsSnapshot(uuid, "River",
                Map.of(StatKey.PVP_KILLS, 20D, StatKey.PVP_DEATHS, 10D), 1, recordedSince), recordedSince, ZoneId.of("UTC"))));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(plugin).executeAsync(any());
        when(player.openInventory(opened)).thenAnswer(invocation -> {
            current.set(opened);
            return view;
        });
        when(player.openInventory(cancelled)).thenReturn(null);
        try (MockedConstruction<ProfilePreferences> preferences = mockConstruction(ProfilePreferences.class, (instance, context) -> {
                 when(instance.ready()).thenReturn(CompletableFuture.completedFuture(null));
                 when(instance.favorites(uuid)).thenReturn(List.of());
             });
             MockedConstruction<ItemStack> items = mockConstruction(ItemStack.class, (instance, context) -> {
                 ItemMeta meta = mock(ItemMeta.class);
                 when(instance.getItemMeta()).thenReturn(meta);
                 doAnswer(invocation -> {
                     List<String> lines = invocation.getArgument(0);
                     lore.addAll(lines);
                     return null;
                 }).when(meta).setLore(any());
             });
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<BukkitInventoryViews> views = mockStatic(BukkitInventoryViews.class);
             MockedStatic<BukkitInventoryShutdown> shutdown = mockStatic(BukkitInventoryShutdown.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(2).run();
                return true;
            });
            views.when(() -> BukkitInventoryViews.top(view)).thenAnswer(invocation -> current.get());
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(String.class))).thenReturn(opened, cancelled);
            shutdown.when(() -> BukkitInventoryShutdown.drain(eq(plugin), any())).thenAnswer(invocation -> {
                drained.addAll(invocation.getArgument(1));
                return null;
            });
            StatsGui gui = new StatsGui(plugin);
            gui.show(player, "self", period);
            gui.show(player, "self", period);
            gui.close();
            assertEquals(List.of(opened), drained.stream().map(BukkitInventoryShutdown.View::inventory).toList());
            assertTrue(lore.stream().anyMatch(line -> line.contains("Numerator · Player kills:")));
            assertTrue(lore.stream().anyMatch(line -> line.contains("Denominator · Player combat deaths:")));
            assertTrue(lore.stream().anyMatch(line -> line.contains("Ranking minimum · Player combat deaths:")));
            assertTrue(lore.stream().anyMatch(line -> line.contains("Qualified for this ranking")));
            assertTrue(lore.stream().anyMatch(line -> line.contains("Not qualified for this ranking yet")));
            assertTrue(lore.stream().anyMatch(line -> line.contains("Activity uses detected game input, not proof of attention.")));
            if (period == StatsPeriod.DAY) {
                assertTrue(lore.stream().anyMatch(line -> line.contains("Recorded totals · Today")));
                assertTrue(lore.stream().anyMatch(line -> line.contains("Calendar time zone: UTC")));
                assertTrue(lore.stream().anyMatch(line -> line.contains("Partial coverage: recording began during this window.")));
            }
        } finally {
            language.close();
        }
    }

    private void exerciseAbandonedRequest(boolean close) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        PresentationService presentation = mock(PresentationService.class);
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        UUID uuid = UUID.randomUUID();
        List<Runnable> lookups = new ArrayList<>();
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger(StatsGuiTest.class.getName()));
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getPresentationService()).thenReturn(presentation);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("static.stats")).thenReturn(true);
        when(stats.find(any(), eq(StatsPeriod.LIFETIME))).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            lookups.add(invocation.getArgument(0));
            return null;
        }).when(plugin).executeAsync(any());
        try (MockedConstruction<ProfilePreferences> preferences = mockConstruction(ProfilePreferences.class,
                (instance, context) -> when(instance.ready()).thenReturn(CompletableFuture.completedFuture(null)));
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<BukkitInventoryViews> views = mockStatic(BukkitInventoryViews.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(2).run();
                return true;
            });
            views.when(() -> BukkitInventoryViews.top(view)).thenReturn(inventory);
            StatsGui gui = new StatsGui(plugin);
            gui.show(player, "Older", StatsPeriod.LIFETIME);
            if (close) {
                InventoryCloseEvent event = mock(InventoryCloseEvent.class);
                when(event.getPlayer()).thenReturn(player);
                when(event.getInventory()).thenReturn(inventory);
                gui.onClose(event);
            } else {
                gui.show(player, "Newer", StatsPeriod.LIFETIME);
            }
            lookups.getFirst().run();
            verifyNoInteractions(presentation);
            if (!close) {
                lookups.getLast().run();
                verify(presentation).command(eq(player), eq(StatsMessages.UNKNOWN_PLAYER), any(), eq(FeedbackTone.FAILURE));
            }
        }
    }
}
