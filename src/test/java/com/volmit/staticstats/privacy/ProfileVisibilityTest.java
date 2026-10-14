package com.volmit.staticstats.privacy;

import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankingRules;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileVisibilityTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Logger LOGGER = Logger.getLogger(ProfileVisibilityTest.class.getName());

    @TempDir
    Path directory;

    @Test
    void defaultServerAndDurablePublicPrivateChangesSurviveRestart() throws Exception {
        Path file = directory.resolve("profile-visibility.json");
        try (ProfileVisibility visibility = ready(file)) {
            assertThat(visibility.visibility(FIRST)).isEqualTo(Visibility.SERVER);
            assertThat(visibility.isServerVisible(FIRST)).isTrue();
            assertThat(visibility.isPublic(FIRST)).isFalse();
            assertThat(visibility.set(FIRST, Visibility.PUBLIC).get(5, TimeUnit.SECONDS)).isEqualTo(Visibility.PUBLIC);
            assertThat(visibility.isPublic(FIRST)).isTrue();
            visibility.set(SECOND, Visibility.PRIVATE).get(5, TimeUnit.SECONDS);
        }
        try (ProfileVisibility visibility = ready(file)) {
            assertThat(visibility.isPublic(FIRST)).isTrue();
            assertThat(visibility.isServerVisible(SECOND)).isFalse();
            assertThat(visibility.canView(SECOND, SECOND, false)).isTrue();
            assertThat(visibility.canView(SECOND, FIRST, false)).isFalse();
            assertThat(visibility.canView(SECOND, FIRST, true)).isTrue();
            visibility.set(FIRST, Visibility.SERVER).get(5, TimeUnit.SECONDS);
            assertThat(visibility.isPublic(FIRST)).isFalse();
        }
        try (ProfileVisibility visibility = ready(file)) {
            assertThat(visibility.visibility(FIRST)).isEqualTo(Visibility.SERVER);
        }
    }

    @Test
    void malformedStorageFailsClosedAndCannotBeOverwritten() throws Exception {
        Path file = directory.resolve("profile-visibility.json");
        Files.writeString(file, "{invalid");
        try (ProfileVisibility visibility = new ProfileVisibility(file, LOGGER)) {
            assertThatThrownBy(() -> visibility.ready().get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(Exception.class);
            assertThat(visibility.available()).isFalse();
            assertThat(visibility.isPublic(FIRST)).isFalse();
            assertThat(visibility.isServerVisible(FIRST)).isFalse();
            assertThat(visibility.canView(FIRST, FIRST, false)).isTrue();
            assertThat(visibility.canView(FIRST, null, true)).isTrue();
            assertThatThrownBy(() -> visibility.set(FIRST, Visibility.PUBLIC).get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(Exception.class);
            assertThat(Files.readString(file)).isEqualTo("{invalid");
        }
    }

    @Test
    void failedWriteKeepsPublishedPolicyAndCanRetry() throws Exception {
        Path file = directory.resolve("profile-visibility.json");
        try (ProfileVisibility visibility = ready(file)) {
            visibility.set(FIRST, Visibility.PRIVATE).get(5, TimeUnit.SECONDS);
            Files.delete(file);
            Files.createDirectory(file);
            Files.writeString(file.resolve("occupied"), "prevent replacement");
            assertThatThrownBy(() -> visibility.set(FIRST, Visibility.PUBLIC).get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(Exception.class);
            assertThat(visibility.visibility(FIRST)).isEqualTo(Visibility.PRIVATE);
            Files.delete(file.resolve("occupied"));
            Files.delete(file);
            visibility.set(FIRST, Visibility.PUBLIC).get(5, TimeUnit.SECONDS);
            assertThat(visibility.isPublic(FIRST)).isTrue();
        }
    }

    @Test
    void filteringRecomputesTiesPositionsGapsAndInvalidatesOnChange() throws Exception {
        RankingSnapshot board = ranking(StatKey.BLOCKS_BROKEN, List.of(profile(FIRST, 100, 0), profile(SECOND, 40, 0), profile(THIRD, 40, 0)));
        try (ProfileVisibility visibility = ready(directory.resolve("visibility.json"))) {
            RankingSnapshot cached = visibility.filter(board);
            assertThat(visibility.filter(board)).isSameAs(cached);
            visibility.set(FIRST, Visibility.PRIVATE).get(5, TimeUnit.SECONDS);
            RankingSnapshot filtered = visibility.filter(board);
            assertThat(filtered).isNotSameAs(cached);
            assertThat(filtered.entries()).hasSize(2);
            assertThat(filtered.totalProfiles()).isEqualTo(2);
            assertThat(filtered.find(FIRST)).isEmpty();
            assertThat(filtered.find(SECOND).orElseThrow().rank()).isEqualTo(1);
            assertThat(filtered.find(THIRD).orElseThrow().rank()).isEqualTo(1);
            assertThat(filtered.find(THIRD).orElseThrow().position()).isEqualTo(2);
            assertThat(filtered.find(SECOND).orElseThrow().gapToNext()).isZero();
            visibility.set(FIRST, Visibility.SERVER).get(5, TimeUnit.SECONDS);
            assertThat(visibility.filter(board).find(SECOND).orElseThrow().rank()).isEqualTo(2);
        }
    }

    @Test
    void ratioFilteringRetainsVisibleUnqualifiedSamplesWithoutPrivateMetadata() throws Exception {
        RankingSnapshot board = ranking(StatKey.KILL_DEATH_RATIO, List.of(profile(FIRST, 100, 20), profile(SECOND, 40, 20), profile(THIRD, 40, 1)));
        try (ProfileVisibility visibility = ready(directory.resolve("visibility.json"))) {
            visibility.set(FIRST, Visibility.PRIVATE).get(5, TimeUnit.SECONDS);
            RankingSnapshot filtered = visibility.filter(board);
            assertThat(filtered.entries()).hasSize(1);
            assertThat(filtered.totalProfiles()).isEqualTo(2);
            assertThat(filtered.excludedCount()).isEqualTo(1);
            assertThat(filtered.qualification(FIRST)).isEmpty();
            assertThat(filtered.qualification(THIRD).orElseThrow().qualified()).isFalse();
            assertThat(filtered.entries().getFirst().rank()).isEqualTo(1);
        }
    }

    @Test
    void detailedFilterKeepsExactLargeIntegerRanksAndGaps() throws Exception {
        DetailRankingSnapshot board = DetailRankingSnapshot.create(DetailKey.MINED_DIAMOND_ORE, 10,
                List.of(detail(FIRST, Long.MAX_VALUE), detail(SECOND, 9_007_199_254_740_993L), detail(THIRD, 9_007_199_254_740_992L)));
        try (ProfileVisibility visibility = ready(directory.resolve("visibility.json"))) {
            visibility.set(FIRST, Visibility.PRIVATE).get(5, TimeUnit.SECONDS);
            DetailRankingSnapshot filtered = visibility.filter(board);
            assertThat(filtered.find(FIRST)).isEmpty();
            assertThat(filtered.find(SECOND).orElseThrow().rank()).isEqualTo(1);
            assertThat(filtered.find(THIRD).orElseThrow().gapToNext()).isEqualTo(1L);
            assertThat(filtered.entries()).hasSize(2);
        }
    }

    @Test
    void ordinaryOthersPermissionDoesNotBypassPrivateAndShutdownFailsClosed() throws Exception {
        ProfileVisibility visibility = ready(directory.resolve("visibility.json"));
        try {
            visibility.set(FIRST, Visibility.PRIVATE).get(5, TimeUnit.SECONDS);
            Player viewer = mock(Player.class);
            when(viewer.getUniqueId()).thenReturn(SECOND);
            when(viewer.hasPermission("static.stats.others")).thenReturn(true);
            assertThat(visibility.canView(viewer, FIRST)).isFalse();
            when(viewer.hasPermission("static.privacy.bypass")).thenReturn(true);
            assertThat(visibility.canView(viewer, FIRST)).isTrue();
            CommandSender block = mock(CommandSender.class);
            assertThat(visibility.canView(block, FIRST)).isFalse();
            visibility.set(FIRST, Visibility.PUBLIC).get(5, TimeUnit.SECONDS);
            RankingSnapshot board = ranking(StatKey.BLOCKS_BROKEN, List.of(profile(FIRST, 100, 0)));
            assertThat(visibility.filter(board).entries()).hasSize(1);
            visibility.close();
            assertThat(visibility.isPublic(FIRST)).isFalse();
            assertThat(visibility.filter(board).entries()).isEmpty();
            assertThatThrownBy(() -> visibility.set(FIRST, Visibility.PUBLIC).join()).hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            visibility.close();
        }
    }

    @Test
    void serializedUpdatesPublishAfterDurabilityAndNotifyEachAcceptedChange() throws Exception {
        try (ProfileVisibility visibility = ready(directory.resolve("visibility.json"))) {
            List<UUID> observed = new CopyOnWriteArrayList<>();
            visibility.onChanged(observed::add);
            CompletableFuture<Visibility> first = visibility.set(FIRST, Visibility.PUBLIC);
            CompletableFuture<Visibility> second = visibility.set(FIRST, Visibility.PRIVATE);
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(Visibility.PUBLIC);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Visibility.PRIVATE);
            assertThat(visibility.visibility(FIRST)).isEqualTo(Visibility.PRIVATE);
            assertThat(observed).containsExactly(FIRST, FIRST);
            assertThat(Files.readString(directory.resolve("visibility.json"))).contains("private");
        }
    }

    @Test
    void boundedWriterRejectsExcessRequestsAndAcceptsRetryAfterDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ProfileVisibility visibility = ready(directory.resolve("visibility.json"))) {
            visibility.onChanged(uuid -> {
                if (!uuid.equals(FIRST)) {
                    return;
                }
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Visibility test write timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });
            CompletableFuture<Visibility> active = visibility.set(FIRST, Visibility.PUBLIC);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                List<CompletableFuture<Visibility>> queued = new ArrayList<>();
                for (int index = 0; index < 64; index++) {
                    queued.add(visibility.set(new UUID(1, index), Visibility.PRIVATE));
                }
                CompletableFuture<Visibility> rejected = visibility.set(SECOND, Visibility.PUBLIC);
                assertThatThrownBy(rejected::join).hasCauseInstanceOf(RejectedExecutionException.class);
                assertThat(visibility.visibility(SECOND)).isEqualTo(Visibility.SERVER);
                release.countDown();
                active.get(5, TimeUnit.SECONDS);
                for (CompletableFuture<Visibility> accepted : queued) {
                    assertThat(accepted.get(5, TimeUnit.SECONDS)).isEqualTo(Visibility.PRIVATE);
                }
                assertThat(visibility.set(SECOND, Visibility.PUBLIC).get(5, TimeUnit.SECONDS)).isEqualTo(Visibility.PUBLIC);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void unchangedModesDoNotWriteNotifyOrInvalidateCachedPolicy() throws Exception {
        Path file = directory.resolve("visibility.json");
        try (ProfileVisibility visibility = ready(file)) {
            List<UUID> changes = new ArrayList<>();
            visibility.onChanged(changes::add);
            long revision = visibility.revision();
            assertThat(visibility.set(FIRST, Visibility.SERVER).get(5, TimeUnit.SECONDS)).isEqualTo(Visibility.SERVER);
            assertThat(Files.exists(file)).isFalse();
            assertThat(visibility.revision()).isEqualTo(revision);
            visibility.set(FIRST, Visibility.PRIVATE).get(5, TimeUnit.SECONDS);
            revision = visibility.revision();
            Files.delete(file);
            Files.createDirectory(file);
            Files.writeString(file.resolve("occupied"), "not writable as a file");
            assertThat(visibility.set(FIRST, Visibility.PRIVATE).get(5, TimeUnit.SECONDS)).isEqualTo(Visibility.PRIVATE);
            assertThat(visibility.revision()).isEqualTo(revision);
            assertThat(changes).containsExactly(FIRST);
        }
    }

    private static ProfileVisibility ready(Path file) throws Exception {
        ProfileVisibility visibility = new ProfileVisibility(file, LOGGER);
        visibility.ready().get(5, TimeUnit.SECONDS);
        return visibility;
    }

    private static RankingSnapshot ranking(StatKey key, List<PlayerStatsSnapshot> profiles) {
        return RankingSnapshot.create(key, 10, profiles, RankingRules.defaults(), StatsPeriod.LIFETIME.window(10, ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private static PlayerStatsSnapshot profile(UUID uuid, double value, double deaths) {
        return new PlayerStatsSnapshot(uuid, uuid.toString(), Map.of(StatKey.BLOCKS_BROKEN, value, StatKey.PVP_KILLS, value, StatKey.PVP_DEATHS, deaths), 10, 1);
    }

    private static DetailSnapshot detail(UUID uuid, long value) {
        return new DetailSnapshot(uuid, uuid.toString(), Map.of(DetailKey.MINED_DIAMOND_ORE, value),
                Map.of(DetailKey.MINED_DIAMOND_ORE, 1L), Map.of(DetailKey.MINED_DIAMOND_ORE, 10L), 10);
    }
}
