package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.privacy.VisibilityFixtures;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.presentation.PresentationService;
import com.volmit.staticstats.records.PersonalBest;
import com.volmit.staticstats.records.PersonalBestPeriod;
import com.volmit.staticstats.records.PersonalBestSnapshot;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.StatKey;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

class BestsGuiTest {
    @TempDir
    Path directory;

    @Test
    void calendarRecordsShowSourceCoverageRecoveryAndActualValues() {
        PersonalBest best = new PersonalBest(PersonalBestPeriod.DAY, StatKey.BLOCKS_BROKEN, 42,
                1000, 10000, 2000, 5000, true, true);
        List<String> messages = console(snapshot(List.of(best), 3000), "day", 1, false);

        assertThat(messages).anyMatch(line -> line.contains("Blocks broken") && line.contains("42"));
        assertThat(messages).contains("Partial coverage: recording began during this window.");
        assertThat(messages).contains("Recovered from available history when record tracking began.");
        assertThat(messages).contains("The source window or session is ongoing; this best may increase.");
        assertThat(messages).contains("Earlier bests use retained history only; older activity may be missing.");
        assertThat(messages).contains("Saved bests remain when source history is pruned.");
        assertThat(messages).anyMatch(line -> line.startsWith("Personal-best tracking began:") && line.contains("UTC"));
        assertThat(messages).anyMatch(line -> line.startsWith("Record value observed:") && line.contains("UTC"));
    }

    @Test
    void openSessionShowsTheWinningObservationWithoutClaimingTheLatestSessionCheckpoint() {
        PersonalBest best = new PersonalBest(PersonalBestPeriod.SESSION, StatKey.ACTIVE_TIME, 61000,
                1000, 0, 1000, 62000, true, false);
        List<String> messages = console(snapshot(List.of(best), 1000), "session", 1, false);

        assertThat(messages).anyMatch(line -> line.startsWith("Session started:"));
        assertThat(messages).noneMatch(line -> line.startsWith("Last recorded session time:"));
        assertThat(messages).anyMatch(line -> line.startsWith("Record value observed:"));
        assertThat(messages).contains("Session records cover recorded online, active, and AFK durations.");
        assertThat(messages).contains("Activity uses detected game input, not proof of attention.");
        assertThat(messages).doesNotContain("The source window or session is closed.");
        assertThat(messages).noneMatch(line -> line.contains("Recovered from"));
    }

    @Test
    void closedSessionDistinguishesFinalCheckpointFromTheEarlierWinningObservation() {
        PersonalBest best = new PersonalBest(PersonalBestPeriod.SESSION, StatKey.ACTIVE_TIME, 61000,
                1000, 302000, 1000, 62000, false, false);
        List<String> messages = console(snapshot(List.of(best), 1000), "session", 1, false);

        assertThat(messages).anyMatch(line -> line.startsWith("Last recorded session time:") && line.contains("12:05:02"));
        assertThat(messages).anyMatch(line -> line.startsWith("Record value observed:") && line.contains("12:01:02"));
        assertThat(messages).contains("The source window or session is closed.");
    }

    @Test
    void emptyRecordsKeepUnknownTrackingHonestAndStrictMessageArgumentsValid() {
        List<String> messages = console(snapshot(List.of(), 0), "month", 1, false);
        assertThat(messages).contains("Personal-best tracking has not recorded an observation yet.");
        assertThat(messages).contains("No positive personal bests have been recorded for this scope.");
        assertThat(messages).hasSize(7);
    }

    @Test
    void consolePagesContainAtMostTenRecordsAndFilterOtherScopes() {
        List<PersonalBest> records = new ArrayList<>();
        for (StatKey key : StatKey.values()) {
            if (PersonalBestPeriod.DAY.supports(key)) {
                records.add(new PersonalBest(PersonalBestPeriod.DAY, key, 1, 1000, 10000, 1000, 5000, false, false));
            }
        }
        records.add(new PersonalBest(PersonalBestPeriod.SESSION, StatKey.ONLINE_TIME, 1000, 1000, 2000, 1000, 2000, false, false));
        PersonalBestSnapshot snapshot = snapshot(records, 1000);
        assertThat(BestsGui.records(snapshot, PersonalBestPeriod.SESSION)).hasSize(1);
        List<String> messages = console(snapshot, "day", 2, false);
        assertThat(messages.getFirst()).contains("page 2/4");
        assertThat(messages.stream().filter(line -> line.startsWith("Record value observed:"))).hasSize(10);
    }

    @Test
    void remoteConsoleReceivesItsReplyBeforeQueuedOperationsRun() {
        List<String> messages = console(snapshot(List.of(new PersonalBest(PersonalBestPeriod.WEEK,
                StatKey.BLOCKS_PLACED, 2345, 1000, 10000, 1000, 5000, false, false)), 1000), "week", 1, true);
        assertThat(messages).anyMatch(line -> line.contains("2,345"));
        assertThat(messages).contains("The source window or session is closed.");
    }

    @Test
    void closingTheSourceInventoryCancelsPendingDelivery() {
        pendingDelivery(true, false);
    }

    @Test
    void permissionRevocationBeforeDeliveryPreventsOpening() {
        pendingDelivery(false, false);
    }

    @Test
    void OtherPlayerRecordsRequireTheOthersPermissionAtDelivery() {
        pendingDelivery(false, true);
    }

    private void pendingDelivery(boolean close, boolean otherPlayer) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        PresentationService presentation = mock(PresentationService.class);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        PersonalBestSnapshot snapshot = snapshot(List.of(), 0);
        AtomicBoolean allowed = new AtomicBoolean(true);
        List<Runnable> work = new ArrayList<>();
        UUID viewer = otherPlayer ? UUID.randomUUID() : snapshot.uuid();
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getPresentationService()).thenReturn(presentation);
        when(player.getUniqueId()).thenReturn(viewer);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("static.bests")).thenAnswer(invocation -> allowed.get());
        when(stats.snapshot(viewer, StatsPeriod.LIFETIME)).thenReturn(Optional.of(profile(snapshot)));
        when(stats.find("River", StatsPeriod.LIFETIME)).thenReturn(Optional.of(profile(snapshot)));
        when(stats.personalBests(snapshot.uuid())).thenReturn(Optional.of(snapshot));
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
            BestsGui gui = new BestsGui(plugin);
            gui.show(player, otherPlayer ? "River" : "self", "day", 1);
            if (close) {
                InventoryCloseEvent event = mock(InventoryCloseEvent.class);
                when(event.getPlayer()).thenReturn(player);
                when(event.getInventory()).thenReturn(inventory);
                gui.onClose(event);
            } else if (!otherPlayer) {
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

    private List<String> console(PersonalBestSnapshot snapshot, String period, int page, boolean remote) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        CommandSender sender = remote ? mock(RemoteConsoleCommandSender.class) : mock(CommandSender.class);
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(sender.hasPermission("static.bests")).thenReturn(true);
        when(stats.find("River", StatsPeriod.LIFETIME)).thenReturn(Optional.of(profile(snapshot)));
        when(stats.personalBests(snapshot.uuid())).thenReturn(Optional.of(snapshot));
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
            new BestsGui(plugin).show(sender, "River", period, page);
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

    private PlayerStatsSnapshot profile(PersonalBestSnapshot snapshot) {
        return new PlayerStatsSnapshot(snapshot.uuid(), snapshot.name(), Map.of(), 1, 1);
    }

    private PersonalBestSnapshot snapshot(List<PersonalBest> records, long trackingSince) {
        return new PersonalBestSnapshot(UUID.randomUUID(), "River", ZoneId.of("UTC"), trackingSince, records);
    }
}
