package com.volmit.staticstats.integration;

import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.privacy.VisibilityFixtures;
import com.volmit.staticstats.privacy.ProfileVisibility;
import com.volmit.staticstats.privacy.Visibility;
import com.volmit.staticstats.stats.RankingRules;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.RankingQualification;
import com.volmit.staticstats.stats.RankedPlayer;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.util.Map;
import java.util.List;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StaticExpansionTest {
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    @TempDir
    Path directory;
    private LanguageService language;

    @AfterEach
    void closeLanguage() {
        if (language != null) {
            language.close();
        }
    }

    @Test
    void exposesOfflineRawValuesWithoutGroupingOrScientificNotation() throws IOException {
        PlayerStatsSnapshot snapshot = new PlayerStatsSnapshot(PLAYER, "River", Map.of(
                StatKey.BLOCKS_BROKEN, 1_234D,
                StatKey.DISTANCE_TRAVELED, 1250.5,
                StatKey.ONLINE_TIME, 90_000_000_000D), 0, 0);
        StatsService statistics = service(Optional.of(snapshot));
        StaticExpansion expansion = expansion(statistics);
        OfflinePlayer player = player();
        assertEquals("1234", expansion.onRequest(player, "blocks_broken"));
        assertEquals("1250.5", expansion.onRequest(player, "DISTANCE_TRAVELED"));
        assertEquals("90000000000", expansion.onRequest(player, "online_time"));
        verify(player, never()).getPlayer();
        verify(player, never()).isOnline();
    }

    @Test
    void formatsDerivedAccuracyDistanceAndSessionTime() throws IOException {
        PlayerStatsSnapshot snapshot = new PlayerStatsSnapshot(PLAYER, "River", Map.of(
                StatKey.ARROWS_FIRED, 10D,
                StatKey.ARROW_HITS_DEALT, 8D,
                StatKey.DISTANCE_TRAVELED, 1250D,
                StatKey.ONLINE_TIME, 3_665_000D), 0, 0);
        StaticExpansion expansion = expansion(service(Optional.of(snapshot)));
        OfflinePlayer player = player();
        assertEquals("0.8", expansion.onRequest(player, "arrow_accuracy"));
        assertEquals("80.0%", expansion.onRequest(player, "ARROW_ACCURACY_FORMATTED"));
        assertEquals("1.25 km", expansion.onRequest(player, "distance_traveled_formatted"));
        assertEquals("1h 1m", expansion.onRequest(player, "online_time_formatted"));
        assertEquals("2", expansion.onRequest(player, "arrows_missed"));
    }

    @Test
    void missingProfilesHaveZeroValuesButUnknownIdentifiersRemainUnresolved() throws IOException {
        StaticExpansion expansion = expansion(service(Optional.empty()));
        OfflinePlayer player = player();
        assertEquals("0", expansion.onRequest(player, "blocks_broken"));
        assertEquals("0", expansion.onRequest(player, "kill_death_ratio"));
        assertEquals("0.0%", expansion.onRequest(player, "arrow_accuracy_formatted"));
        assertEquals("0s", expansion.onRequest(player, "online_time_formatted"));
        assertNull(expansion.onRequest(player, "unknown_statistic"));
        assertNull(expansion.onRequest(player, "unknown_statistic_formatted"));
    }

    @Test
    void nullRequestContextDoesNotTouchStatistics() throws IOException {
        StatsService statistics = service(Optional.empty());
        StaticExpansion expansion = expansion(statistics);
        assertNull(expansion.onRequest(null, "blocks_broken"));
        assertNull(expansion.onRequest(player(), null));
        verifyNoInteractions(statistics);
    }

    @Test
    void readsRanksAndTopRowsOnlyFromThePublishedCache() throws IOException {
        PlayerStatsSnapshot profile = new PlayerStatsSnapshot(PLAYER, "River", Map.of(StatKey.BLOCKS_BROKEN, 1234D), 1000, 500);
        StatsService statistics = service(Optional.of(profile));
        RankingSnapshot ranking = mock(RankingSnapshot.class);
        RankedPlayer entry = new RankedPlayer(profile, 1, 1, 1234, 0);
        when(ranking.entries()).thenReturn(List.of(entry));
        when(ranking.find(PLAYER)).thenReturn(Optional.of(entry));
        when(ranking.refreshedAt()).thenReturn(2000L);
        when(statistics.cachedRanking(StatKey.BLOCKS_BROKEN, StatsPeriod.LIFETIME)).thenReturn(Optional.of(ranking));
        StaticExpansion expansion = expansion(statistics);
        assertEquals("River", expansion.onRequest(null, "top_blocks_broken_1_name"));
        assertEquals("1234", expansion.onRequest(null, "top_blocks_broken_1_value"));
        assertEquals("1", expansion.onRequest(player(), "rank_blocks_broken"));
        assertEquals("1", expansion.onRequest(player(), "position_blocks_broken"));
        assertEquals("0", expansion.onRequest(player(), "gap_blocks_broken"));
        assertEquals("true", expansion.onRequest(null, "ranking_blocks_broken_ready"));
        assertEquals("2000", expansion.onRequest(null, "ranking_blocks_broken_updated"));
        assertEquals("1", expansion.onRequest(null, "ranking_blocks_broken_size"));
        assertEquals("", expansion.onRequest(player(), "top_blocks_broken_2_name"));
        assertNull(expansion.onRequest(player(), "top_blocks_broken_0_name"));
        verify(statistics, never()).snapshot(PLAYER, StatsPeriod.LIFETIME);
        verify(statistics, never()).ranking(StatKey.BLOCKS_BROKEN);
        verify(statistics, never()).leaderboard(StatKey.BLOCKS_BROKEN, 0, 1);
    }

    @Test
    void distinguishesWarmingRankingsAndUnknownCoverageFromZeroScores() throws IOException {
        StatsService statistics = service(Optional.empty());
        when(statistics.cachedRanking(StatKey.VOTES, StatsPeriod.LIFETIME)).thenReturn(Optional.empty());
        StaticExpansion expansion = expansion(statistics);
        assertEquals("", expansion.onRequest(player(), "rank_votes"));
        assertEquals("", expansion.onRequest(null, "top_votes_1_value"));
        assertEquals("false", expansion.onRequest(null, "ranking_votes_ready"));
        assertEquals("0", expansion.onRequest(null, "ranking_votes_updated"));
        assertEquals("0", expansion.onRequest(player(), "recorded_since"));
        assertEquals("Unknown", expansion.onRequest(player(), "recorded_since_formatted"));
        assertNull(expansion.onRequest(player(), "rank_missing_stat"));
        assertNull(expansion.onRequest(null, "ranking_missing_stat_ready"));
    }

    @Test
    void qualificationUsesTheRankingSnapshotWithoutChangingRawProfileValues() throws IOException {
        PlayerStatsSnapshot profile = new PlayerStatsSnapshot(PLAYER, "River",
                Map.of(StatKey.ARROWS_FIRED, 20D, StatKey.ARROW_HITS_DEALT, 18D), 1000, 500);
        StatsService statistics = service(Optional.of(profile));
        RankingSnapshot ranking = mock(RankingSnapshot.class);
        when(ranking.qualification(PLAYER)).thenReturn(Optional.of(new RankingQualification(
                StatKey.ARROW_HITS_DEALT, 18, StatKey.ARROWS_FIRED, 20, 50, false)));
        when(statistics.cachedRanking(StatKey.ARROW_ACCURACY, StatsPeriod.LIFETIME)).thenReturn(Optional.of(ranking));
        StaticExpansion expansion = expansion(statistics);
        assertEquals("false", expansion.onRequest(player(), "qualification_arrow_accuracy_qualified"));
        assertEquals("18", expansion.onRequest(player(), "qualification_arrow_accuracy_numerator"));
        assertEquals("20", expansion.onRequest(player(), "qualification_arrow_accuracy_denominator"));
        assertEquals("50", expansion.onRequest(player(), "qualification_arrow_accuracy_minimum"));
        assertEquals("30", expansion.onRequest(player(), "qualification_arrow_accuracy_remaining"));
        assertEquals("", expansion.onRequest(player(), "rank_arrow_accuracy"));
        verify(statistics, never()).snapshot(PLAYER, StatsPeriod.LIFETIME);
        verify(statistics, never()).ranking(StatKey.ARROW_ACCURACY);
        assertEquals("0.9", expansion.onRequest(player(), "arrow_accuracy"));
        assertEquals("", expansion.onRequest(player(), "qualification_votes_qualified"));
        assertNull(expansion.onRequest(player(), "qualification_missing_qualified"));
    }

    private static StatsService service(Optional<PlayerStatsSnapshot> snapshot) {
        StatsService statistics = mock(StatsService.class);
        when(statistics.snapshot(PLAYER, StatsPeriod.LIFETIME)).thenReturn(snapshot);
        return statistics;
    }

    @Test
    void periodPrefixesSelectIndependentCountersAndPublishedRankings() throws IOException {
        StatsService statistics = service(Optional.of(new PlayerStatsSnapshot(PLAYER, "River",
                Map.of(StatKey.VOTES, 90D), 1000, 500)));
        PlayerStatsSnapshot today = new PlayerStatsSnapshot(PLAYER, "River", Map.of(StatKey.VOTES, 2D), 1000, 800);
        when(statistics.snapshot(PLAYER, StatsPeriod.DAY)).thenReturn(Optional.of(today));
        RankingSnapshot ranking = mock(RankingSnapshot.class);
        when(ranking.entries()).thenReturn(List.of(new RankedPlayer(today, 1, 1, 2, 0)));
        when(statistics.cachedRanking(StatKey.VOTES, StatsPeriod.DAY)).thenReturn(Optional.of(ranking));
        StaticExpansion expansion = expansion(statistics);
        assertEquals("90", expansion.onRequest(player(), "votes"));
        assertEquals("90", expansion.onRequest(player(), "lifetime_votes"));
        assertEquals("2", expansion.onRequest(player(), "day_votes"));
        assertEquals("800", expansion.onRequest(player(), "day_recorded_since"));
        assertEquals("2", expansion.onRequest(null, "day_top_votes_1_value"));
        assertEquals("true", expansion.onRequest(null, "day_ranking_votes_ready"));
        assertEquals("false", expansion.onRequest(null, "week_ranking_votes_ready"));
        assertEquals("0", expansion.onRequest(player(), "month_votes"));
        assertNull(expansion.onRequest(player(), "day_week_votes"));
        verify(statistics, never()).ranking(StatKey.VOTES, StatsPeriod.DAY);
    }

    @Test
    void privateSubjectsAndLeaderboardMetadataStayHiddenWithoutATrustedViewer() throws Exception {
        StatsService statistics = service(Optional.of(new PlayerStatsSnapshot(PLAYER, "Hidden", Map.of(StatKey.VOTES, 100D), 10, 1)));
        UUID visible = UUID.randomUUID();
        RankingSnapshot board = RankingSnapshot.create(StatKey.VOTES, 10,
                List.of(new PlayerStatsSnapshot(PLAYER, "Hidden", Map.of(StatKey.VOTES, 100D), 10, 1),
                        new PlayerStatsSnapshot(visible, "Visible", Map.of(StatKey.VOTES, 20D), 10, 1)),
                RankingRules.defaults(), StatsPeriod.LIFETIME.window(10, ZoneOffset.UTC), ZoneOffset.UTC);
        when(statistics.cachedRanking(StatKey.VOTES, StatsPeriod.LIFETIME)).thenReturn(Optional.of(board));
        StaticPlugin plugin = VisibilityFixtures.plugin();
        when(plugin.getStatsService()).thenReturn(statistics);
        try (ProfileVisibility visibility = new ProfileVisibility(directory.resolve("visibility.json"), Logger.getAnonymousLogger())) {
            visibility.ready().get(5, TimeUnit.SECONDS);
            visibility.set(PLAYER, Visibility.PRIVATE).get(5, TimeUnit.SECONDS);
            when(plugin.getProfileVisibility()).thenReturn(visibility);
            StaticExpansion expansion = new StaticExpansion(plugin);
            assertEquals("", expansion.onRequest(player(), "votes"));
            assertEquals("", expansion.onRequest(player(), "recorded_since"));
            assertEquals("", expansion.onRequest(player(), "rank_votes"));
            assertEquals("Visible", expansion.onRequest(player(), "top_votes_1_name"));
            assertEquals("1", expansion.onRequest(null, "top_votes_1_rank"));
            assertEquals("1", expansion.onRequest(null, "ranking_votes_size"));
            assertEquals("", expansion.onRequest(null, "top_votes_2_name"));
            verify(statistics, never()).snapshot(PLAYER, StatsPeriod.LIFETIME);
        }
    }

    private StaticExpansion expansion(StatsService statistics) throws IOException {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        when(plugin.getStatsService()).thenReturn(statistics);
        language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        language.install(language.prepare("en_US"));
        when(plugin.getLanguageService()).thenReturn(language);
        return new StaticExpansion(plugin);
    }

    private static OfflinePlayer player() {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(PLAYER);
        return player;
    }
}
