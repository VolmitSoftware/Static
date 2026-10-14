package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.privacy.VisibilityFixtures;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.milestone.MilestoneCompletion;
import com.volmit.staticstats.milestone.MilestoneCompletionEvent;
import com.volmit.staticstats.milestone.MilestoneDefinition;
import com.volmit.staticstats.milestone.MilestoneProgress;
import com.volmit.staticstats.presentation.PresentationService;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.SessionEndReason;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MilestonesGuiTest {
    private static final UUID PLAYER = new UUID(0, 1);

    @TempDir
    Path directory;

    @Test
    void consoleShowsExactTargetsNextUnmetAndRetainedHistoricalBadges() {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        ConfigService config = mock(ConfigService.class);
        CommandSender sender = mock(CommandSender.class);
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getConfigService()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(config.runtime()).thenReturn(RuntimeConfig.from(new StaticConfig()));
        when(sender.hasPermission("static.milestones")).thenReturn(true);
        PlayerStatsSnapshot profile = new PlayerStatsSnapshot(PLAYER, "River", Map.of(), 1, 1);
        when(stats.find("River", StatsPeriod.LIFETIME)).thenReturn(Optional.of(profile));
        MilestoneDefinition fraction = new MilestoneDefinition(StatKey.VOTES, 0.5);
        MilestoneDefinition tiny = new MilestoneDefinition(StatKey.VOTES, 0.0000000001);
        MilestoneDefinition time = new MilestoneDefinition(StatKey.ACTIVE_TIME, 500);
        MilestoneCompletion archived = new MilestoneCompletion(new MilestoneDefinition(StatKey.BLOCKS_BROKEN, 50), 1, 55, true);
        when(stats.milestones(PLAYER)).thenReturn(List.of(new MilestoneProgress(tiny, 0, Optional.empty()),
                new MilestoneProgress(fraction, 0.25, Optional.empty()), new MilestoneProgress(time, 250, Optional.empty())));
        when(stats.milestoneCompletions(PLAYER)).thenReturn(List.of(archived));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(plugin).executeAsync(any());
        List<String> messages = new ArrayList<>();
        LanguageService language = new LanguageService(directory.toFile(), plugin.getLogger());
        try (MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class)) {
            when(plugin.getLanguageService()).thenReturn(language);
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(sender), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            new MilestonesGui(plugin).show(sender, "River", 1);
            assertTrue(messages.contains("Progress: 0.25 / 0.5"));
            assertTrue(messages.contains("Progress: 0 / 0.0000000001"));
            assertTrue(messages.contains("Progress: 250 ms / 500 ms"));
            assertEquals(2, messages.stream().filter(line -> line.equals("Next target in this statistic")).count());
            assertTrue(messages.contains("Already met when first checked."));
            assertTrue(messages.contains("Target removed; the badge is retained."));
            assertTrue(messages.contains("Badges: 1 · Active targets: 3"));
        } finally {
            language.close();
        }
    }

    @Test
    void completionDeliveryRequiresTheOriginalOpenSessionAndCurrentPermission() {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        ConfigService config = mock(ConfigService.class);
        Player player = mock(Player.class);
        UUID session = UUID.randomUUID();
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getConfigService()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(config.runtime()).thenReturn(RuntimeConfig.from(new StaticConfig()));
        when(player.getUniqueId()).thenReturn(PLAYER);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("static.milestones")).thenReturn(true);
        when(stats.sessions(PLAYER)).thenReturn(List.of(new PlayerSession(session, 1, 0, 1, 0, 0, 0, SessionEndReason.OPEN)));
        MilestoneCompletion completion = new MilestoneCompletion(new MilestoneDefinition(StatKey.VOTES, 0.5), 1, 1, false);
        List<Runnable> deliveries = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        LanguageService language = new LanguageService(directory.toFile(), plugin.getLogger());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class)) {
            when(plugin.getLanguageService()).thenReturn(language);
            bukkit.when(() -> Bukkit.getPlayer(PLAYER)).thenReturn(player);
            scheduler.when(() -> FoliaScheduler.runGlobal(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run();
                return true;
            });
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class))).thenAnswer(invocation -> {
                deliveries.add(invocation.getArgument(2));
                return true;
            });
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(player), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            MilestonesGui gui = new MilestonesGui(plugin);
            gui.completed(new MilestoneCompletionEvent(PLAYER, "River", completion, Optional.empty()));
            gui.completed(new MilestoneCompletionEvent(PLAYER, "River", new MilestoneCompletion(completion.definition(), 1, 1, true), Optional.of(session)));
            assertTrue(deliveries.isEmpty());
            gui.completed(new MilestoneCompletionEvent(PLAYER, "River", completion, Optional.of(session)));
            deliveries.removeFirst().run();
            assertEquals(1, messages.size());
            assertTrue(messages.getFirst().contains("Milestone complete: Votes · 0.5"));
            gui.completed(new MilestoneCompletionEvent(PLAYER, "River", completion, Optional.of(session)));
            when(player.hasPermission("static.milestones")).thenReturn(false);
            deliveries.removeFirst().run();
            when(player.hasPermission("static.milestones")).thenReturn(true);
            when(stats.sessions(PLAYER)).thenReturn(List.of(new PlayerSession(UUID.randomUUID(), 2, 0, 2, 0, 0, 0, SessionEndReason.OPEN)));
            gui.completed(new MilestoneCompletionEvent(PLAYER, "River", completion, Optional.of(session)));
            deliveries.removeFirst().run();
            assertEquals(1, messages.size());
            gui.close();
        } finally {
            language.close();
        }
    }

    @Test
    void closedInventoryRejectsDelayedMilestoneLookup() {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        PresentationService presentation = mock(PresentationService.class);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        List<Runnable> requests = new ArrayList<>();
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getPresentationService()).thenReturn(presentation);
        when(player.getUniqueId()).thenReturn(PLAYER);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("static.milestones")).thenReturn(true);
        when(stats.find("Missing", StatsPeriod.LIFETIME)).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return null;
        }).when(plugin).executeAsync(any());
        try (MockedStatic<BukkitInventoryViews> views = mockStatic(BukkitInventoryViews.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            views.when(() -> BukkitInventoryViews.top(view)).thenReturn(inventory);
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(2).run();
                return true;
            });
            MilestonesGui gui = new MilestonesGui(plugin);
            gui.show(player, "Missing", 1);
            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getInventory()).thenReturn(inventory);
            gui.onClose(event);
            requests.getFirst().run();
            verifyNoInteractions(presentation);
        }
    }
}
