package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.privacy.VisibilityFixtures;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.detailed.DetailCategory;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailSettings;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.presentation.PresentationService;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.StatsPeriod;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DetailGuiTest {
    @TempDir
    Path directory;

    @Test
    void recordedViewPreservesDeselectedCountsAndExpandedViewIncludesSelectedZeros() {
        DetailSnapshot snapshot = snapshot(Map.of(DetailKey.MINED_DIAMOND_ORE, 3L));
        DetailSettings settings = new DetailSettings(false, Set.of(DetailKey.CAUGHT_ITEMS));

        assertThat(DetailGui.visibleKeys(snapshot, settings, null, true)).containsExactly(DetailKey.MINED_DIAMOND_ORE);
        assertThat(DetailGui.visibleKeys(snapshot, settings, null, false))
                .containsExactly(DetailKey.MINED_DIAMOND_ORE, DetailKey.CAUGHT_ITEMS);
        assertThat(DetailGui.visibleKeys(snapshot, settings, DetailCategory.FISH_CAUGHT, true)).isEmpty();
        assertThat(DetailGui.visibleKeys(snapshot, settings, DetailCategory.FISH_CAUGHT, false)).containsExactly(DetailKey.CAUGHT_ITEMS);
    }

    @Test
    void consoleShowsExactLongCountsAndRetainedLifetimeCoverage() {
        DetailSnapshot snapshot = snapshot(Map.of(DetailKey.MINED_DIAMOND_ORE, Long.MAX_VALUE));
        List<String> messages = console(snapshot, "all", 1, false);

        assertThat(messages).anyMatch(line -> line.contains("9,223,372,036,854,775,807"));
        assertThat(messages).anyMatch(line -> line.startsWith("First counted observation:"));
        assertThat(messages).anyMatch(line -> line.startsWith("Recorded totals · Updated"));
        assertThat(messages).contains("Detailed recording is disabled; saved counts remain.");
        assertThat(messages).contains("Recording is off for this counter; saved counts remain.");
        assertThat(messages).contains("Counts begin when observed; earlier activity is not recovered.");
    }

    @Test
    void consolePagesAreBoundedAndEmptyProfilesRenderWithStrictLanguageArguments() {
        Map<DetailKey, Long> counts = new EnumMap<>(DetailKey.class);
        for (DetailKey key : DetailKey.values()) {
            counts.put(key, 1L);
        }
        List<String> page = console(snapshot(counts), "all", 2, false);
        assertThat(page).hasSize(44);
        assertThat(page.getFirst()).contains("page 2/6");
        List<String> empty = console(snapshot(Map.of()), "blocks_mined", 1, false);
        assertThat(empty).contains("No counted observations in this view.");
    }

    @Test
    void remoteConsoleReceivesExactCountsBeforeAnyQueuedOperationRuns() {
        DetailSnapshot snapshot = snapshot(Map.of(DetailKey.MINED_DIAMOND_ORE, Long.MAX_VALUE));
        List<String> messages = console(snapshot, "all", 1, true);
        assertThat(messages).anyMatch(line -> line.contains("9,223,372,036,854,775,807"));
        assertThat(messages).anyMatch(line -> line.startsWith("First counted observation:"));
    }

    @Test
    void closingTheSourceInventoryCancelsPendingDetailDelivery() {
        pendingDelivery(true);
    }

    @Test
    void permissionRevocationBeforeDeliveryPreventsOpeningDetails() {
        pendingDelivery(false);
    }

    private void pendingDelivery(boolean close) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        PresentationService presentation = mock(PresentationService.class);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        DetailSnapshot snapshot = snapshot(Map.of());
        AtomicBoolean allowed = new AtomicBoolean(true);
        List<Runnable> work = new ArrayList<>();
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getPresentationService()).thenReturn(presentation);
        when(player.getUniqueId()).thenReturn(snapshot.uuid());
        when(player.getOpenInventory()).thenReturn(view);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("static.details")).thenAnswer(invocation -> allowed.get());
        when(stats.snapshot(snapshot.uuid(), StatsPeriod.LIFETIME)).thenReturn(Optional.of(profile(snapshot)));
        when(stats.details(snapshot.uuid())).thenReturn(Optional.of(snapshot));
        doAnswer(invocation -> {
            work.add(invocation.getArgument(0));
            return null;
        }).when(plugin).executeAsync(any());
        try (MockedStatic<BukkitInventoryViews> views = mockStatic(BukkitInventoryViews.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            views.when(() -> BukkitInventoryViews.top(view)).thenReturn(inventory);
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(2).run();
                return true;
            });
            DetailGui gui = new DetailGui(plugin);
            gui.show(player, "self", "all", 1);
            if (close) {
                InventoryCloseEvent event = mock(InventoryCloseEvent.class);
                when(event.getPlayer()).thenReturn(player);
                when(event.getInventory()).thenReturn(inventory);
                gui.onClose(event);
            } else {
                allowed.set(false);
            }
            work.getFirst().run();
            verify(player, never()).openInventory(any(Inventory.class));
            if (close) {
                verifyNoInteractions(presentation);
            } else {
                verify(presentation).command(eq(player), eq(StaticMessages.NO_PERMISSION), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
            }
        }
    }

    private List<String> console(DetailSnapshot snapshot, String category, int page, boolean remote) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        ConfigService config = mock(ConfigService.class);
        CommandSender sender = remote ? mock(RemoteConsoleCommandSender.class) : mock(CommandSender.class);
        StaticConfig source = new StaticConfig();
        source.details.enabled = false;
        source.details.selectedKeys.clear();
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getConfigService()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(config.runtime()).thenReturn(RuntimeConfig.from(source));
        when(sender.hasPermission("static.details")).thenReturn(true);
        when(stats.find("River", StatsPeriod.LIFETIME)).thenReturn(Optional.of(profile(snapshot)));
        when(stats.details(snapshot.uuid())).thenReturn(Optional.of(snapshot));
        List<Runnable> queued = new ArrayList<>();
        doAnswer(invocation -> {
            queued.add(invocation.getArgument(0));
            return null;
        }).when(plugin).executeAsync(any());
        LanguageService language = new LanguageService(directory.toFile(), plugin.getLogger());
        List<String> messages = new ArrayList<>();
        try (MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class)) {
            when(plugin.getLanguageService()).thenReturn(language);
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(sender), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            new DetailGui(plugin).show(sender, "River", category, page);
            if (remote) {
                assertThat(queued).isEmpty();
                verify(plugin, never()).executeAsync(any());
                assertThat(messages).isNotEmpty();
            } else {
                assertThat(messages).isEmpty();
                assertThat(queued).hasSize(1);
                queued.getFirst().run();
            }
        } finally {
            language.close();
        }
        return messages;
    }

    private PlayerStatsSnapshot profile(DetailSnapshot snapshot) {
        return new PlayerStatsSnapshot(snapshot.uuid(), snapshot.name(), Map.of(), 1, 1);
    }

    private DetailSnapshot snapshot(Map<DetailKey, Long> counts) {
        Map<DetailKey, Long> first = new EnumMap<>(DetailKey.class);
        Map<DetailKey, Long> last = new EnumMap<>(DetailKey.class);
        for (DetailKey key : counts.keySet()) {
            first.put(key, 1L);
            last.put(key, 2L);
        }
        return new DetailSnapshot(UUID.randomUUID(), "River", counts, first, last, 2);
    }
}
