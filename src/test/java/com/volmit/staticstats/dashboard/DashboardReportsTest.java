package com.volmit.staticstats.dashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.milestone.MilestoneCompletion;
import com.volmit.staticstats.milestone.MilestoneDefinition;
import com.volmit.staticstats.milestone.MilestoneProgress;
import com.volmit.staticstats.season.SeasonDefinition;
import com.volmit.staticstats.season.SeasonRankingSnapshot;
import com.volmit.staticstats.season.SeasonRules;
import com.volmit.staticstats.season.SeasonSnapshot;
import com.volmit.staticstats.season.SeasonStatus;
import com.volmit.staticstats.stats.PeriodStatsSnapshot;
import com.volmit.staticstats.stats.PeriodWindow;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankingRules;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardReportsTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);
    private static final ZoneId ZONE = ZoneId.of("America/Denver");

    @Test
    void detailRankingsKeepExactLongsAndOperatorSeesPrivateProfiles() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        DetailKey key = DetailKey.MINED_DIAMOND_ORE;
        DetailSnapshot detail = new DetailSnapshot(PLAYER, "Private", Map.of(key, Long.MAX_VALUE),
                Map.of(key, 1L), Map.of(key, 2L), 2L);
        when(statistics.detailRanking(key)).thenReturn(CompletableFuture.completedFuture(
                DetailRankingSnapshot.create(key, 3L, List.of(detail))));
        DashboardData data = data(statistics);
        JsonObject result = data.detailRankings(key, 0, 1).join();
        JsonObject entry = result.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertThat(entry.get("value").getAsJsonPrimitive().isString()).isTrue();
        assertThat(entry.get("value").getAsString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(entry.get("name").getAsString()).isEqualTo("Private");
        assertThat(data.detailRankings(key, Integer.MAX_VALUE, 50).join().getAsJsonArray("entries")).isEmpty();
    }

    @Test
    void archivedSeasonPreservesAuthoritativeWinnersAndPagedStandings() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        SeasonRules rules = new SeasonRules(StatKey.BLOCKS_BROKEN, 0, Set.of(), false, false, 1000);
        SeasonSnapshot season = new SeasonSnapshot(new SeasonDefinition("autumn", "Autumn", Instant.ofEpochMilli(1),
                Instant.ofEpochMilli(1000), rules), SeasonStatus.ARCHIVED, 1, 1001, 2);
        RankingSnapshot ranking = RankingSnapshot.create(StatKey.BLOCKS_BROKEN, 1001,
                List.of(profile(PLAYER, 40), profile(OTHER, 20)), RankingRules.defaults(),
                new PeriodWindow(StatsPeriod.LIFETIME, "autumn", 1, 1000), ZONE);
        when(statistics.season("autumn")).thenReturn(Optional.of(season));
        when(statistics.seasons()).thenReturn(List.of(season));
        when(statistics.seasonRanking("autumn")).thenReturn(CompletableFuture.completedFuture(
                new SeasonRankingSnapshot(season, ranking, ranking.entries().subList(0, 1))));
        DashboardData data = data(statistics);
        JsonObject report = data.season("autumn", 1, 1).join().orElseThrow();
        assertThat(report.getAsJsonObject("season").get("status").getAsString()).isEqualTo("archived");
        assertThat(report.getAsJsonObject("ranking").getAsJsonArray("entries").get(0).getAsJsonObject().get("rank").getAsInt()).isEqualTo(2);
        assertThat(report.getAsJsonArray("winners").get(0).getAsJsonObject().get("uuid").getAsString()).isEqualTo(PLAYER.toString());
        assertThat(data.seasons(1, 1).getAsJsonArray("entries")).isEmpty();
        assertThat(data.season("missing", 0, 25).join()).isEmpty();
    }

    @Test
    void milestonesDistinguishHistoricalCompletionsFromUnreachedThresholds() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        when(statistics.snapshot(PLAYER)).thenReturn(Optional.of(profile(PLAYER, 40)));
        MilestoneDefinition low = new MilestoneDefinition(StatKey.BLOCKS_BROKEN, 10);
        MilestoneDefinition high = new MilestoneDefinition(StatKey.BLOCKS_BROKEN, 50);
        when(statistics.milestones(PLAYER)).thenReturn(List.of(new MilestoneProgress(high, 40, Optional.empty()),
                new MilestoneProgress(low, 40, Optional.of(new MilestoneCompletion(low, 9, 40, true)))));
        DashboardData data = data(statistics);
        JsonObject first = data.milestones(PLAYER, 0, 1).orElseThrow();
        assertThat(first.get("total").getAsInt()).isEqualTo(2);
        assertThat(first.get("completedTotal").getAsInt()).isEqualTo(1);
        assertThat(first.getAsJsonArray("entries").get(0).getAsJsonObject().get("historical").getAsBoolean()).isTrue();
        JsonObject pending = data.milestones(PLAYER, 1, 1).orElseThrow().getAsJsonArray("entries").get(0).getAsJsonObject();
        assertThat(pending.get("completedAt").isJsonNull()).isTrue();
        assertThat(pending.get("completed").getAsBoolean()).isFalse();
        assertThat(data.milestones(OTHER, 0, 25)).isEmpty();
    }

    @Test
    void comparisonAlignsPlayersAndPreservesMissingBucketsRatherThanInventingZeros() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        when(statistics.historyZone()).thenReturn(ZONE);
        when(statistics.snapshot(PLAYER)).thenReturn(Optional.of(profile(PLAYER, 40)));
        when(statistics.snapshot(OTHER)).thenReturn(Optional.of(profile(OTHER, 0)));
        PeriodWindow current = StatsPeriod.DAY.window(System.currentTimeMillis(), ZONE);
        when(statistics.history(PLAYER, StatsPeriod.DAY)).thenReturn(List.of(new PeriodStatsSnapshot(current,
                profile(PLAYER, 40), current.startedAt() + 1000, ZONE)));
        when(statistics.history(OTHER, StatsPeriod.DAY)).thenReturn(List.of(new PeriodStatsSnapshot(current,
                profile(OTHER, 0), current.startedAt() + 2000, ZONE)));
        DashboardData data = data(statistics);
        JsonObject report = data.comparison(PLAYER, Optional.of(OTHER), StatKey.BLOCKS_BROKEN, StatsPeriod.DAY, 2).orElseThrow();
        JsonArray players = report.getAsJsonArray("players");
        assertThat(players).hasSize(2);
        JsonArray first = players.get(0).getAsJsonObject().getAsJsonArray("points");
        assertThat(first.get(0).isJsonNull()).isTrue();
        assertThat(first.get(1).getAsJsonObject().get("value").getAsDouble()).isEqualTo(40);
        assertThat(players.get(1).getAsJsonObject().getAsJsonArray("points").get(1).getAsJsonObject().get("value").getAsDouble()).isZero();
        assertThat(data.comparison(PLAYER, Optional.of(PLAYER), StatKey.BLOCKS_BROKEN, StatsPeriod.DAY, 1)
                .orElseThrow().getAsJsonArray("players")).hasSize(1);
        assertThat(data.comparison(PLAYER, Optional.of(new UUID(0, 3)), StatKey.BLOCKS_BROKEN, StatsPeriod.DAY, 1)).isEmpty();
    }

    @Test
    void calendarWindowsRespectDaylightSavingAndMonthLengths() {
        List<PeriodWindow> days = DashboardData.comparisonWindows(StatsPeriod.DAY, 3,
                Instant.parse("2026-03-09T18:00:00Z").toEpochMilli(), ZONE);
        assertThat(days.get(1).endsAt() - days.get(1).startedAt()).isEqualTo(23 * 60 * 60 * 1000L);
        assertThat(days.get(0).endsAt()).isEqualTo(days.get(1).startedAt());
        List<PeriodWindow> months = DashboardData.comparisonWindows(StatsPeriod.MONTH, 2,
                Instant.parse("2026-03-09T18:00:00Z").toEpochMilli(), ZONE);
        assertThat(months.getFirst().id()).isEqualTo("2026-02");
        assertThat(months.getLast().id()).isEqualTo("2026-03");
    }

    @Test
    void comparisonComputesRatiosFromEachRetainedBucket() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        when(statistics.historyZone()).thenReturn(ZONE);
        when(statistics.snapshot(PLAYER)).thenReturn(Optional.of(profile(PLAYER, 0)));
        PeriodWindow window = StatsPeriod.MONTH.window(System.currentTimeMillis(), ZONE);
        PlayerStatsSnapshot bucket = new PlayerStatsSnapshot(PLAYER, "Ratio", Map.of(StatKey.PVP_KILLS, 9D,
                StatKey.PVP_DEATHS, 3D), 1000, 1);
        when(statistics.history(PLAYER, StatsPeriod.MONTH)).thenReturn(List.of(new PeriodStatsSnapshot(window,
                bucket, window.startedAt(), ZONE)));
        JsonObject report = data(statistics).comparison(PLAYER, Optional.empty(), StatKey.KILL_DEATH_RATIO,
                StatsPeriod.MONTH, 1).orElseThrow();
        JsonObject point = report.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonArray("points")
                .get(0).getAsJsonObject();
        assertThat(point.get("value").getAsDouble()).isEqualTo(3);
    }

    @Test
    void removedMilestoneThresholdRetainsItsDurableCompletion() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        when(statistics.snapshot(PLAYER)).thenReturn(Optional.of(profile(PLAYER, 40)));
        MilestoneDefinition removed = new MilestoneDefinition(StatKey.BLOCKS_BROKEN, 10);
        when(statistics.milestoneCompletions(PLAYER)).thenReturn(List.of(new MilestoneCompletion(removed, 9, 12, false)));
        JsonObject report = data(statistics).milestones(PLAYER, 0, 25).orElseThrow();
        assertThat(report.get("completedTotal").getAsInt()).isEqualTo(1);
        JsonObject entry = report.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertThat(entry.get("archived").getAsBoolean()).isTrue();
        assertThat(entry.get("observedValue").getAsDouble()).isEqualTo(12);
        assertThat(entry.get("completedAt").getAsString()).isEqualTo("9");
    }

    @Test
    void reportArgumentsAreBoundedBeforeStatisticsWork() {
        DashboardData data = data(mock(StaticStatistics.class));
        assertThatThrownBy(() -> data.detailRankings(DetailKey.MINED_COAL_ORE, 0, 51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> data.seasons(-1, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> data.season("../secret", 0, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> data.milestones(PLAYER, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> data.comparison(PLAYER, Optional.empty(), StatKey.BLOCKS_BROKEN, StatsPeriod.DAY, 13)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> data.comparison(PLAYER, Optional.empty(), StatKey.BLOCKS_BROKEN, StatsPeriod.LIFETIME, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static DashboardData data(StaticStatistics statistics) {
        return new DashboardData(statistics, List::of, uuid -> false);
    }

    private static PlayerStatsSnapshot profile(UUID uuid, double blocks) {
        return new PlayerStatsSnapshot(uuid, "Player" + uuid.getLeastSignificantBits(), Map.of(StatKey.BLOCKS_BROKEN, blocks), 1000, 1);
    }
}
