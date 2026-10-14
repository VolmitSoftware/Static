package com.volmit.staticstats.config;

import com.volmit.staticstats.stats.StatKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void validatesAndPersistsTheInstalledSnapshot() throws IOException {
        ConfigRepository repository = new ConfigRepository(directory.toFile());
        StaticConfig edited = repository.load().source();
        edited.tracking.autosaveSeconds = 0;
        edited.tracking.leaderboardLimit = 100;
        edited.tracking.excludedWorlds = List.of("Creative", " creative ", "");
        repository.save(edited);
        RuntimeConfig loaded = repository.load().runtime();
        assertThat(loaded.autosaveSeconds()).isEqualTo(10);
        assertThat(loaded.leaderboardLimit()).isEqualTo(25);
        assertThat(loaded.excludedWorlds()).containsExactly("creative");
        assertThat(loaded.trackCreative()).isFalse();
        assertThat(Files.readString(directory.resolve("config.toml"))).contains("[tracking]");
    }

    @Test
    void failedReloadRetainsRuntimeAndInvalidFile() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        service.update(config -> config.tracking.autosaveSeconds = 120);
        String invalid = "[tracking\nautosaveSeconds = nope";
        Files.writeString(directory.resolve("config.toml"), invalid);
        assertThatThrownBy(service::prepareFromDisk).isInstanceOf(IOException.class);
        assertThat(service.runtime().autosaveSeconds()).isEqualTo(120);
        assertThat(Files.readString(directory.resolve("config.toml"))).isEqualTo(invalid);
    }

    @Test
    void runtimeSourceCannotBeMutatedByCallers() throws IOException {
        RuntimeConfig runtime = new ConfigRepository(directory.toFile()).load().runtime();
        runtime.source().general.enabled = false;
        assertThat(runtime.source().general.enabled).isTrue();
        assertThat(runtime.enabled()).isTrue();
    }

    @Test
    void qualificationThresholdsPersistClampAndConvertHoursWithoutMutatingInstalledRules() throws IOException {
        ConfigRepository repository = new ConfigRepository(directory.toFile());
        StaticConfig edited = repository.load().source();
        edited.ranking.minPvpDeaths = 10.5;
        edited.ranking.minArrowsFired = -1;
        edited.ranking.minArrowHitsTaken = 2_000_000_000D;
        edited.ranking.minOnlineHours = 1.25;
        repository.save(edited);
        RuntimeConfig loaded = repository.load().runtime();
        assertThat(loaded.rankingRules().minimums().get(StatKey.KILL_DEATH_RATIO)).isEqualTo(10.5);
        assertThat(loaded.rankingRules().minimums().get(StatKey.ARROW_ACCURACY)).isZero();
        assertThat(loaded.rankingRules().minimums().get(StatKey.ARROW_HIT_RATIO)).isEqualTo(1_000_000_000D);
        assertThat(loaded.rankingRules().minimums().get(StatKey.VOTES_PER_DAY)).isEqualTo(4_500_000D);
        loaded.source().ranking.minPvpDeaths = 99;
        assertThat(loaded.source().ranking.minPvpDeaths).isEqualTo(10.5);
        assertThat(Files.readString(directory.resolve("config.toml"))).contains("[ranking]");
    }

    @Test
    void nonFiniteQualificationEditRetainsInstalledRulesAndFile() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        String previous = Files.readString(directory.resolve("config.toml"));
        assertThatThrownBy(() -> service.update(config -> config.ranking.minPvpDeaths = Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.runtime().source().ranking.minPvpDeaths).isEqualTo(10);
        assertThat(Files.readString(directory.resolve("config.toml"))).isEqualTo(previous);
    }

    @Test
    void historyBoundsAndTimezonePersistWithoutMutatingSource() throws IOException {
        ConfigRepository repository = new ConfigRepository(directory.toFile());
        StaticConfig edited = repository.load().source();
        edited.history.timeZone = " America/Denver ";
        edited.history.afkSeconds = 0;
        edited.history.retainedDays = 999;
        edited.history.retainedWeeks = 0;
        edited.history.retainedMonths = 999;
        edited.history.sessionsPerPlayer = 0;
        repository.save(edited);
        RuntimeConfig loaded = repository.load().runtime();
        assertThat(loaded.historySettings().zone().getId()).isEqualTo("America/Denver");
        assertThat(loaded.historySettings().afkMillis()).isEqualTo(5000);
        assertThat(loaded.source().history.retainedDays).isEqualTo(366);
        assertThat(loaded.source().history.retainedWeeks).isEqualTo(1);
        assertThat(loaded.source().history.retainedMonths).isEqualTo(120);
        assertThat(loaded.source().history.sessionsPerPlayer).isEqualTo(1);
        assertThat(edited.history.timeZone).isEqualTo(" America/Denver ");
    }

    @Test
    void rejectedHistoryPolicyDoesNotWriteOrInstallCandidate() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        String previous = Files.readString(directory.resolve("config.toml"));
        service.setValidator(candidate -> {
            if (!candidate.historySettings().zone().getId().equals("UTC")) {
                throw new IllegalArgumentException("History timezone is fixed");
            }
        });
        assertThatThrownBy(() -> service.update(config -> config.history.timeZone = "America/Denver"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.runtime().historySettings().zone().getId()).isEqualTo("UTC");
        assertThat(Files.readString(directory.resolve("config.toml"))).isEqualTo(previous);
        StaticConfig candidate = service.editableCopy();
        candidate.history.timeZone = "America/Denver";
        ConfigRepository.PreparedConfig prepared = new ConfigRepository.PreparedConfig(candidate, RuntimeConfig.from(candidate), "");
        assertThatThrownBy(() -> service.install(prepared)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.runtime().historySettings().zone().getId()).isEqualTo("UTC");
    }

    @Test
    void milestoneLaddersRoundTripAndRemainIndependentOfEditableCopies() throws IOException {
        ConfigRepository repository = new ConfigRepository(directory.toFile());
        StaticConfig edited = repository.load().source();
        edited.milestones.enabled = false;
        edited.milestones.notifyPlayers = false;
        edited.milestones.thresholds.clear();
        edited.milestones.thresholds.put("distance_walked", List.of(2.5, 100.0));
        repository.save(edited);
        RuntimeConfig loaded = repository.load().runtime();
        assertThat(loaded.milestoneSettings().enabled()).isFalse();
        assertThat(loaded.milestoneNotifications()).isFalse();
        assertThat(loaded.milestoneSettings().thresholds()).containsOnlyKeys(StatKey.DISTANCE_WALKED);
        assertThat(loaded.milestoneSettings().thresholds().get(StatKey.DISTANCE_WALKED)).containsExactly(2.5, 100.0);
        StaticConfig copy = loaded.source();
        copy.milestones.thresholds.get("distance_walked").add(200.0);
        assertThat(loaded.source().milestones.thresholds.get("distance_walked")).containsExactly(2.5, 100.0);
        assertThat(Files.readString(directory.resolve("config.toml"))).contains("[milestones.thresholds]", "distance_walked");
    }

    @Test
    void invalidMilestoneLaddersNeverReplaceInstalledConfiguration() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        String previous = Files.readString(directory.resolve("config.toml"));
        for (String statistic : List.of("unknown", "arrow_accuracy", "BLOCKS_BROKEN")) {
            assertThatThrownBy(() -> service.update(config -> config.milestones.thresholds.put(statistic, List.of(1D))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.readString(directory.resolve("config.toml"))).isEqualTo(previous);
        }
        for (List<Double> ladder : List.of(List.of(0D), List.of(-1D), List.of(Double.NaN),
                List.of(Double.POSITIVE_INFINITY), List.of(2D, 1D), List.of(1D, 1D))) {
            assertThatThrownBy(() -> service.update(config -> config.milestones.thresholds.put("votes", ladder)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.readString(directory.resolve("config.toml"))).isEqualTo(previous);
        }
        assertThat(service.runtime().milestoneSettings().thresholds()).doesNotContainKey(StatKey.VOTES);
    }

    @Test
    void emptyMilestoneLaddersPersistWithoutRestoringDefaults() throws IOException {
        ConfigRepository repository = new ConfigRepository(directory.toFile());
        StaticConfig edited = repository.load().source();
        edited.milestones.thresholds.clear();
        repository.save(edited);
        assertThat(repository.load().runtime().milestoneSettings().thresholds()).isEmpty();
    }

    @Test
    void seasonDefinitionsRoundTripWithIndependentRuleCopies() throws IOException {
        ConfigRepository repository = new ConfigRepository(directory.toFile());
        StaticConfig edited = repository.load().source();
        StaticConfig.Season season = new StaticConfig.Season();
        season.id = "autumn";
        season.name = "Autumn Building";
        season.startsAt = "2027-10-01T00:00:00Z";
        season.endsAt = "2027-11-01T00:00:00Z";
        season.excludedWorlds = List.of(" Creative ", "creative");
        edited.seasons.events.add(season);
        repository.save(edited);
        RuntimeConfig loaded = repository.load().runtime();
        assertThat(loaded.seasonSettings().events()).hasSize(1);
        assertThat(loaded.seasonSettings().events().getFirst().id()).isEqualTo("autumn");
        assertThat(loaded.seasonSettings().events().getFirst().rules().excludedWorlds()).containsExactly("creative");
        StaticConfig copy = loaded.source();
        copy.seasons.events.getFirst().name = "Changed";
        copy.seasons.events.getFirst().excludedWorlds.add("other");
        assertThat(loaded.source().seasons.events.getFirst().name).isEqualTo("Autumn Building");
        assertThat(loaded.source().seasons.events.getFirst().excludedWorlds).containsExactly("creative");
        assertThat(Files.readString(directory.resolve("config.toml"))).contains("[[seasons.events]]");
    }

    @Test
    void invalidSeasonDefinitionLeavesInstalledFileAndRuntimeUnchanged() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        String previous = Files.readString(directory.resolve("config.toml"));
        StaticConfig.Season season = new StaticConfig.Season();
        season.id = "autumn";
        season.name = "Autumn";
        season.startsAt = "2027-10-01T00:00:00Z";
        season.endsAt = "2027-09-01T00:00:00Z";
        assertThatThrownBy(() -> service.update(config -> config.seasons.events.add(season)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.runtime().seasonSettings().events()).isEmpty();
        assertThat(Files.readString(directory.resolve("config.toml"))).isEqualTo(previous);
    }

    @Test
    void detailedSelectionRoundTripsAndRejectsInvalidIdentifiersWithoutReplacingFile() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        service.update(config -> {
            config.details.enabled = false;
            config.details.selectedKeys = List.of("mined_diamond_ore", "crafted_items");
        });
        RuntimeConfig loaded = service.prepareFromDisk().runtime();
        assertThat(loaded.detailSettings().enabled()).isFalse();
        assertThat(loaded.detailSettings().selectedKeys()).hasSize(2);
        StaticConfig copy = service.editableCopy();
        copy.details.selectedKeys.clear();
        assertThat(service.editableCopy().details.selectedKeys).hasSize(2);
        String previous = Files.readString(directory.resolve("config.toml"));
        for (List<String> invalid : List.of(List.of("unknown"), List.of("MINED_DIAMOND_ORE"),
                List.of("crafted_items", "crafted_items"))) {
            assertThatThrownBy(() -> service.update(config -> config.details.selectedKeys = invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.readString(directory.resolve("config.toml"))).isEqualTo(previous);
        }
        service.update(config -> config.details.selectedKeys = List.of());
        assertThat(service.prepareFromDisk().runtime().detailSettings().selectedKeys()).isEmpty();
    }
}
