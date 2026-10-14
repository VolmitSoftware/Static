package com.volmit.staticstats.dashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.participation.ParticipationSnapshot;
import com.volmit.staticstats.records.PersonalBest;
import com.volmit.staticstats.records.PersonalBestPeriod;
import com.volmit.staticstats.records.PersonalBestSnapshot;
import com.volmit.staticstats.stats.PeriodStatsSnapshot;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankingRules;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.SessionEndReason;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardDataTest {
    private static final ZoneId ZONE = ZoneId.of("America/Denver");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void directoryCachesDetachedNamesAndRefreshesAfterFiveSeconds() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        AtomicLong now = new AtomicLong();
        AtomicInteger captures = new AtomicInteger();
        List<DashboardDataPlayer> profiles = new ArrayList<>();
        profiles.add(new DashboardDataPlayer(PLAYER, "Zulu", 8, 3));
        DashboardData data = new DashboardData(statistics, () -> {
            captures.incrementAndGet();
            return profiles;
        }, uuid -> false, now::get);
        assertThat(data.players("", 0, 25).get("total").getAsInt()).isEqualTo(1);
        profiles.add(new DashboardDataPlayer(UUID.randomUUID(), "Alpha", 9, 4));
        assertThat(data.players("", 0, 25).get("total").getAsInt()).isEqualTo(1);
        now.set(5_000_000_000L);
        JsonObject result = data.players("", 0, 1);
        assertThat(result.get("total").getAsInt()).isEqualTo(2);
        assertThat(result.getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("Alpha");
        assertThat(captures.get()).isEqualTo(2);
        verifyNoInteractions(statistics);
    }

    @Test
    void concurrentDirectoryReadersShareOneCapture() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DashboardData data = new DashboardData(mock(StaticStatistics.class), () -> {
            captures.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Directory capture timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return List.of(new DashboardDataPlayer(PLAYER, "Reader", 8, 3));
        }, uuid -> false);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<JsonObject> first = CompletableFuture.supplyAsync(() -> data.players("", 0, 25), executor);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<JsonObject> second = CompletableFuture.supplyAsync(() -> data.players("", 0, 25), executor);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).get("total").getAsInt()).isEqualTo(1);
            assertThat(second.get(5, TimeUnit.SECONDS).get("total").getAsInt()).isEqualTo(1);
            assertThat(captures.get()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void directorySearchIsLiteralCaseInsensitiveAndHandlesOverflowingPages() {
        DashboardData data = new DashboardData(mock(StaticStatistics.class), () -> List.of(
                new DashboardDataPlayer(PLAYER, "<Player.*>", 8, 3)), uuid -> false);
        assertThat(data.players("player.*", 0, 50).get("total").getAsInt()).isEqualTo(1);
        assertThat(data.players("000000000001", 0, 50).get("total").getAsInt()).isEqualTo(1);
        assertThat(data.players("", Integer.MAX_VALUE, 50).getAsJsonArray("entries")).isEmpty();
        assertThat(data.players("no match", 0, 50).getAsJsonArray("entries")).isEmpty();
        assertThatThrownBy(() -> data.players("x".repeat(65), 0, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> data.players("", -1, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> data.players("", 0, 51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> data.players("", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rankingsKeepCompetitionTiesAndQualifiedSampleCounts() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        List<PlayerStatsSnapshot> profiles = List.of(player("A", 20, 10), player("B", 40, 20), player("C", 100, 1));
        RankingSnapshot ranking = RankingSnapshot.create(StatKey.KILL_DEATH_RATIO, 1000, profiles, RankingRules.defaults(),
                StatsPeriod.LIFETIME.window(1000, ZONE), ZONE);
        when(statistics.ranking(StatKey.KILL_DEATH_RATIO, StatsPeriod.LIFETIME)).thenReturn(CompletableFuture.completedFuture(ranking));
        DashboardData data = new DashboardData(statistics, List::of, uuid -> false);
        JsonObject result = data.rankings(StatKey.KILL_DEATH_RATIO, StatsPeriod.LIFETIME, 0, 1).join();
        assertThat(result.get("totalProfiles").getAsInt()).isEqualTo(3);
        assertThat(result.get("excludedCount").getAsInt()).isEqualTo(1);
        assertThat(result.get("total").getAsInt()).isEqualTo(2);
        assertThat(result.getAsJsonArray("entries").size()).isEqualTo(1);
        JsonObject second = data.rankings(StatKey.KILL_DEATH_RATIO, StatsPeriod.LIFETIME, 1, 1).join()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        assertThat(second.get("rank").getAsInt()).isEqualTo(1);
        assertThat(second.get("position").getAsInt()).isEqualTo(2);
        assertThat(result.getAsJsonObject("window").get("endsAt").getAsString()).isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    @Test
    void overviewKeepsMissingMedianUnknownCohortsAndRetentionWarning() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        ParticipationSnapshot summary = new ParticipationSnapshot(StatsPeriod.DAY.window(1000, ZONE), ZONE, 1000,
                3, 1, 1, 1, 0, OptionalDouble.empty(), 2, 1, 0, 1, true);
        when(statistics.participation(StatsPeriod.DAY, 0)).thenReturn(CompletableFuture.completedFuture(summary));
        when(statistics.playerCount()).thenReturn(10);
        JsonObject result = new DashboardData(statistics, List::of, uuid -> false).overview(StatsPeriod.DAY, 0).join();
        assertThat(result.get("playerCount").getAsInt()).isEqualTo(10);
        JsonObject participation = result.getAsJsonObject("participation");
        assertThat(participation.get("medianSessionMillis").isJsonNull()).isTrue();
        assertThat(participation.get("unknownCohortPlayers").getAsInt()).isEqualTo(1);
        assertThat(participation.get("retentionLimited").getAsBoolean()).isTrue();
        assertThat(participation.get("historyZone").getAsString()).isEqualTo(ZONE.getId());
    }

    @Test
    void profilePreservesExactLongCountersAndBoundsNewestSessionsAndHistory() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        PlayerStatsSnapshot snapshot = new PlayerStatsSnapshot(PLAYER, "<script>name</script>", Map.of(StatKey.JOINS, 5D), 10, 1);
        when(statistics.snapshot(PLAYER, StatsPeriod.DAY)).thenReturn(Optional.of(snapshot));
        when(statistics.historyZone()).thenReturn(ZONE);
        when(statistics.details(PLAYER)).thenReturn(Optional.of(new DetailSnapshot(PLAYER, snapshot.name(),
                Map.of(DetailKey.MINED_DIAMOND_ORE, Long.MAX_VALUE), Map.of(DetailKey.MINED_DIAMOND_ORE, 1L),
                Map.of(DetailKey.MINED_DIAMOND_ORE, 10L), 10)));
        List<PlayerSession> sessions = new ArrayList<>();
        List<PeriodStatsSnapshot> history = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            sessions.add(new PlayerSession(UUID.randomUUID(), index, index + 10, index + 10, 10, 8, 2, SessionEndReason.QUIT));
            history.add(new PeriodStatsSnapshot(StatsPeriod.DAY.window(index * 86_400_000L, ZONE), snapshot, 1, ZONE));
        }
        when(statistics.sessions(PLAYER)).thenReturn(sessions);
        when(statistics.history(PLAYER, StatsPeriod.DAY)).thenReturn(history);
        JsonObject result = new DashboardData(statistics, List::of, uuid -> false).profile(PLAYER, StatsPeriod.DAY).orElseThrow();
        assertThat(result.get("name").getAsString()).isEqualTo(snapshot.name());
        assertThat(result.getAsJsonArray("details").get(0).getAsJsonObject().get("value").getAsJsonPrimitive().isString()).isTrue();
        assertThat(result.getAsJsonArray("details").get(0).getAsJsonObject().get("value").getAsString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(result.getAsJsonArray("sessions").size()).isEqualTo(50);
        assertThat(result.get("sessionsTotal").getAsInt()).isEqualTo(60);
        assertThat(result.getAsJsonArray("sessions").get(0).getAsJsonObject().get("startedAt").getAsString()).isEqualTo("59");
        assertThat(result.getAsJsonArray("history").size()).isEqualTo(50);
        assertThat(result.get("historyTotal").getAsInt()).isEqualTo(60);
        assertThat(result.getAsJsonArray("bests")).isEmpty();
    }

    @Test
    void absentProfileRemainsAbsentAndMetadataEnumeratesAllStatistics() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        when(statistics.historyZone()).thenReturn(ZONE);
        DashboardData data = new DashboardData(statistics, List::of, uuid -> false);
        assertThat(data.profile(PLAYER, StatsPeriod.LIFETIME)).isEmpty();
        JsonArray keys = data.metadata().getAsJsonArray("statistics");
        assertThat(keys.size()).isEqualTo(StatKey.values().length);
    }

    @Test
    void publicProjectionUsesOnlyPublishedTotalsAndRecordsWithoutActivityDates() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        PlayerStatsSnapshot snapshot = new PlayerStatsSnapshot(PLAYER, "<script>name</script>", Map.of(StatKey.JOINS, 5D), 10, 1);
        when(statistics.snapshot(PLAYER)).thenReturn(Optional.of(snapshot));
        when(statistics.details(PLAYER)).thenReturn(Optional.of(new DetailSnapshot(PLAYER, snapshot.name(),
                Map.of(DetailKey.MINED_DIAMOND_ORE, Long.MAX_VALUE), Map.of(DetailKey.MINED_DIAMOND_ORE, 1L),
                Map.of(DetailKey.MINED_DIAMOND_ORE, 10L), 10)));
        when(statistics.personalBests(PLAYER)).thenReturn(Optional.of(new PersonalBestSnapshot(PLAYER, snapshot.name(), ZONE, 1,
                List.of(new PersonalBest(PersonalBestPeriod.DAY, StatKey.JOINS, 5D, 0, 20, 1, 10, true, false)))));
        JsonObject result = new DashboardData(statistics, List::of, uuid -> true).publicProfile(PLAYER).orElseThrow();
        assertThat(result.keySet()).containsExactlyInAnyOrder("uuid", "name", "statistics", "details", "bests");
        assertThat(result.getAsJsonArray("details").get(0).getAsJsonObject().keySet()).containsExactlyInAnyOrder("id", "name", "value");
        assertThat(result.getAsJsonArray("details").get(0).getAsJsonObject().get("value").getAsString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(result.getAsJsonArray("bests").get(0).getAsJsonObject().keySet()).containsExactlyInAnyOrder("period", "stat", "value", "formatted");
        assertThat(result.toString()).doesNotContain("startedAt", "endsAt", "updatedAt", "recordedSince", "observedAt", "inProgress", "recovered", "sessions", "history");
    }

    @Test
    void unpublishedProfilesAreNotReadAndRevocationDiscardsAnInFlightProjection() {
        StaticStatistics statistics = mock(StaticStatistics.class);
        AtomicBoolean published = new AtomicBoolean();
        DashboardData data = new DashboardData(statistics, List::of, uuid -> published.get());
        assertThat(data.publicProfile(PLAYER)).isEmpty();
        verifyNoInteractions(statistics);
        published.set(true);
        when(statistics.snapshot(PLAYER)).thenAnswer(invocation -> {
            published.set(false);
            return Optional.of(new PlayerStatsSnapshot(PLAYER, "Revoked", Map.of(), 10, 1));
        });
        assertThat(data.publicProfile(PLAYER)).isEmpty();
    }

    private static PlayerStatsSnapshot player(String name, double kills, double deaths) {
        return new PlayerStatsSnapshot(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name,
                Map.of(StatKey.PVP_KILLS, kills, StatKey.PVP_DEATHS, deaths), 10, 1);
    }
}
