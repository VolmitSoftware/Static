package com.volmit.staticstats.detailed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class DetailRankingSnapshot {
    private final DetailKey counter;
    private final long refreshedAt;
    private final List<RankedDetailPlayer> entries;
    private final Map<UUID, RankedDetailPlayer> players;
    private final int totalProfiles;
    private final long recordedSince;

    private DetailRankingSnapshot(SnapshotData data) {
        counter = data.counter();
        refreshedAt = data.refreshedAt();
        entries = List.copyOf(data.entries());
        totalProfiles = data.totalProfiles();
        recordedSince = data.recordedSince();
        Map<UUID, RankedDetailPlayer> indexed = new HashMap<>(entries.size());
        for (RankedDetailPlayer entry : entries) {
            indexed.put(entry.profile().uuid(), entry);
        }
        players = Map.copyOf(indexed);
    }

    public static DetailRankingSnapshot create(DetailKey counter, long refreshedAt, List<DetailSnapshot> profiles) {
        Objects.requireNonNull(counter);
        Objects.requireNonNull(profiles);
        if (refreshedAt < 0) {
            throw new IllegalArgumentException("Invalid detailed ranking capture time");
        }
        List<ScoredPlayer> ordered = new ArrayList<>(profiles.size());
        Set<UUID> identities = new HashSet<>(profiles.size());
        long recordedSince = 0;
        for (DetailSnapshot profile : profiles) {
            if (!identities.add(profile.uuid())) {
                throw new IllegalArgumentException("Duplicate detailed ranking profile " + profile.uuid());
            }
            long value = profile.value(counter);
            long observation = profile.recordedSince(counter);
            if (value <= 0 || observation <= 0) {
                continue;
            }
            ordered.add(new ScoredPlayer(profile, value));
            recordedSince = recordedSince == 0 ? observation : Math.min(recordedSince, observation);
        }
        ordered.sort(Comparator.comparingLong(ScoredPlayer::value).reversed()
                .thenComparing(entry -> entry.profile().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.profile().uuid()));
        List<RankedDetailPlayer> ranked = new ArrayList<>(ordered.size());
        int rank = 1;
        long groupValue = ordered.isEmpty() ? 0 : ordered.getFirst().value();
        long gap = 0;
        for (int index = 0; index < ordered.size(); index++) {
            ScoredPlayer player = ordered.get(index);
            if (player.value() != groupValue) {
                gap = groupValue - player.value();
                groupValue = player.value();
                rank = index + 1;
            }
            ranked.add(new RankedDetailPlayer(player.profile(), index + 1, rank, player.value(), gap));
        }
        return new DetailRankingSnapshot(new SnapshotData(counter, refreshedAt, ranked, profiles.size(), recordedSince));
    }

    public DetailRankingSnapshot visible(Predicate<UUID> visible) {
        List<RankedDetailPlayer> selected = new ArrayList<>();
        long groupValue = -1;
        long gap = 0;
        long firstObservation = 0;
        int rank = 0;
        for (RankedDetailPlayer entry : entries) {
            if (!visible.test(entry.profile().uuid())) {
                continue;
            }
            int position = selected.size() + 1;
            if (groupValue != entry.value()) {
                gap = position == 1 ? 0 : groupValue - entry.value();
                groupValue = entry.value();
                rank = position;
            }
            selected.add(new RankedDetailPlayer(entry.profile(), position, rank, entry.value(), gap));
            long observation = entry.profile().recordedSince(counter);
            firstObservation = firstObservation == 0 ? observation : Math.min(firstObservation, observation);
        }
        return new DetailRankingSnapshot(new SnapshotData(counter, refreshedAt, selected, selected.size(), firstObservation));
    }

    public DetailKey counter() {
        return counter;
    }

    public long refreshedAt() {
        return refreshedAt;
    }

    public List<RankedDetailPlayer> entries() {
        return entries;
    }

    public Optional<RankedDetailPlayer> find(UUID uuid) {
        return Optional.ofNullable(players.get(Objects.requireNonNull(uuid)));
    }

    public int totalProfiles() {
        return totalProfiles;
    }

    public int excludedCount() {
        return totalProfiles - entries.size();
    }

    public long recordedSince() {
        return recordedSince;
    }

    private record ScoredPlayer(DetailSnapshot profile, long value) { }

    private record SnapshotData(DetailKey counter, long refreshedAt, List<RankedDetailPlayer> entries,
                                int totalProfiles, long recordedSince) { }
}
