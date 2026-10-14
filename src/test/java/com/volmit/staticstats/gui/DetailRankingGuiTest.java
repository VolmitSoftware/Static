package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.privacy.VisibilityFixtures;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.localization.DetailMessages;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.localization.StatsMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.presentation.PresentationService;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

class DetailRankingGuiTest {
    @TempDir
    Path directory;

    @Test
    void personalRankGapAndNearbyRowsPreserveLongPrecisionBeyondDoubleRange() {
        UUID viewer = UUID.randomUUID();
        Player sender = player(viewer);
        DetailRankingSnapshot ranking = ranking(List.of(profile(UUID.randomUUID(), "Leader", Long.MAX_VALUE),
                profile(UUID.randomUUID(), "Second", 9_007_199_254_740_994L),
                profile(viewer, "River", 9_007_199_254_740_993L)));
        try (Harness harness = new Harness(sender, ranking)) {
            harness.source.tracking.leaderboardLimit = 1;
            harness.configuration();
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 1);

            assertThat(harness.messages).contains("#1 Leader: 9,223,372,036,854,775,807");
            assertThat(harness.messages).contains("Your rank: #3 of 3 players | 9,007,199,254,740,993");
            assertThat(harness.messages).contains("Gap to the next higher score: 1");
            assertThat(harness.messages).contains("Players near your position");
            assertThat(harness.messages).contains("#3 River: 9,007,199,254,740,993");
            assertThat(harness.messages).anyMatch(line -> line.startsWith("Earliest observation among ranked profiles:"));
        }
    }

    @Test
    void tiedLeaderUsesStrictMessageArgumentsAndMasterPauseKeepsSavedCountsVisible() {
        UUID viewer = UUID.randomUUID();
        Player sender = player(viewer);
        try (Harness harness = new Harness(sender, ranking(List.of(profile(viewer, "River", 7),
                profile(UUID.randomUUID(), "Aster", 7))))) {
            harness.source.general.enabled = false;
            harness.configuration();
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 1);

            assertThat(harness.messages).contains("You share or hold the highest score.");
            assertThat(harness.messages).contains("Recording is off for this counter; saved counts remain.");
            assertThat(harness.messages).contains("Lifetime detailed counts · 2/2 profiles with positive observations");
            assertThat(harness.messages).noneMatch(line -> line.startsWith("Gap to"));
        }
    }

    @Test
    void emptyBoardExplainsUnrankedZeroCountsAndDoesNotInventACoverageDate() {
        Player sender = player(UUID.randomUUID());
        try (Harness harness = new Harness(sender, ranking(List.of(profile(sender.getUniqueId(), "River", 0))))) {
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 1);
            assertThat(harness.messages).contains("Lifetime detailed counts · 0/1 profiles with positive observations");
            assertThat(harness.messages).contains("No positive recorded counts for this counter yet.");
            assertThat(harness.messages).contains("You have no positive recorded count for this counter in this snapshot.");
            assertThat(harness.messages).noneMatch(line -> line.startsWith("Earliest observation"));
        }
    }

    @Test
    void remoteConsoleWarmsWithoutWaitingThenReceivesCachedExactCountsSynchronously() {
        RemoteConsoleCommandSender sender = mock(RemoteConsoleCommandSender.class);
        DetailRankingSnapshot ranking = ranking(List.of(profile(UUID.randomUUID(), "River", Long.MAX_VALUE)));
        try (Harness harness = new Harness(sender, ranking)) {
            when(harness.stats.cachedDetailRanking(DetailKey.MINED_DIAMOND_ORE))
                    .thenReturn(Optional.empty(), Optional.of(ranking));
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 1);
            assertThat(harness.messages).hasSize(1);
            assertThat(harness.messages.getFirst()).contains("Detailed rankings are loading. Run this command again shortly.");
            harness.messages.clear();
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 1);
            assertThat(harness.messages).contains("#1 River: 9,223,372,036,854,775,807");
            verify(harness.stats, never()).detailRanking(any());
            verify(harness.plugin, never()).executeAsync(any());
        }
    }

    @Test
    void latestPlayerRequestWinsWhenAnOlderFutureCompletesLast() {
        Player sender = player(UUID.randomUUID());
        DetailRankingSnapshot older = ranking(List.of(profile(sender.getUniqueId(), "River", 1)));
        DetailRankingSnapshot newer = ranking(List.of(profile(sender.getUniqueId(), "River", 2)));
        try (Harness harness = new Harness(sender, older)) {
            CompletableFuture<DetailRankingSnapshot> first = new CompletableFuture<>();
            CompletableFuture<DetailRankingSnapshot> second = new CompletableFuture<>();
            when(harness.stats.detailRanking(DetailKey.MINED_DIAMOND_ORE)).thenReturn(first, second);
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 1);
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 1);
            harness.messages.clear();
            second.complete(newer);
            int delivered = harness.messages.size();
            first.complete(older);
            assertThat(harness.messages).hasSize(delivered).contains("#1 River: 2").doesNotContain("#1 River: 1");
        }
    }

    @Test
    void permissionRevocationBeforeCompletionPreventsRankingDisclosure() {
        Player sender = player(UUID.randomUUID());
        DetailRankingSnapshot ranking = ranking(List.of(profile(sender.getUniqueId(), "River", 1)));
        try (Harness harness = new Harness(sender, ranking)) {
            CompletableFuture<DetailRankingSnapshot> future = new CompletableFuture<>();
            when(harness.stats.detailRanking(DetailKey.MINED_DIAMOND_ORE)).thenReturn(future);
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 1);
            harness.messages.clear();
            when(sender.hasPermission("static.detailtop")).thenReturn(false);
            future.complete(ranking);
            assertThat(harness.messages).isEmpty();
            verify(harness.presentation).command(eq(sender), eq(StaticMessages.NO_PERMISSION), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
        }
    }

    @Test
    void invalidCounterAndOutOfRangePagesReturnTheCorrectLocalizedErrors() {
        CommandSender sender = mock(CommandSender.class);
        try (Harness harness = new Harness(sender, ranking(List.of()))) {
            harness.gui.top(sender, "not_a_counter", 1);
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 0);
            harness.gui.top(sender, DetailKey.MINED_DIAMOND_ORE.id(), 2);
            verify(harness.presentation).command(eq(sender), eq(DetailMessages.UNKNOWN_COUNTER), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
            verify(harness.presentation, times(2)).command(eq(sender), eq(StatsMessages.INVALID_PAGE), any(MessageArgs.class), eq(FeedbackTone.FAILURE));
            assertThat(harness.messages).isEmpty();
        }
    }

    @Test
    void failedAsyncRankingsRouteWorldBoundSendersBackToTheirOwners() {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        LanguageService language = mock(LanguageService.class);
        PresentationService presentation = mock(PresentationService.class);
        BlockCommandSender blockSender = mock(BlockCommandSender.class);
        CommandMinecart minecart = mock(CommandMinecart.class);
        Block block = mock(Block.class);
        Location location = new Location(null, 1, 2, 3);
        CompletableFuture<DetailRankingSnapshot> blockRanking = new CompletableFuture<>();
        CompletableFuture<DetailRankingSnapshot> entityRanking = new CompletableFuture<>();
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getLanguageService()).thenReturn(language);
        when(plugin.getPresentationService()).thenReturn(presentation);
        when(blockSender.getBlock()).thenReturn(block);
        when(block.getLocation()).thenReturn(location);
        when(blockSender.hasPermission("static.detailtop")).thenReturn(true);
        when(minecart.hasPermission("static.detailtop")).thenReturn(true);
        when(stats.detailRanking(DetailKey.MINED_DIAMOND_ORE)).thenReturn(blockRanking, entityRanking);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            DetailGui gui = new DetailGui(plugin);
            gui.top(blockSender, DetailKey.MINED_DIAMOND_ORE.id(), 1);
            gui.top(minecart, DetailKey.MINED_DIAMOND_ORE.id(), 1);
            blockRanking.completeExceptionally(new IllegalStateException("unavailable"));
            entityRanking.completeExceptionally(new IllegalStateException("unavailable"));
            scheduler.verify(() -> FoliaScheduler.runRegion(eq(plugin), eq(location), any(Runnable.class)));
            scheduler.verify(() -> FoliaScheduler.runEntity(eq(plugin), eq(minecart), any(Runnable.class)));
            verifyNoInteractions(presentation);
            verify(block).getLocation();
        }
    }

    private Player player(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private DetailSnapshot profile(UUID uuid, String name, long value) {
        return new DetailSnapshot(uuid, name, value > 0 ? Map.of(DetailKey.MINED_DIAMOND_ORE, value) : Map.of(),
                value > 0 ? Map.of(DetailKey.MINED_DIAMOND_ORE, 1L) : Map.of(),
                value > 0 ? Map.of(DetailKey.MINED_DIAMOND_ORE, 2L) : Map.of(), 2);
    }

    private DetailRankingSnapshot ranking(List<DetailSnapshot> profiles) {
        return DetailRankingSnapshot.create(DetailKey.MINED_DIAMOND_ORE, 2, profiles);
    }

    private final class Harness implements AutoCloseable {
        private final StaticPlugin plugin = VisibilityFixtures.plugin();
        private final StatsService stats = mock(StatsService.class);
        private final ConfigService config = mock(ConfigService.class);
        private final PresentationService presentation = mock(PresentationService.class);
        private final StaticConfig source = new StaticConfig();
        private final LanguageService language = new LanguageService(directory.toFile(), Logger.getLogger(getClass().getName()));
        private final List<String> messages = new ArrayList<>();
        private final MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class);
        private final MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
        private final DetailGui gui;

        private Harness(CommandSender sender, DetailRankingSnapshot ranking) {
            when(plugin.getStatsService()).thenReturn(stats);
            when(plugin.getConfigService()).thenReturn(config);
            when(plugin.getPresentationService()).thenReturn(presentation);
            when(plugin.getLanguageService()).thenReturn(language);
            when(sender.hasPermission("static.detailtop")).thenReturn(true);
            when(stats.detailRanking(DetailKey.MINED_DIAMOND_ORE)).thenReturn(CompletableFuture.completedFuture(ranking));
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(sender), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), any(Entity.class), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(2).run();
                return true;
            });
            configuration();
            gui = new DetailGui(plugin);
        }

        @Override
        public void close() {
            scheduler.close();
            messenger.close();
            language.close();
        }

        private void configuration() {
            when(config.runtime()).thenReturn(RuntimeConfig.from(source));
        }
    }
}
