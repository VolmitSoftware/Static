package com.volmit.staticstats.config;

import art.arcane.volmlib.util.config.ConfigDoc;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.detailed.DetailSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StaticConfig {
    public General general = new General();
    public Tracking tracking = new Tracking();
    public Ranking ranking = new Ranking();
    public History history = new History();
    public Milestones milestones = new Milestones();
    public Seasons seasons = new Seasons();
    public Details details = new Details();
    public HotReload hotReload = new HotReload();
    public Presentation presentation = new Presentation();
    public Debug debug = new Debug();
    public Dashboard dashboard = new Dashboard();

    public StaticConfig copy() {
        StaticConfig copy = new StaticConfig();
        copy.general.enabled = general.enabled;
        copy.general.language = general.language;
        copy.tracking.autosaveSeconds = tracking.autosaveSeconds;
        copy.tracking.leaderboardLimit = tracking.leaderboardLimit;
        copy.tracking.excludedWorlds = new ArrayList<>(tracking.excludedWorlds);
        copy.tracking.trackCreative = tracking.trackCreative;
        copy.tracking.trackSpectator = tracking.trackSpectator;
        copy.ranking.minPvpDeaths = ranking.minPvpDeaths;
        copy.ranking.minMeleeDamageTaken = ranking.minMeleeDamageTaken;
        copy.ranking.minMeleeHitsTaken = ranking.minMeleeHitsTaken;
        copy.ranking.minArrowDamageTaken = ranking.minArrowDamageTaken;
        copy.ranking.minArrowHitsTaken = ranking.minArrowHitsTaken;
        copy.ranking.minArrowsFired = ranking.minArrowsFired;
        copy.ranking.minOnlineHours = ranking.minOnlineHours;
        copy.history.timeZone = history.timeZone;
        copy.history.afkSeconds = history.afkSeconds;
        copy.history.retainedDays = history.retainedDays;
        copy.history.retainedWeeks = history.retainedWeeks;
        copy.history.retainedMonths = history.retainedMonths;
        copy.history.sessionsPerPlayer = history.sessionsPerPlayer;
        copy.milestones.enabled = milestones.enabled;
        copy.milestones.notifyPlayers = milestones.notifyPlayers;
        copy.milestones.thresholds = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : milestones.thresholds.entrySet()) {
            copy.milestones.thresholds.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        copy.seasons.events = new ArrayList<>();
        for (Season event : seasons.events) {
            copy.seasons.events.add(event.copy());
        }
        copy.details.enabled = details.enabled;
        copy.details.selectedKeys = new ArrayList<>(details.selectedKeys);
        copy.hotReload.enabled = hotReload.enabled;
        copy.hotReload.pollIntervalMillis = hotReload.pollIntervalMillis;
        copy.hotReload.cooldownMillis = hotReload.cooldownMillis;
        copy.hotReload.notifyOperators = hotReload.notifyOperators;
        copy.presentation.splashScreen = presentation.splashScreen;
        copy.presentation.commandSounds = presentation.commandSounds;
        copy.debug.uploadEnabled = debug.uploadEnabled;
        copy.dashboard.enabled = dashboard.enabled;
        copy.dashboard.port = dashboard.port;
        copy.dashboard.publicOrigin = dashboard.publicOrigin;
        copy.dashboard.publicProfilesEnabled = dashboard.publicProfilesEnabled;
        return copy;
    }

    public static final class Dashboard {
        @ConfigDoc(value = "Enable the read-only web listener on 127.0.0.1.", impact = "Operator reports require the token stored separately in dashboard/access-token. Public pages require their separate setting and player opt-in.")
        public boolean enabled = false;
        @ConfigDoc(value = "Loopback HTTP listener port.", impact = "Use 1024 through 65535. Failed listener changes retain the working configuration.")
        public int port = 8791;
        @ConfigDoc(value = "Browser origin permitted to access the dashboard.", impact = "Use an HTTPS reverse proxy for remote access. Supply scheme, hostname and optional port only, without a trailing slash.")
        public String publicOrigin = "http://127.0.0.1:8791";
        @ConfigDoc(value = "Allow public pages for players who explicitly choose Public visibility.", impact = "Requires dashboard.enabled. Publishes lifetime totals and record values, without sessions or activity dates. Server and Private profiles remain unavailable on the web.")
        public boolean publicProfilesEnabled = false;
    }

    public static final class Details {
        @ConfigDoc(value = "Record selected lifetime activity breakdowns.", impact = "Uses global tracking filters. Disabling collection retains recorded totals.")
        public boolean enabled = true;
        @ConfigDoc(value = "Selected detailed counter identifiers.", impact = "At most 64 unique catalogue identifiers. Removed counters keep their data; re-enabling does not reconstruct missing activity.")
        public List<String> selectedKeys = new ArrayList<>(DetailSettings.defaults().selectedKeys().stream()
                .sorted().map(DetailKey::id).toList());
    }

    public static final class General {
        @ConfigDoc(value = "Record player statistics.", impact = "Existing statistics remain readable while tracking is disabled.")
        public boolean enabled = true;
        @ConfigDoc(value = "Default language for players without a personal language selection.", impact = "Only English is generated locally. Other catalogue languages download when selected; missing entries use built-in English.")
        public String language = "en_US";
    }

    public static final class Tracking {
        @ConfigDoc(value = "Seconds between persistent statistics saves.", impact = "Accepted range is 10 through 3600 seconds. Disconnect and shutdown also save statistics.")
        public int autosaveSeconds = 60;
        @ConfigDoc(value = "Maximum entries displayed on a leaderboard page.", impact = "Accepted range is 1 through 25.")
        public int leaderboardLimit = 10;
        @ConfigDoc(value = "World names excluded from event-based statistics.", impact = "Names are compared without case sensitivity.")
        public List<String> excludedWorlds = new ArrayList<>();
        @ConfigDoc(value = "Record gameplay statistics while players are in creative mode.", impact = "Disabled by default to prevent creative actions inflating gameplay totals.")
        public boolean trackCreative = false;
        @ConfigDoc(value = "Record gameplay statistics while players are spectators.", impact = "Disabled by default.")
        public boolean trackSpectator = false;
    }

    public static final class Ranking {
        @ConfigDoc(value = "Minimum PvP deaths for kill/death rankings.", impact = "Rankings require a positive denominator even when the minimum is zero. Profile totals remain visible.")
        public double minPvpDeaths = 10;
        @ConfigDoc(value = "Minimum melee damage taken for melee damage ratio rankings.", impact = "Accepted range is zero through one billion; fractional thresholds are allowed.")
        public double minMeleeDamageTaken = 100;
        @ConfigDoc(value = "Minimum melee hits taken for melee hit ratio rankings.", impact = "Accepted range is zero through one billion; fractional thresholds are allowed.")
        public double minMeleeHitsTaken = 25;
        @ConfigDoc(value = "Minimum arrow damage taken for arrow damage ratio rankings.", impact = "Accepted range is zero through one billion; fractional thresholds are allowed.")
        public double minArrowDamageTaken = 100;
        @ConfigDoc(value = "Minimum arrow hits taken for arrow hit ratio rankings.", impact = "Accepted range is zero through one billion; fractional thresholds are allowed.")
        public double minArrowHitsTaken = 25;
        @ConfigDoc(value = "Minimum fired arrows for arrow accuracy rankings.", impact = "Accepted range is zero through one billion; fractional thresholds are allowed.")
        public double minArrowsFired = 50;
        @ConfigDoc(value = "Minimum eligible online hours for votes per online day rankings.", impact = "Accepted range is zero through one billion hours. This does not measure AFK-filtered time.")
        public double minOnlineHours = 1;
    }

    public static final class History {
        @ConfigDoc(value = "Timezone used for calendar statistics.", impact = "Choose an IANA timezone before recording history. Timezone changes require a restart and are rejected when persisted history uses another zone. Weeks begin Monday.")
        public String timeZone = "UTC";
        @ConfigDoc(value = "Seconds without observed activity before time counts as AFK.", impact = "Accepted range is 5 through 86400 seconds. Active and AFK time both remain part of eligible online time.")
        public int afkSeconds = 300;
        @ConfigDoc(value = "Daily periods retained per player, including the current day.", impact = "Accepted range is 1 through 366. Increasing retention cannot restore pruned history.")
        public int retainedDays = 35;
        @ConfigDoc(value = "Weekly periods retained per player, including the current week.", impact = "Accepted range is 1 through 104.")
        public int retainedWeeks = 12;
        @ConfigDoc(value = "Monthly periods retained per player, including the current month.", impact = "Accepted range is 1 through 120.")
        public int retainedMonths = 12;
        @ConfigDoc(value = "Recent sessions retained per player.", impact = "Accepted range is 1 through 100. Interrupted sessions end at their last saved checkpoint.")
        public int sessionsPerPlayer = 20;
    }

    public static final class Milestones {
        @ConfigDoc(value = "Record cosmetic milestone completions from lifetime statistics.", impact = "Existing totals qualify silently when definitions are installed. Completion dates record when Static detected the threshold.")
        public boolean enabled = true;
        @ConfigDoc(value = "Send private messages for newly crossed milestone thresholds.", impact = "Messages follow a successful statistics save. Existing completions do not replay on reload or login.")
        public boolean notifyPlayers = true;
        @ConfigDoc(value = "Lifetime thresholds by base statistic ID.", impact = "Use ascending positive values, up to 16 per statistic. Duration values use milliseconds; distance uses blocks. Derived statistics cannot be milestone targets.")
        public Map<String, List<Double>> thresholds = defaults();

        private static Map<String, List<Double>> defaults() {
            Map<String, List<Double>> thresholds = new LinkedHashMap<>();
            thresholds.put("blocks_broken", new ArrayList<>(List.of(100D, 1000D, 10000D)));
            thresholds.put("blocks_placed", new ArrayList<>(List.of(100D, 1000D, 10000D)));
            thresholds.put("active_time", new ArrayList<>(List.of(3_600_000D, 36_000_000D, 360_000_000D)));
            return thresholds;
        }
    }

    public static final class Seasons {
        @ConfigDoc(value = "Named competitions with explicit start and exclusive end instants.", impact = "Rules freeze when a season starts. Started seasons and results remain stored if removed here. At most 64 retained seasons and 8 overlapping windows are supported.")
        public List<Season> events = new ArrayList<>();
    }

    public static final class Season {
        public String id = "";
        public String name = "";
        public String startsAt = "";
        public String endsAt = "";
        public String statistic = "blocks_broken";
        public double minimumSample = 0;
        public List<String> excludedWorlds = new ArrayList<>();
        public boolean trackCreative = false;
        public boolean trackSpectator = false;
        public int afkSeconds = 300;

        public Season copy() {
            Season copy = new Season();
            copy.id = id;
            copy.name = name;
            copy.startsAt = startsAt;
            copy.endsAt = endsAt;
            copy.statistic = statistic;
            copy.minimumSample = minimumSample;
            copy.excludedWorlds = new ArrayList<>(excludedWorlds);
            copy.trackCreative = trackCreative;
            copy.trackSpectator = trackSpectator;
            copy.afkSeconds = afkSeconds;
            return copy;
        }
    }

    public static final class HotReload {
        @ConfigDoc(value = "Apply stable configuration and installed language file changes automatically.", impact = "Invalid configuration edits retain the last good runtime settings.")
        public boolean enabled = true;
        @ConfigDoc(value = "Milliseconds between filesystem checks.", impact = "Accepted range is 250 through 60000 milliseconds.")
        public long pollIntervalMillis = 1000L;
        @ConfigDoc(value = "Quiet period before applying an edited file.", impact = "Accepted range is 250 through 60000 milliseconds.")
        public long cooldownMillis = 1500L;
        @ConfigDoc(value = "Notify operators after a configuration hot reload.", impact = "Feedback appears in the action bar.")
        public boolean notifyOperators = true;
    }

    public static final class Presentation {
        @ConfigDoc(value = "Print the Static splash when the plugin starts.", impact = "Reload does not replay the splash.")
        public boolean splashScreen = true;
        @ConfigDoc(value = "Play brief sounds after player commands.", impact = "Console output is unchanged.")
        public boolean commandSounds = true;
    }

    public static final class Debug {
        @ConfigDoc(value = "Permit diagnostic uploads to the public mclo.gs service.", impact = "Reports are always saved locally before upload. Use /static debug dump upload=false for a local report.")
        public boolean uploadEnabled = true;
    }
}
