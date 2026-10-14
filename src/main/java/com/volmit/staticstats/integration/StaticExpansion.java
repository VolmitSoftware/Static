package com.volmit.staticstats.integration;

import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.localization.StatsFormatter;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankedPlayer;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.RankingQualification;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StaticExpansion extends PlaceholderExpansion {
    private static final Pattern TOP = Pattern.compile("top_([a-z_]+)_([1-9][0-9]{0,8})_(name|uuid|value|value_formatted|rank)");
    private static final Pattern BOARD = Pattern.compile("ranking_([a-z_]+)_(ready|updated|size)");
    private static final Pattern QUALIFICATION = Pattern.compile("qualification_([a-z_]+)_(qualified|numerator|denominator|minimum|remaining)");
    private static final List<String> PERSONAL = List.of("rank_", "position_", "gap_");
    private final StaticPlugin plugin;

    public StaticExpansion(StaticPlugin plugin) { this.plugin = plugin; }

    @Override
    public String getIdentifier() { return "static"; }
    @Override
    public String getAuthor() { return "VolmitSoftware"; }
    @Override
    public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String parameters) {
        if (parameters == null) { return null; }
        String key = parameters.toLowerCase(Locale.ROOT);
        StatsPeriod period = StatsPeriod.LIFETIME;
        for (StatsPeriod candidate : StatsPeriod.values()) {
            String prefix = candidate.name().toLowerCase(Locale.ROOT) + "_";
            if (key.startsWith(prefix)) {
                period = candidate;
                key = key.substring(prefix.length());
                break;
            }
        }
        UUID playerId = player == null ? null : player.getUniqueId();
        Matcher top = TOP.matcher(key);
        if (top.matches()) {
            return top(top, playerId, period);
        }
        Matcher board = BOARD.matcher(key);
        if (board.matches()) {
            return board(board, period);
        }
        if (playerId == null) { return null; }
        if (!plugin.getProfileVisibility().isServerVisible(playerId)) { return ""; }
        Matcher qualification = QUALIFICATION.matcher(key);
        if (qualification.matches()) {
            return qualification(qualification, playerId, period);
        }
        if (key.equals("recorded_since") || key.equals("recorded_since_formatted")) {
            long since = plugin.getStatsService().snapshot(playerId, period).map(PlayerStatsSnapshot::recordedSince).orElse(0L);
            return key.endsWith("_formatted") ? StatsFormatter.dateForPlayer(since, plugin.getLanguageService(), playerId)
                    : Long.toString(since);
        }
        boolean formatted = key.endsWith("_formatted");
        if (formatted) { key = key.substring(0, key.length() - "_formatted".length()); }
        for (String operation : PERSONAL) {
            if (key.startsWith(operation)) {
                StatKey stat = statistic(key.substring(operation.length()));
                if (stat == null || formatted && !operation.equals("gap_")) { return null; }
                Optional<RankedPlayer> entry = plugin.getStatsService().cachedRanking(stat, period).map(plugin.getProfileVisibility()::filter).flatMap(result -> result.find(playerId));
                if (entry.isEmpty()) { return ""; }
                return switch (operation) {
                    case "rank_" -> Integer.toString(entry.get().rank());
                    case "position_" -> Integer.toString(entry.get().position());
                    default -> display(stat, entry.get().gapToNext(), formatted, playerId);
                };
            }
        }
        StatKey stat = statistic(key);
        if (stat == null) { return null; }
        double value = plugin.getStatsService().snapshot(playerId, period).map(profile -> profile.value(stat)).orElse(0D);
        return display(stat, value, formatted, playerId);
    }

    private String top(Matcher request, UUID playerId, StatsPeriod period) {
        StatKey stat = statistic(request.group(1));
        if (stat == null) { return null; }
        int position = Integer.parseInt(request.group(2));
        Optional<RankingSnapshot> ranking = plugin.getStatsService().cachedRanking(stat, period).map(plugin.getProfileVisibility()::filter);
        if (ranking.isEmpty() || position > ranking.get().entries().size()) { return ""; }
        RankedPlayer entry = ranking.get().entries().get(position - 1);
        return switch (request.group(3)) {
            case "name" -> entry.profile().name();
            case "uuid" -> entry.profile().uuid().toString();
            case "rank" -> Integer.toString(entry.rank());
            case "value_formatted" -> display(stat, entry.value(), true, playerId);
            default -> raw(entry.value());
        };
    }

    private String board(Matcher request, StatsPeriod period) {
        StatKey stat = statistic(request.group(1));
        if (stat == null) { return null; }
        Optional<RankingSnapshot> ranking = plugin.getStatsService().cachedRanking(stat, period).map(plugin.getProfileVisibility()::filter);
        return switch (request.group(2)) {
            case "ready" -> Boolean.toString(ranking.isPresent());
            case "updated" -> ranking.map(result -> Long.toString(result.refreshedAt())).orElse("0");
            default -> ranking.map(result -> Integer.toString(result.entries().size())).orElse("0");
        };
    }

    private String display(StatKey stat, double value, boolean formatted, UUID playerId) {
        return formatted ? StatsFormatter.formatForPlayer(stat, value, plugin.getLanguageService(), playerId) : raw(value);
    }

    private String qualification(Matcher request, UUID playerId, StatsPeriod period) {
        StatKey stat = statistic(request.group(1));
        if (stat == null) { return null; }
        Optional<RankingQualification> qualification = plugin.getStatsService().cachedRanking(stat, period).map(plugin.getProfileVisibility()::filter)
                .flatMap(snapshot -> snapshot.qualification(playerId));
        if (qualification.isEmpty()) { return ""; }
        RankingQualification result = qualification.get();
        return switch (request.group(2)) {
            case "qualified" -> Boolean.toString(result.qualified());
            case "numerator" -> raw(result.numerator());
            case "denominator" -> raw(result.denominator());
            case "minimum" -> raw(result.minimum());
            default -> raw(Math.max(0, result.minimum() - result.denominator()));
        };
    }

    private static String raw(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static StatKey statistic(String id) {
        try {
            return StatKey.parse(id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
