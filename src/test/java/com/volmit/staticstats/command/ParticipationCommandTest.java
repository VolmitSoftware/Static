package com.volmit.staticstats.command;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.ParticipationMessages;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.participation.ParticipationSnapshot;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.presentation.PresentationService;
import com.volmit.staticstats.stats.StatsPeriod;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParticipationCommandTest {
    @TempDir
    Path directory;

    @Test
    void reportDistinguishesUnknownCohortsRetentionAndTheFullSessionMedianSample() {
        CommandSender sender = mock(CommandSender.class);
        try (Harness harness = new Harness(sender, snapshot(3, OptionalDouble.of(0.5)))) {
            harness.command.show(sender, "day", 0);
            assertThat(harness.messages).contains("Unique players with retained sessions: 3");
            assertThat(harness.messages).contains("First observed in window: 1 · Returning: 1 · Unknown history: 1");
            assertThat(harness.messages).contains("Quit sessions ending in window: 2 · Median eligible duration: 0.0005s");
            assertThat(harness.messages).contains("Median uses each retained quit session's full eligible duration.");
            assertThat(harness.messages).contains("Session retention can limit this window.");
            assertThat(harness.messages).contains("Participation counts connections, including time excluded from statistics.");
            assertThat(harness.messages).contains("Overlapping sessions · Open: 1 · Shutdown: 2 · Interrupted: 3");
            assertThat(harness.messages).anyMatch(line -> line.startsWith("Earliest retained observation in window:") && line.contains("UTC"));
        }
    }

    @Test
    void emptyHistoryDoesNotClaimZeroPlayersOrAZeroMedian() {
        CommandSender sender = mock(CommandSender.class);
        try (Harness harness = new Harness(sender, snapshot(0, OptionalDouble.empty()))) {
            harness.command.show(sender, "day", 0);
            assertThat(harness.messages).contains("No retained sessions overlap this window; this does not prove there were no players.");
            assertThat(harness.messages).noneMatch(line -> line.contains("Median eligible duration") || line.startsWith("Unique players")
                    || line.startsWith("Earliest retained observation"));
        }
    }

    @Test
    void observedConnectionsWithoutQuitSamplesExplainWhyNoMedianIsShown() {
        CommandSender sender = mock(CommandSender.class);
        try (Harness harness = new Harness(sender, snapshot(3, OptionalDouble.empty()))) {
            harness.command.show(sender, "day", 0);
            assertThat(harness.messages).contains("No retained completed quit sessions ended in this window.");
            assertThat(harness.messages).noneMatch(line -> line.contains("Median eligible duration:"));
        }
    }

    @Test
    void remoteConsoleWarmsThenRendersCachedDataWithoutAnAsyncTransportReply() {
        RemoteConsoleCommandSender sender = mock(RemoteConsoleCommandSender.class);
        ParticipationSnapshot snapshot = snapshot(3, OptionalDouble.of(1500));
        try (Harness harness = new Harness(sender, snapshot)) {
            when(harness.stats.cachedParticipation(StatsPeriod.DAY, 0)).thenReturn(Optional.empty(), Optional.of(snapshot));
            harness.command.show(sender, "day", 0);
            assertThat(harness.messages).hasSize(1);
            assertThat(harness.messages.getFirst()).contains("Participation summary is loading. Run this command again shortly.");
            harness.messages.clear();
            harness.command.show(sender, "day", 0);
            assertThat(harness.messages).contains("Quit sessions ending in window: 2 · Median eligible duration: 1.5s");
            verify(harness.stats, never()).participation(any(), eq(0));
            verify(harness.plugin, never()).executeAsync(any());
        }
    }

    @Test
    void stalePlayerRequestsDoNotOverwriteTheLatestSummary() {
        Player sender = player();
        ParticipationSnapshot snapshot = snapshot(3, OptionalDouble.empty());
        try (Harness harness = new Harness(sender, snapshot)) {
            CompletableFuture<ParticipationSnapshot> first = new CompletableFuture<>();
            CompletableFuture<ParticipationSnapshot> second = new CompletableFuture<>();
            when(harness.stats.participation(StatsPeriod.DAY, 0)).thenReturn(first, second);
            harness.command.show(sender, "day", 0);
            harness.command.show(sender, "day", 0);
            harness.messages.clear();
            second.complete(snapshot);
            int count = harness.messages.size();
            first.complete(snapshot(0, OptionalDouble.empty()));
            assertThat(harness.messages).hasSize(count).doesNotContain("No retained sessions overlap this window; this does not prove there were no players.");
        }
    }

    @Test
    void revokedPermissionAndDisconnectedPlayersCannotReceivePendingReports() {
        Player sender = player();
        ParticipationSnapshot snapshot = snapshot(3, OptionalDouble.empty());
        try (Harness harness = new Harness(sender, snapshot)) {
            CompletableFuture<ParticipationSnapshot> first = new CompletableFuture<>();
            CompletableFuture<ParticipationSnapshot> second = new CompletableFuture<>();
            when(harness.stats.participation(StatsPeriod.DAY, 0)).thenReturn(first, second);
            harness.command.show(sender, "day", 0);
            harness.messages.clear();
            when(sender.hasPermission("static.participation")).thenReturn(false);
            first.complete(snapshot);
            assertThat(harness.messages).isEmpty();
            verify(harness.presentation).command(eq(sender), eq(StaticMessages.NO_PERMISSION), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
            when(sender.hasPermission("static.participation")).thenReturn(true);
            harness.command.show(sender, "day", 0);
            harness.messages.clear();
            PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
            when(quit.getPlayer()).thenReturn(sender);
            harness.command.onQuit(quit);
            second.complete(snapshot);
            assertThat(harness.messages).isEmpty();
        }
    }

    @Test
    void invalidPeriodsAndOffsetsAreRejectedBeforeRequestingAReport() {
        CommandSender sender = mock(CommandSender.class);
        try (Harness harness = new Harness(sender, snapshot(0, OptionalDouble.empty()))) {
            harness.command.show(sender, "lifetime", 0);
            harness.command.show(sender, "session", 0);
            harness.command.show(sender, "day", -1);
            harness.command.show(sender, "day", 35);
            verify(harness.presentation, times(2)).command(eq(sender), eq(ParticipationMessages.UNKNOWN_PERIOD), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
            verify(harness.presentation, times(2)).command(eq(sender), eq(ParticipationMessages.INVALID_OFFSET), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
            verifyNoInteractions(harness.stats);
        }
    }

    @Test
    void retentionChangeDuringRequestReportsTheUpdatedOffsetBound() {
        CommandSender sender = mock(CommandSender.class);
        try (Harness harness = new Harness(sender, snapshot(0, OptionalDouble.empty()))) {
            StaticConfig reduced = new StaticConfig();
            reduced.history.retainedDays = 2;
            when(harness.config.runtime()).thenReturn(RuntimeConfig.from(new StaticConfig()), RuntimeConfig.from(reduced));
            when(harness.stats.participation(StatsPeriod.DAY, 5)).thenThrow(new IllegalArgumentException("Offset outside retention"));
            harness.command.show(sender, "day", 5);
            verify(harness.presentation).command(eq(sender), eq(ParticipationMessages.INVALID_OFFSET), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
            verify(harness.presentation, never()).command(eq(sender), eq(ParticipationMessages.FAILED), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
        }
    }

    @Test
    void retentionChangeWhileFutureIsPendingUsesValidationFeedbackInsteadOfARefreshFailure() {
        CommandSender sender = mock(CommandSender.class);
        try (Harness harness = new Harness(sender, snapshot(0, OptionalDouble.empty()))) {
            CompletableFuture<ParticipationSnapshot> future = new CompletableFuture<>();
            when(harness.stats.participation(StatsPeriod.DAY, 5)).thenReturn(future);
            harness.command.show(sender, "day", 5);
            harness.messages.clear();
            StaticConfig reduced = new StaticConfig();
            reduced.history.retainedDays = 2;
            when(harness.config.runtime()).thenReturn(RuntimeConfig.from(reduced));
            future.completeExceptionally(new IllegalArgumentException("Offset outside retention"));
            verify(harness.presentation).command(eq(sender), eq(ParticipationMessages.INVALID_OFFSET), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
            verify(harness.presentation, never()).command(eq(sender), eq(ParticipationMessages.FAILED), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
            assertThat(harness.messages).isEmpty();
        }
    }

    @Test
    void futureResponsesRouteBlockAndMinecartSendersToTheirOwningContexts() {
        StaticPlugin plugin = mock(StaticPlugin.class);
        StatsService stats = mock(StatsService.class);
        LanguageService language = mock(LanguageService.class);
        ConfigService config = mock(ConfigService.class);
        PresentationService presentation = mock(PresentationService.class);
        BlockCommandSender blockSender = mock(BlockCommandSender.class);
        CommandMinecart minecart = mock(CommandMinecart.class);
        Block block = mock(Block.class);
        Location location = new Location(null, 1, 2, 3);
        CompletableFuture<ParticipationSnapshot> blockSummary = new CompletableFuture<>();
        CompletableFuture<ParticipationSnapshot> entitySummary = new CompletableFuture<>();
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getLanguageService()).thenReturn(language);
        when(plugin.getConfigService()).thenReturn(config);
        when(config.runtime()).thenReturn(RuntimeConfig.from(new StaticConfig()));
        when(plugin.getPresentationService()).thenReturn(presentation);
        when(blockSender.getBlock()).thenReturn(block);
        when(block.getLocation()).thenReturn(location);
        when(blockSender.hasPermission("static.participation")).thenReturn(true);
        when(minecart.hasPermission("static.participation")).thenReturn(true);
        when(stats.participation(StatsPeriod.DAY, 0)).thenReturn(blockSummary, entitySummary);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            ParticipationCommand command = new ParticipationCommand(plugin);
            command.show(blockSender, "day", 0);
            command.show(minecart, "day", 0);
            blockSummary.completeExceptionally(new IllegalStateException("unavailable"));
            entitySummary.completeExceptionally(new IllegalStateException("unavailable"));
            scheduler.verify(() -> FoliaScheduler.runRegion(eq(plugin), eq(location), any(Runnable.class)));
            scheduler.verify(() -> FoliaScheduler.runEntity(eq(plugin), eq(minecart), any(Runnable.class)));
            verifyNoInteractions(presentation);
            verify(block).getLocation();
        }
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private ParticipationSnapshot snapshot(int players, OptionalDouble median) {
        ParticipationSnapshot snapshot = mock(ParticipationSnapshot.class);
        when(snapshot.window()).thenReturn(StatsPeriod.DAY.window(1_000_000, ZoneId.of("UTC")));
        when(snapshot.historyZone()).thenReturn(ZoneId.of("UTC"));
        when(snapshot.refreshedAt()).thenReturn(1_000_000L);
        when(snapshot.uniquePlayers()).thenReturn(players);
        when(snapshot.newPlayers()).thenReturn(1);
        when(snapshot.returningPlayers()).thenReturn(1);
        when(snapshot.unknownCohortPlayers()).thenReturn(1);
        when(snapshot.medianSessionMillis()).thenReturn(median);
        when(snapshot.completedSessions()).thenReturn(median.isPresent() ? 2 : 0);
        when(snapshot.openSessions()).thenReturn(1);
        when(snapshot.shutdownSessions()).thenReturn(2);
        when(snapshot.interruptedSessions()).thenReturn(3);
        when(snapshot.earliestObservation()).thenReturn(players == 0 ? 0 : 1L);
        when(snapshot.retentionLimited()).thenReturn(true);
        return snapshot;
    }

    private final class Harness implements AutoCloseable {
        private final StaticPlugin plugin = mock(StaticPlugin.class);
        private final StatsService stats = mock(StatsService.class);
        private final ConfigService config = mock(ConfigService.class);
        private final PresentationService presentation = mock(PresentationService.class);
        private final LanguageService language = new LanguageService(directory.toFile(), Logger.getLogger(getClass().getName()));
        private final List<String> messages = new ArrayList<>();
        private final MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class);
        private final MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
        private final ParticipationCommand command;

        private Harness(CommandSender sender, ParticipationSnapshot snapshot) {
            when(plugin.getStatsService()).thenReturn(stats);
            when(plugin.getConfigService()).thenReturn(config);
            when(config.runtime()).thenReturn(RuntimeConfig.from(new StaticConfig()));
            when(plugin.getPresentationService()).thenReturn(presentation);
            when(plugin.getLanguageService()).thenReturn(language);
            when(sender.hasPermission("static.participation")).thenReturn(true);
            when(stats.participation(StatsPeriod.DAY, 0)).thenReturn(CompletableFuture.completedFuture(snapshot));
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(sender), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), any(Entity.class), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(2).run();
                return true;
            });
            command = new ParticipationCommand(plugin);
        }

        @Override
        public void close() {
            scheduler.close();
            messenger.close();
            language.close();
        }
    }
}
