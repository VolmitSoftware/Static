package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.privacy.VisibilityFixtures;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.presentation.PresentationService;
import com.volmit.staticstats.season.SeasonDefinition;
import com.volmit.staticstats.season.SeasonProfileSnapshot;
import com.volmit.staticstats.season.SeasonRules;
import com.volmit.staticstats.season.SeasonSnapshot;
import com.volmit.staticstats.season.SeasonStatus;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.RankingSnapshot;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeasonsGuiTest {
    @TempDir
    Path directory;

    @Test
    void archivedProgressUsesTheEventQualificationAndExplicitCoverageDates() {
        List<String> messages = profile(false);
        assertTrue(messages.stream().anyMatch(line -> line.contains("Archived results")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("rules and totals are frozen")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("Ranking minimum · Player combat deaths:") && line.endsWith("2")));
        assertTrue(messages.contains("Qualified for this ranking"));
    }

    @Test
    void anotherPlayersSeasonProgressNeedsTheSeparatePermission() {
        assertTrue(profile(true).isEmpty());
    }

    @Test
    void archivedRankingsDoNotRefreshUsingTheCurrentCalendarOrGlobalRules() {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        ConfigService config = mock(ConfigService.class);
        CommandSender sender = mock(CommandSender.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getConfigService()).thenReturn(config);
        when(config.runtime()).thenReturn(RuntimeConfig.from(new StaticConfig()));
        when(sender.hasPermission("static.seasons")).thenReturn(true);
        SeasonRules rules = rules();
        PlayerStatsSnapshot profile = new PlayerStatsSnapshot(UUID.randomUUID(), "River", Map.of(StatKey.PVP_KILLS, 4D, StatKey.PVP_DEATHS, 2D), 1, 1);
        RankingSnapshot ranking = RankingSnapshot.create(StatKey.KILL_DEATH_RATIO, 1, List.of(profile), rules.rankingRules(),
                StatsPeriod.DAY.window(1, ZoneOffset.UTC), ZoneOffset.UTC);
        List<String> messages = new ArrayList<>();
        LanguageService language = new LanguageService(directory.toFile(), plugin.getLogger());
        try (MockedConstruction<ProfilePreferences> preferences = mockConstruction(ProfilePreferences.class);
             MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class)) {
            when(plugin.getLanguageService()).thenReturn(language);
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(sender), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            new StatsGui(plugin).seasonRanking(sender, ranking, 1);
            assertTrue(messages.stream().anyMatch(line -> line.startsWith("#1 River:")));
            assertFalse(messages.stream().anyMatch(line -> line.contains("Recorded totals · Today")));
            verifyNoInteractions(stats);
        } finally {
            language.close();
        }
    }

    @Test
    void scheduledCatalogRendersZeroCoverageWithoutExtraLanguageArguments() {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        CommandSender sender = mock(CommandSender.class);
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(sender.hasPermission("static.seasons")).thenReturn(true);
        when(stats.seasons()).thenReturn(List.of(new SeasonSnapshot(definition(), SeasonStatus.SCHEDULED, 0, 0, 0)));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(plugin).executeAsync(any());
        List<String> messages = new ArrayList<>();
        LanguageService language = new LanguageService(directory.toFile(), plugin.getLogger());
        try (MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class)) {
            when(plugin.getLanguageService()).thenReturn(language);
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(sender), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            new SeasonsGui(plugin).catalog(sender, 1);
            assertTrue(messages.contains("Recording has not started."));
            assertEquals(1, messages.stream().filter(line -> line.equals("Status: Scheduled")).count());
        } finally {
            language.close();
        }
    }

    private List<String> profile(boolean other) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        Player sender = mock(Player.class);
        UUID viewer = UUID.randomUUID();
        UUID target = other ? UUID.randomUUID() : viewer;
        PlayerStatsSnapshot profile = new PlayerStatsSnapshot(target, "River", Map.of(StatKey.PVP_KILLS, 4D, StatKey.PVP_DEATHS, 2D), 1, 1);
        SeasonSnapshot season = new SeasonSnapshot(definition(), SeasonStatus.ARCHIVED, 1, 2, 1);
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(plugin.getPresentationService()).thenReturn(mock(PresentationService.class));
        when(sender.getUniqueId()).thenReturn(viewer);
        when(sender.hasPermission("static.seasons")).thenReturn(true);
        when(stats.season("autumn")).thenReturn(Optional.of(season));
        when(stats.find("River", StatsPeriod.LIFETIME)).thenReturn(Optional.of(profile));
        when(stats.seasonSnapshot(target, "autumn")).thenReturn(Optional.of(new SeasonProfileSnapshot(season, profile)));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(plugin).executeAsync(any());
        List<String> messages = new ArrayList<>();
        LanguageService language = new LanguageService(directory.toFile(), plugin.getLogger());
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class)) {
            when(plugin.getLanguageService()).thenReturn(language);
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(sender), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(2).run();
                return true;
            });
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(sender), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            new SeasonsGui(plugin).profile(sender, "autumn", "River");
        } finally {
            language.close();
        }
        return messages;
    }

    private SeasonDefinition definition() {
        return new SeasonDefinition("autumn", "Autumn", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-08T00:00:00Z"), rules());
    }

    private SeasonRules rules() {
        return new SeasonRules(StatKey.KILL_DEATH_RATIO, 2, Set.of(), false, false, 300_000);
    }
}
