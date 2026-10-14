package com.volmit.staticstats.api;

import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.records.PersonalBestSnapshot;
import com.volmit.staticstats.participation.ParticipationSnapshot;

import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import com.volmit.staticstats.stats.PeriodStatsSnapshot;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.milestone.MilestoneCompletion;
import com.volmit.staticstats.milestone.MilestoneCompletionEvent;
import com.volmit.staticstats.milestone.MilestoneProgress;
import com.volmit.staticstats.season.SeasonCompletionEvent;
import com.volmit.staticstats.season.SeasonProfileSnapshot;
import com.volmit.staticstats.season.SeasonRankingSnapshot;
import com.volmit.staticstats.season.SeasonSnapshot;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface StaticStatistics {
    Optional<PlayerStatsSnapshot> snapshot(UUID uuid);

    Optional<DetailSnapshot> details(UUID uuid);

    Optional<DetailRankingSnapshot> cachedDetailRanking(DetailKey counter);

    CompletableFuture<DetailRankingSnapshot> detailRanking(DetailKey counter);

    Optional<PersonalBestSnapshot> personalBests(UUID uuid);

    Optional<ParticipationSnapshot> cachedParticipation(StatsPeriod period, int offset);

    CompletableFuture<ParticipationSnapshot> participation(StatsPeriod period, int offset);

    void addDetail(UUID uuid, DetailKey key, long amount);

    Optional<PlayerStatsSnapshot> snapshot(UUID uuid, StatsPeriod period);

    Optional<PeriodStatsSnapshot> periodSnapshot(UUID uuid, StatsPeriod period);

    List<PeriodStatsSnapshot> history(UUID uuid, StatsPeriod period);

    List<PlayerSession> sessions(UUID uuid);

    ZoneId historyZone();

    long activityRecordedSince(UUID uuid);

    List<MilestoneProgress> milestones(UUID uuid);

    List<MilestoneCompletion> milestoneCompletions(UUID uuid);

    AutoCloseable onMilestoneCompletion(Consumer<MilestoneCompletionEvent> listener);

    List<SeasonSnapshot> seasons();

    Optional<SeasonSnapshot> season(String id);

    Optional<SeasonProfileSnapshot> seasonSnapshot(UUID uuid, String id);

    CompletableFuture<SeasonRankingSnapshot> seasonRanking(String id);

    AutoCloseable onSeasonCompletion(Consumer<SeasonCompletionEvent> listener);

    Optional<PlayerStatsSnapshot> find(String nameOrUuid);

    Optional<PlayerStatsSnapshot> find(String nameOrUuid, StatsPeriod period);

    List<PlayerStatsSnapshot> leaderboard(StatKey key, int offset, int limit);

    Optional<RankingSnapshot> cachedRanking(StatKey key);

    Optional<RankingSnapshot> cachedRanking(StatKey key, StatsPeriod period);

    CompletableFuture<RankingSnapshot> ranking(StatKey key);

    CompletableFuture<RankingSnapshot> ranking(StatKey key, StatsPeriod period);

    int playerCount();

    void add(UUID uuid, StatKey key, double amount);

    CompletableFuture<Void> flushAsync();
}
