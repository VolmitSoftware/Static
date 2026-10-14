package com.volmit.staticstats.stats;

import java.util.Locale;

public enum StatKey {
    BLOCKS_BROKEN("Blocks broken", "building"),
    BLOCKS_PLACED("Blocks placed", "building"),
    ONLINE_TIME("Time online", "connections"),
    ACTIVE_TIME("Active time", "connections"),
    AFK_TIME("AFK time", "connections"),
    PVP_KILLS("Player kills", "combat"),
    PVP_DEATHS("Player combat deaths", "combat"),
    DEATHS("All deaths", "combat"),
    MOB_KILLS("Mob kills", "combat"),
    MELEE_DAMAGE_DEALT("Melee damage dealt", "combat"),
    MELEE_DAMAGE_TAKEN("Melee damage taken", "combat"),
    MELEE_HITS_DEALT("Melee hits dealt", "combat"),
    MELEE_HITS_TAKEN("Melee hits taken", "combat"),
    CRITICAL_ARROWS("Critical arrows", "archery"),
    ARROWS_FIRED("Arrows fired", "archery"),
    NONCRITICAL_ARROWS("Noncritical arrows", "archery"),
    ARROW_HITS_DEALT("Arrow hits dealt", "archery"),
    ARROW_HITS_TAKEN("Arrow hits taken", "archery"),
    ARROW_DAMAGE_DEALT("Arrow damage dealt", "archery"),
    ARROW_DAMAGE_TAKEN("Arrow damage taken", "archery"),
    CHAT_MESSAGES("Chat messages", "social"),
    COMMANDS_EXECUTED("Commands submitted", "social"),
    DISTANCE_WALKED("Walked", "movement"),
    DISTANCE_SNEAKED("Sneaked", "movement"),
    DISTANCE_SPRINTED("Sprinted", "movement"),
    DISTANCE_TRAVELED("Traveled", "movement"),
    DISTANCE_FLOWN("Flown", "movement"),
    DISTANCE_FALLEN("Fallen", "movement"),
    DISTANCE_SWUM("Swum", "movement"),
    VOTES("Votes", "social"),
    JOINS("Joins", "connections"),
    QUITS("Quits", "connections"),
    KILL_DEATH_RATIO("Kill/death ratio", "combat"),
    MELEE_DAMAGE_RATIO("Melee damage ratio", "combat"),
    MELEE_HIT_RATIO("Melee hit ratio", "combat"),
    ARROW_DAMAGE_RATIO("Arrow damage ratio", "archery"),
    ARROW_HIT_RATIO("Arrow hit ratio", "archery"),
    ARROWS_MISSED("Arrows without a player hit", "archery"),
    ARROW_ACCURACY("Player hit accuracy", "archery"),
    VOTES_PER_DAY("Votes per online day", "social");

    private final String displayName;
    private final String category;

    StatKey(String displayName, String category) {
        this.displayName = displayName;
        this.category = category;
    }

    public static StatKey parse(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return displayName;
    }

    public String category() {
        return category;
    }

    public boolean derived() {
        return ordinal() >= KILL_DEATH_RATIO.ordinal();
    }

    public double value(PlayerStatsSnapshot snapshot) {
        return switch (this) {
            case KILL_DEATH_RATIO -> ratio(snapshot, PVP_KILLS, PVP_DEATHS);
            case MELEE_DAMAGE_RATIO -> ratio(snapshot, MELEE_DAMAGE_DEALT, MELEE_DAMAGE_TAKEN);
            case MELEE_HIT_RATIO -> ratio(snapshot, MELEE_HITS_DEALT, MELEE_HITS_TAKEN);
            case ARROW_DAMAGE_RATIO -> ratio(snapshot, ARROW_DAMAGE_DEALT, ARROW_DAMAGE_TAKEN);
            case ARROW_HIT_RATIO -> ratio(snapshot, ARROW_HITS_DEALT, ARROW_HITS_TAKEN);
            case ARROWS_MISSED -> Math.max(0, snapshot.baseValue(ARROWS_FIRED) - snapshot.baseValue(ARROW_HITS_DEALT));
            case ARROW_ACCURACY -> Math.min(1, ratio(snapshot, ARROW_HITS_DEALT, ARROWS_FIRED));
            case VOTES_PER_DAY -> divide(snapshot.baseValue(VOTES), snapshot.baseValue(ONLINE_TIME) / 86_400_000D);
            default -> snapshot.baseValue(this);
        };
    }

    public String format(double value) {
        double safeValue = Double.isFinite(value) ? Math.max(0, value) : 0;
        if (this == ONLINE_TIME || this == ACTIVE_TIME || this == AFK_TIME) {
            long seconds = (long) (safeValue / 1000);
            long days = seconds / 86_400;
            long hours = seconds % 86_400 / 3_600;
            long minutes = seconds % 3_600 / 60;
            return days > 0 ? days + "d " + hours + "h " + minutes + "m"
                    : hours > 0 ? hours + "h " + minutes + "m"
                    : minutes > 0 ? minutes + "m " + seconds % 60 + "s" : seconds + "s";
        }
        if (this == ARROW_ACCURACY) {
            return String.format(Locale.ROOT, "%.1f%%", Math.min(1, safeValue) * 100);
        }
        if (category.equals("movement")) {
            return safeValue >= 1000 ? String.format(Locale.ROOT, "%,.2f km", safeValue / 1000)
                    : String.format(Locale.ROOT, "%,.1f m", safeValue);
        }
        if (derived() && this != ARROWS_MISSED || name().contains("DAMAGE")) {
            return String.format(Locale.ROOT, "%,.2f", safeValue);
        }
        return String.format(Locale.ROOT, "%,.0f", safeValue);
    }

    private static double ratio(PlayerStatsSnapshot snapshot, StatKey numerator, StatKey denominator) {
        double divisor = snapshot.baseValue(denominator);
        return divide(snapshot.baseValue(numerator), divisor);
    }

    private static double divide(double numerator, double denominator) {
        return denominator <= 0 ? 0 : Math.min(Double.MAX_VALUE, numerator / denominator);
    }
}
