package com.volmit.staticstats.dashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.volmit.staticstats.api.StaticStatistics;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailSnapshot;
import com.volmit.staticstats.detailed.RankedDetailPlayer;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.milestone.MilestoneProgress;
import com.volmit.staticstats.milestone.MilestoneCompletion;
import com.volmit.staticstats.milestone.MilestoneDefinition;
import com.volmit.staticstats.season.SeasonSnapshot;
import com.volmit.staticstats.season.SeasonRankingSnapshot;
import com.volmit.staticstats.participation.ParticipationSnapshot;
import com.volmit.staticstats.records.PersonalBest;
import com.volmit.staticstats.records.PersonalBestSnapshot;
import com.volmit.staticstats.stats.PeriodStatsSnapshot;
import com.volmit.staticstats.stats.PeriodWindow;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankedPlayer;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.time.ZoneId;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

public final class DashboardData {
    public static final int PAGE_LIMIT = 50;
    public static final int HISTORY_LIMIT = 50;
    public static final int COMPARISON_LIMIT = 12;
    private static final long DIRECTORY_TTL_NANOS = 5_000_000_000L;
    private static final Comparator<DashboardDataPlayer> PLAYER_ORDER = Comparator
            .comparing(DashboardDataPlayer::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(DashboardDataPlayer::uuid);

    private final StaticStatistics statistics;
    private final Supplier<List<DashboardDataPlayer>> profiles;
    private final LongSupplier clock;
    private final Predicate<UUID> publicVisibility;
    private List<DashboardDataPlayer> directory;
    private long directoryCapturedAt;

    public DashboardData(StaticStatistics statistics, Supplier<List<DashboardDataPlayer>> profiles, Predicate<UUID> publicVisibility) {
        this(statistics, profiles, publicVisibility, System::nanoTime);
    }

    DashboardData(StaticStatistics statistics, Supplier<List<DashboardDataPlayer>> profiles, Predicate<UUID> publicVisibility, LongSupplier clock) {
        this.statistics = Objects.requireNonNull(statistics);
        this.profiles = Objects.requireNonNull(profiles);
        this.clock = Objects.requireNonNull(clock);
        this.publicVisibility = Objects.requireNonNull(publicVisibility);
    }

    public JsonObject metadata() {
        JsonObject result = new JsonObject();
        result.addProperty("historyZone", statistics.historyZone().getId());
        result.addProperty("pageSizeLimit", PAGE_LIMIT);
        result.addProperty("historyLimit", HISTORY_LIMIT);
        JsonArray keys = new JsonArray();
        for (StatKey key : StatKey.values()) {
            keys.add(statisticDescriptor(key));
        }
        result.add("statistics", keys);
        JsonArray periods = new JsonArray();
        for (StatsPeriod period : StatsPeriod.values()) {
            periods.add(period.id());
        }
        result.add("periods", periods);
        JsonArray counters = new JsonArray();
        for (DetailKey key : DetailKey.values()) {
            JsonObject counter = new JsonObject();
            counter.addProperty("id", key.id());
            counter.addProperty("name", key.displayName());
            counter.addProperty("category", key.category().name().toLowerCase(Locale.ROOT));
            counters.add(counter);
        }
        result.add("detailKeys", counters);
        result.addProperty("comparisonLimit", COMPARISON_LIMIT);
        return result;
    }

    public CompletableFuture<JsonObject> detailRankings(DetailKey counter, int page, int size) {
        validatePage(page, size);
        return statistics.detailRanking(Objects.requireNonNull(counter)).thenApply(snapshot -> detailRanking(snapshot, page, size));
    }

    public JsonObject seasons(int page, int size) {
        validatePage(page, size);
        List<SeasonSnapshot> seasons = new ArrayList<>(statistics.seasons());
        seasons.sort(Comparator.comparing((SeasonSnapshot season) -> season.definition().startsAt()).reversed()
                .thenComparing(season -> season.definition().id()));
        JsonObject result = pagination(page, size, seasons.size());
        JsonArray entries = new JsonArray();
        int from = start(page, size, seasons.size());
        for (int index = from; index < Math.min(seasons.size(), from + size); index++) {
            entries.add(seasonDescriptor(seasons.get(index)));
        }
        result.add("entries", entries);
        return result;
    }

    public CompletableFuture<Optional<JsonObject>> season(String id, int page, int size) {
        validatePage(page, size);
        if (!Objects.requireNonNull(id).matches("[a-z0-9][a-z0-9_-]{0,47}")) {
            throw new IllegalArgumentException("Invalid season ID");
        }
        if (statistics.season(id).isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return statistics.seasonRanking(id).thenApply(snapshot -> Optional.of(seasonReport(snapshot, page, size)));
    }

    public Optional<JsonObject> milestones(UUID uuid, int page, int size) {
        validatePage(page, size);
        Optional<PlayerStatsSnapshot> player = statistics.snapshot(Objects.requireNonNull(uuid));
        if (player.isEmpty()) {
            return Optional.empty();
        }
        List<MilestoneProgress> progress = new ArrayList<>(statistics.milestones(uuid));
        Set<MilestoneDefinition> current = new HashSet<>();
        for (MilestoneProgress entry : progress) {
            current.add(entry.definition());
        }
        for (MilestoneCompletion completion : statistics.milestoneCompletions(uuid)) {
            if (!current.contains(completion.definition())) {
                progress.add(new MilestoneProgress(completion.definition(), completion.observedValue(), Optional.of(completion)));
            }
        }
        progress.sort(Comparator.comparing((MilestoneProgress entry) -> entry.definition().statistic())
                .thenComparingDouble(entry -> entry.definition().threshold()));
        JsonObject result = pagination(page, size, progress.size());
        result.addProperty("uuid", uuid.toString());
        result.addProperty("name", player.get().name());
        int completed = 0;
        for (MilestoneProgress entry : progress) {
            if (entry.completion().isPresent()) {
                completed++;
            }
        }
        result.addProperty("completedTotal", completed);
        JsonArray entries = new JsonArray();
        int from = start(page, size, progress.size());
        for (int index = from; index < Math.min(progress.size(), from + size); index++) {
            MilestoneProgress entry = progress.get(index);
            JsonObject item = milestone(entry);
            item.addProperty("archived", !current.contains(entry.definition()));
            entries.add(item);
        }
        result.add("entries", entries);
        return Optional.of(result);
    }

    public Optional<JsonObject> comparison(UUID uuid, Optional<UUID> other, StatKey key, StatsPeriod period, int count) {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(other);
        Objects.requireNonNull(key);
        if (Objects.requireNonNull(period) == StatsPeriod.LIFETIME || count < 1 || count > COMPARISON_LIMIT) {
            throw new IllegalArgumentException("Comparison needs day, week or month and between 1 and 12 windows");
        }
        List<UUID> identities = other.filter(candidate -> !candidate.equals(uuid)).map(candidate -> List.of(uuid, candidate)).orElseGet(() -> List.of(uuid));
        List<PlayerStatsSnapshot> profiles = new ArrayList<>(identities.size());
        for (UUID identity : identities) {
            Optional<PlayerStatsSnapshot> profile = statistics.snapshot(identity);
            if (profile.isEmpty()) {
                return Optional.empty();
            }
            profiles.add(profile.get());
        }
        ZoneId zone = statistics.historyZone();
        List<PeriodWindow> windows = comparisonWindows(period, count, System.currentTimeMillis(), zone);
        JsonObject result = new JsonObject();
        result.addProperty("stat", key.id());
        result.addProperty("period", period.id());
        result.addProperty("historyZone", zone.getId());
        result.addProperty("count", count);
        JsonArray windowEntries = new JsonArray();
        for (PeriodWindow window : windows) {
            windowEntries.add(window(window));
        }
        result.add("windows", windowEntries);
        JsonArray players = new JsonArray();
        for (PlayerStatsSnapshot profile : profiles) {
            players.add(comparisonPlayer(profile, key, period, windows));
        }
        result.add("players", players);
        return Optional.of(result);
    }

    public CompletableFuture<JsonObject> overview(StatsPeriod period, int offset) {
        return statistics.participation(period, offset).thenApply(summary -> {
            JsonObject result = new JsonObject();
            result.addProperty("playerCount", statistics.playerCount());
            result.add("participation", participation(summary));
            return result;
        });
    }

    public CompletableFuture<JsonObject> rankings(StatKey key, StatsPeriod period, int page, int size) {
        validatePage(page, size);
        return statistics.ranking(key, period).thenApply(snapshot -> ranking(snapshot, page, size));
    }

    public JsonObject players(String query, int page, int size) {
        validatePage(page, size);
        String search = Objects.requireNonNull(query).strip();
        if (search.length() > 64) {
            throw new IllegalArgumentException("Player search must contain at most 64 characters");
        }
        String normalized = search.toLowerCase(Locale.ROOT);
        List<DashboardDataPlayer> matches = new ArrayList<>(size);
        long requestedStart = (long) page * size;
        int total = 0;
        for (DashboardDataPlayer player : directory()) {
            if (normalized.isEmpty() || player.name().toLowerCase(Locale.ROOT).contains(normalized)
                    || player.uuid().toString().contains(normalized)) {
                if (total >= requestedStart && matches.size() < size) {
                    matches.add(player);
                }
                total++;
            }
        }
        JsonObject result = pagination(page, size, total);
        result.addProperty("query", search);
        JsonArray entries = new JsonArray();
        for (DashboardDataPlayer player : matches) {
            JsonObject entry = identity(player.uuid(), player.name());
            number(entry, "updatedAt", player.updatedAt());
            number(entry, "recordedSince", player.recordedSince());
            entries.add(entry);
        }
        result.add("entries", entries);
        return result;
    }

    public Optional<JsonObject> profile(UUID uuid, StatsPeriod period) {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(period);
        return statistics.snapshot(uuid, period).map(snapshot -> profile(snapshot, period));
    }

    public Optional<JsonObject> publicProfile(UUID uuid) {
        Objects.requireNonNull(uuid);
        if (!publicVisibility.test(uuid)) {
            return Optional.empty();
        }
        Optional<PlayerStatsSnapshot> snapshot = statistics.snapshot(uuid);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        JsonObject result = identity(uuid, snapshot.get().name());
        result.add("statistics", values(snapshot.get()));
        result.add("details", statistics.details(uuid).map(this::publicDetails).orElseGet(JsonArray::new));
        result.add("bests", statistics.personalBests(uuid).map(this::publicBests).orElseGet(JsonArray::new));
        return publicVisibility.test(uuid) ? Optional.of(result) : Optional.empty();
    }

    static List<PeriodWindow> comparisonWindows(StatsPeriod period, int count, long now, ZoneId zone) {
        List<PeriodWindow> windows = new ArrayList<>(count);
        PeriodWindow current = period.window(now, zone);
        for (int index = 0; index < count; index++) {
            windows.add(current);
            current = period.window(current.startedAt() - 1, zone);
        }
        Collections.reverse(windows);
        return List.copyOf(windows);
    }

    private JsonObject comparisonPlayer(PlayerStatsSnapshot player, StatKey key, StatsPeriod period, List<PeriodWindow> windows) {
        Map<String, PeriodStatsSnapshot> retained = new HashMap<>();
        for (PeriodStatsSnapshot snapshot : statistics.history(player.uuid(), period)) {
            retained.put(snapshot.window().id(), snapshot);
        }
        JsonObject result = identity(player.uuid(), player.name());
        JsonArray points = new JsonArray();
        for (PeriodWindow window : windows) {
            PeriodStatsSnapshot snapshot = retained.get(window.id());
            if (snapshot == null) {
                points.add(JsonNull.INSTANCE);
                continue;
            }
            JsonObject point = new JsonObject();
            point.addProperty("value", snapshot.profile().value(key));
            point.addProperty("formatted", key.format(snapshot.profile().value(key)));
            number(point, "recordedSince", snapshot.recordedSince());
            points.add(point);
        }
        result.add("points", points);
        return result;
    }

    private static JsonObject seasonDescriptor(SeasonSnapshot snapshot) {
        JsonObject result = new JsonObject();
        result.addProperty("id", snapshot.definition().id());
        result.addProperty("name", snapshot.definition().name());
        result.addProperty("status", snapshot.status().name().toLowerCase(Locale.ROOT));
        result.addProperty("stat", snapshot.definition().rules().statistic().id());
        result.addProperty("minimumSample", snapshot.definition().rules().minimumSample());
        number(result, "startedAt", snapshot.definition().startsAt().toEpochMilli());
        number(result, "endsAt", snapshot.definition().endsAt().toEpochMilli());
        number(result, "recordedSince", snapshot.recordedSince());
        number(result, "completedAt", snapshot.completedAt());
        result.addProperty("participantCount", snapshot.participantCount());
        return result;
    }

    private static JsonObject seasonReport(SeasonRankingSnapshot snapshot, int page, int size) {
        JsonObject result = new JsonObject();
        result.add("season", seasonDescriptor(snapshot.season()));
        result.add("ranking", ranking(snapshot.ranking(), page, size));
        JsonArray winners = new JsonArray();
        for (int index = 0; index < Math.min(PAGE_LIMIT, snapshot.winners().size()); index++) {
            winners.add(rankedPlayer(snapshot.winners().get(index), snapshot.ranking().statistic()));
        }
        result.add("winners", winners);
        result.addProperty("winnersTotal", snapshot.winners().size());
        return result;
    }

    private static JsonObject milestone(MilestoneProgress progress) {
        StatKey key = progress.definition().statistic();
        JsonObject result = new JsonObject();
        result.addProperty("id", progress.definition().id());
        result.addProperty("stat", key.id());
        result.addProperty("threshold", progress.definition().threshold());
        result.addProperty("value", progress.value());
        result.addProperty("formatted", key.format(progress.value()));
        result.addProperty("thresholdFormatted", key.format(progress.definition().threshold()));
        result.addProperty("completed", progress.completion().isPresent());
        result.add("completedAt", JsonNull.INSTANCE);
        result.add("observedValue", JsonNull.INSTANCE);
        result.addProperty("historical", false);
        progress.completion().ifPresent(completion -> {
            number(result, "completedAt", completion.completedAt());
            result.addProperty("observedValue", completion.observedValue());
            result.addProperty("historical", completion.historical());
        });
        return result;
    }

    private static JsonObject rankedPlayer(RankedPlayer player, StatKey key) {
        JsonObject entry = identity(player.profile().uuid(), player.profile().name());
        entry.addProperty("rank", player.rank());
        entry.addProperty("position", player.position());
        entry.addProperty("value", player.value());
        entry.addProperty("formatted", key.format(player.value()));
        entry.addProperty("gapToNext", player.gapToNext());
        return entry;
    }

    private static JsonObject detailRanking(DetailRankingSnapshot snapshot, int page, int size) {
        JsonObject result = pagination(page, size, snapshot.entries().size());
        result.addProperty("counter", snapshot.counter().id());
        result.addProperty("period", "lifetime");
        number(result, "refreshedAt", snapshot.refreshedAt());
        number(result, "recordedSince", snapshot.recordedSince());
        result.addProperty("totalProfiles", snapshot.totalProfiles());
        result.addProperty("excludedCount", snapshot.excludedCount());
        JsonArray entries = new JsonArray();
        int from = start(page, size, snapshot.entries().size());
        for (int index = from; index < Math.min(snapshot.entries().size(), from + size); index++) {
            RankedDetailPlayer player = snapshot.entries().get(index);
            JsonObject entry = identity(player.profile().uuid(), player.profile().name());
            entry.addProperty("rank", player.rank());
            entry.addProperty("position", player.position());
            number(entry, "value", player.value());
            number(entry, "gapToNext", player.gapToNext());
            number(entry, "recordedSince", player.profile().recordedSince(snapshot.counter()));
            number(entry, "lastObservedAt", player.profile().lastObservedAt(snapshot.counter()));
            entries.add(entry);
        }
        result.add("entries", entries);
        return result;
    }

    private JsonArray publicDetails(DetailSnapshot details) {
        JsonArray counters = new JsonArray();
        for (DetailKey key : DetailKey.values()) {
            if (details.value(key) == 0) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", key.id());
            entry.addProperty("name", key.displayName());
            number(entry, "value", details.value(key));
            counters.add(entry);
        }
        return counters;
    }

    private JsonArray publicBests(PersonalBestSnapshot bests) {
        JsonArray records = new JsonArray();
        List<PersonalBest> ordered = new ArrayList<>(bests.records());
        ordered.sort(Comparator.comparing(PersonalBest::period).thenComparing(PersonalBest::statistic));
        for (PersonalBest best : ordered) {
            JsonObject entry = new JsonObject();
            entry.addProperty("period", best.period().id());
            entry.addProperty("stat", best.statistic().id());
            entry.addProperty("value", best.value());
            entry.addProperty("formatted", best.statistic().format(best.value()));
            records.add(entry);
        }
        return records;
    }

    private synchronized List<DashboardDataPlayer> directory() {
        long now = clock.getAsLong();
        if (directory == null || now - directoryCapturedAt >= DIRECTORY_TTL_NANOS) {
            List<DashboardDataPlayer> updated = new ArrayList<>(profiles.get());
            updated.sort(PLAYER_ORDER);
            directory = List.copyOf(updated);
            directoryCapturedAt = now;
        }
        return directory;
    }

    private JsonObject profile(PlayerStatsSnapshot snapshot, StatsPeriod period) {
        UUID uuid = snapshot.uuid();
        JsonObject result = identity(uuid, snapshot.name());
        number(result, "updatedAt", snapshot.updatedAt());
        number(result, "recordedSince", snapshot.recordedSince());
        result.addProperty("historyZone", statistics.historyZone().getId());
        result.addProperty("period", period.id());
        result.add("statistics", values(snapshot));
        result.add("details", statistics.details(uuid).map(this::details).orElseGet(JsonArray::new));
        Optional<PersonalBestSnapshot> records = statistics.personalBests(uuid);
        result.add("bests", records.map(this::bests).orElseGet(JsonArray::new));
        number(result, "bestTrackingSince", records.map(PersonalBestSnapshot::trackingSince).orElse(0L));
        List<PlayerSession> sessions = new ArrayList<>(statistics.sessions(uuid));
        sessions.sort(Comparator.comparingLong(PlayerSession::startedAt).reversed().thenComparing(PlayerSession::id));
        JsonArray sessionEntries = new JsonArray();
        for (int index = 0; index < Math.min(HISTORY_LIMIT, sessions.size()); index++) {
            sessionEntries.add(session(sessions.get(index)));
        }
        result.add("sessions", sessionEntries);
        result.addProperty("sessionsTotal", sessions.size());
        List<PeriodStatsSnapshot> history = new ArrayList<>(statistics.history(uuid, period));
        history.sort(Comparator.comparingLong((PeriodStatsSnapshot entry) -> entry.window().startedAt()).reversed());
        JsonArray historyEntries = new JsonArray();
        for (int index = 0; index < Math.min(HISTORY_LIMIT, history.size()); index++) {
            PeriodStatsSnapshot entry = history.get(index);
            JsonObject item = new JsonObject();
            item.add("window", window(entry.window()));
            number(item, "recordedSince", entry.recordedSince());
            item.add("statistics", values(entry.profile()));
            historyEntries.add(item);
        }
        result.add("history", historyEntries);
        result.addProperty("historyTotal", history.size());
        result.addProperty("historyLimit", HISTORY_LIMIT);
        return result;
    }

    private JsonArray details(DetailSnapshot snapshot) {
        JsonArray result = new JsonArray();
        for (DetailKey key : DetailKey.values()) {
            if (snapshot.value(key) == 0) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", key.id());
            entry.addProperty("name", key.displayName());
            entry.addProperty("category", key.category().name().toLowerCase(Locale.ROOT));
            number(entry, "value", snapshot.value(key));
            number(entry, "recordedSince", snapshot.recordedSince(key));
            number(entry, "lastObservedAt", snapshot.lastObservedAt(key));
            result.add(entry);
        }
        return result;
    }

    private JsonArray bests(PersonalBestSnapshot snapshot) {
        JsonArray result = new JsonArray();
        List<PersonalBest> ordered = new ArrayList<>(snapshot.records());
        ordered.sort(Comparator.comparing(PersonalBest::period).thenComparing(PersonalBest::statistic));
        for (PersonalBest best : ordered) {
            JsonObject entry = new JsonObject();
            entry.addProperty("period", best.period().id());
            entry.addProperty("stat", best.statistic().id());
            entry.addProperty("value", best.value());
            entry.addProperty("formatted", best.statistic().format(best.value()));
            number(entry, "startedAt", best.startedAt());
            number(entry, "endsAt", best.endsAt());
            number(entry, "recordedSince", best.recordedSince());
            number(entry, "observedAt", best.observedAt());
            entry.addProperty("inProgress", best.inProgress());
            entry.addProperty("recovered", best.recovered());
            result.add(entry);
        }
        return result;
    }

    private static JsonObject participation(ParticipationSnapshot summary) {
        JsonObject result = new JsonObject();
        result.addProperty("period", summary.window().period().id());
        result.add("window", window(summary.window()));
        result.addProperty("historyZone", summary.historyZone().getId());
        number(result, "refreshedAt", summary.refreshedAt());
        result.addProperty("uniquePlayers", summary.uniquePlayers());
        result.addProperty("newPlayers", summary.newPlayers());
        result.addProperty("returningPlayers", summary.returningPlayers());
        result.addProperty("unknownCohortPlayers", summary.unknownCohortPlayers());
        result.addProperty("completedSessions", summary.completedSessions());
        if (summary.medianSessionMillis().isPresent()) {
            result.addProperty("medianSessionMillis", summary.medianSessionMillis().getAsDouble());
        } else {
            result.add("medianSessionMillis", JsonNull.INSTANCE);
        }
        result.addProperty("openSessions", summary.openSessions());
        result.addProperty("interruptedSessions", summary.interruptedSessions());
        result.addProperty("shutdownSessions", summary.shutdownSessions());
        number(result, "earliestObservation", summary.earliestObservation());
        result.addProperty("retentionLimited", summary.retentionLimited());
        return result;
    }

    private static JsonObject ranking(RankingSnapshot snapshot, int page, int size) {
        JsonObject result = pagination(page, size, snapshot.entries().size());
        result.addProperty("stat", snapshot.statistic().id());
        result.addProperty("period", snapshot.period().id());
        result.add("window", window(snapshot.window()));
        result.addProperty("historyZone", snapshot.historyZone().getId());
        number(result, "refreshedAt", snapshot.refreshedAt());
        number(result, "recordedSince", snapshot.recordedSince());
        result.addProperty("totalProfiles", snapshot.totalProfiles());
        result.addProperty("excludedCount", snapshot.excludedCount());
        JsonArray entries = new JsonArray();
        int from = start(page, size, snapshot.entries().size());
        int until = Math.min(snapshot.entries().size(), from + size);
        for (int index = from; index < until; index++) {
            entries.add(rankedPlayer(snapshot.entries().get(index), snapshot.statistic()));
        }
        result.add("entries", entries);
        return result;
    }

    private static JsonArray values(PlayerStatsSnapshot snapshot) {
        JsonArray result = new JsonArray();
        for (StatKey key : StatKey.values()) {
            JsonObject entry = statisticDescriptor(key);
            entry.addProperty("value", snapshot.value(key));
            entry.addProperty("formatted", key.format(snapshot.value(key)));
            result.add(entry);
        }
        return result;
    }

    private static JsonObject statisticDescriptor(StatKey key) {
        JsonObject result = new JsonObject();
        result.addProperty("id", key.id());
        result.addProperty("name", key.displayName());
        result.addProperty("category", key.category());
        return result;
    }

    private static JsonObject session(PlayerSession session) {
        JsonObject result = new JsonObject();
        result.addProperty("id", session.id().toString());
        number(result, "startedAt", session.startedAt());
        number(result, "endedAt", session.endedAt());
        number(result, "checkpointAt", session.checkpointAt());
        number(result, "eligibleMillis", session.eligibleMillis());
        number(result, "activeMillis", session.activeMillis());
        number(result, "afkMillis", session.afkMillis());
        result.addProperty("endReason", session.endReason().name().toLowerCase(Locale.ROOT));
        return result;
    }

    private static JsonObject window(PeriodWindow window) {
        JsonObject result = new JsonObject();
        result.addProperty("period", window.period().id());
        result.addProperty("id", window.id());
        number(result, "startedAt", window.startedAt());
        number(result, "endsAt", window.endsAt());
        return result;
    }

    private static JsonObject identity(UUID uuid, String name) {
        JsonObject result = new JsonObject();
        result.addProperty("uuid", uuid.toString());
        result.addProperty("name", name);
        return result;
    }

    private static JsonObject pagination(int page, int size, int total) {
        JsonObject result = new JsonObject();
        result.addProperty("page", page);
        result.addProperty("size", size);
        result.addProperty("total", total);
        return result;
    }

    private static int start(int page, int size, int total) {
        return (int) Math.min((long) page * size, total);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > PAGE_LIMIT) {
            throw new IllegalArgumentException("Page must be nonnegative and size must be between 1 and 50");
        }
    }

    private static void number(JsonObject target, String name, long value) {
        target.addProperty(name, Long.toString(value));
    }
}
