package com.volmit.staticstats.stats;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public final class RankingSnapshot {
    private final StatKey statistic;
    private final long refreshedAt;
    private final List<RankedPlayer> entries;
    private final Map<UUID, RankedPlayer> players;
    private final Map<UUID, RankingQualification> qualifications;
    private final RankingRules rules;
    private final int totalProfiles;
    private final PeriodWindow window;
    private final ZoneId historyZone;
    private final long recordedSince;

    private RankingSnapshot(SnapshotData data) {
        statistic = Objects.requireNonNull(data.statistic());
        refreshedAt = data.refreshedAt();
        entries = List.copyOf(data.entries());
        qualifications = Map.copyOf(data.qualifications());
        rules = Objects.requireNonNull(data.rules());
        totalProfiles = data.totalProfiles();
        window = data.window();
        historyZone = data.historyZone();
        recordedSince = data.recordedSince();
        Map<UUID, RankedPlayer> indexed = new HashMap<>(entries.size());
        for (RankedPlayer entry : entries) {
            indexed.put(entry.profile().uuid(), entry);
        }
        players = Map.copyOf(indexed);
    }

    public static RankingSnapshot create(StatKey statistic, long refreshedAt, List<PlayerStatsSnapshot> profiles, RankingRules rules,
                                  PeriodWindow window, ZoneId historyZone) {
        List<ScoredPlayer> ordered = new ArrayList<>(profiles.size());
        Map<UUID, RankingQualification> qualifications = new HashMap<>();
        Optional<RankingRequirement> requirement = rules.requirement(statistic);
        for (PlayerStatsSnapshot profile : profiles) {
            if (requirement.isPresent()) {
                RankingQualification qualification = requirement.get().evaluate(profile);
                qualifications.put(profile.uuid(), qualification);
                if (!qualification.qualified()) {
                    continue;
                }
            }
            ordered.add(new ScoredPlayer(profile, profile.value(statistic)));
        }
        ordered.sort(Comparator.comparingDouble(ScoredPlayer::value).reversed()
                .thenComparing(entry -> entry.profile().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.profile().uuid()));
        List<RankedPlayer> ranked = new ArrayList<>(ordered.size());
        int rank = 1;
        double groupValue = ordered.isEmpty() ? 0 : ordered.getFirst().value();
        double gap = 0;
        for (int index = 0; index < ordered.size(); index++) {
            ScoredPlayer player = ordered.get(index);
            if (Double.compare(player.value(), groupValue) != 0) {
                gap = groupValue - player.value();
                groupValue = player.value();
                rank = index + 1;
            }
            ranked.add(new RankedPlayer(player.profile(), index + 1, rank, player.value(), gap));
        }
        return new RankingSnapshot(new SnapshotData(statistic, refreshedAt, ranked, qualifications, rules, profiles.size(),
                window, historyZone, coverage(profiles, window.period())));
    }

    public RankingSnapshot visible(Predicate<UUID> visible) {
        List<RankedPlayer> selected = new ArrayList<>();
        List<PlayerStatsSnapshot> profiles = new ArrayList<>();
        Map<UUID, RankingQualification> allowedQualifications = new HashMap<>();
        qualifications.forEach((uuid, qualification) -> {
            if (visible.test(uuid)) {
                allowedQualifications.put(uuid, qualification);
            }
        });
        double groupValue = -1;
        double gap = 0;
        int rank = 0;
        for (RankedPlayer entry : entries) {
            if (!visible.test(entry.profile().uuid())) {
                continue;
            }
            int position = selected.size() + 1;
            if (Double.compare(groupValue, entry.value()) != 0) {
                gap = position == 1 ? 0 : groupValue - entry.value();
                groupValue = entry.value();
                rank = position;
            }
            selected.add(new RankedPlayer(entry.profile(), position, rank, entry.value(), gap));
            profiles.add(entry.profile());
        }
        int total = rules.requirement(statistic).isPresent() ? allowedQualifications.size() : selected.size();
        return new RankingSnapshot(new SnapshotData(statistic, refreshedAt, selected, allowedQualifications,
                rules, total, window, historyZone, coverage(profiles, window.period())));
    }

    public StatKey statistic() {
        return statistic;
    }

    public long refreshedAt() {
        return refreshedAt;
    }

    public List<RankedPlayer> entries() {
        return entries;
    }

    public Optional<RankedPlayer> find(UUID uuid) {
        return Optional.ofNullable(players.get(Objects.requireNonNull(uuid)));
    }

    public Optional<RankingQualification> qualification(UUID uuid) {
        return Optional.ofNullable(qualifications.get(Objects.requireNonNull(uuid)));
    }

    public RankingRules rules() {
        return rules;
    }

    public int totalProfiles() {
        return totalProfiles;
    }

    public int excludedCount() {
        return totalProfiles - entries.size();
    }

    public StatsPeriod period() {
        return window.period();
    }

    public PeriodWindow window() {
        return window;
    }

    public ZoneId historyZone() {
        return historyZone;
    }

    public long recordedSince() {
        return recordedSince;
    }

    private static long coverage(List<PlayerStatsSnapshot> profiles, StatsPeriod period) {
        long earliest = Long.MAX_VALUE;
        for (PlayerStatsSnapshot profile : profiles) {
            if (profile.recordedSince() == 0) {
                if (period == StatsPeriod.LIFETIME) {
                    return 0;
                }
                continue;
            }
            earliest = Math.min(earliest, profile.recordedSince());
        }
        return earliest == Long.MAX_VALUE ? 0 : earliest;
    }

    private record SnapshotData(StatKey statistic, long refreshedAt, List<RankedPlayer> entries,
                                Map<UUID, RankingQualification> qualifications, RankingRules rules, int totalProfiles,
                                PeriodWindow window, ZoneId historyZone, long recordedSince) { }

    private record ScoredPlayer(PlayerStatsSnapshot profile, double value) { }
}
