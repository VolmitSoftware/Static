package com.volmit.staticstats.gui;

import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.privacy.VisibilityFixtures;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.presentation.PresentationService;
import com.volmit.staticstats.stats.PlayerSession;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.SessionEndReason;
import com.volmit.staticstats.stats.StatsPeriod;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatsSessionsTest {
    @TempDir
    Path directory;

    @Test
    void recentSessionsUseBoundedPagesAndExplainInterruptedRecords() {
        List<String> messages = render(false);
        assertTrue(messages.stream().anyMatch(line -> line.contains("River's recent sessions") && line.contains("page 2/2")));
        assertEquals(1, messages.stream().filter(line -> line.startsWith("#")).count());
        assertTrue(messages.stream().anyMatch(line -> line.startsWith("#6 ")));
        assertTrue(messages.stream().anyMatch(line -> line.equals("Online: 10s · Active: 8s · AFK: 2s")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("last saved checkpoint; disconnect time is unknown")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("not proof of attention")));
    }

    @Test
    void sessionsForAnotherPlayerRequireTheSeparatePermission() {
        assertTrue(render(true).isEmpty());
    }

    private List<String> render(boolean otherPlayer) {
        StaticPlugin plugin = VisibilityFixtures.plugin();
        StatsService stats = mock(StatsService.class);
        PresentationService presentation = mock(PresentationService.class);
        Player player = mock(Player.class);
        UUID viewer = UUID.randomUUID();
        UUID target = otherPlayer ? UUID.randomUUID() : viewer;
        PlayerStatsSnapshot profile = new PlayerStatsSnapshot(target, "River", Map.of(), 1, 1);
        List<PlayerSession> records = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            records.add(new PlayerSession(UUID.randomUUID(), 1, 10_001, 10_001, 10_000, 8_000, 2_000,
                    index == 5 ? SessionEndReason.INTERRUPTED : SessionEndReason.QUIT));
        }
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger(StatsSessionsTest.class.getName()));
        when(plugin.getStatsService()).thenReturn(stats);
        when(plugin.getPresentationService()).thenReturn(presentation);
        when(player.getUniqueId()).thenReturn(viewer);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("static.sessions")).thenReturn(true);
        when(stats.find("River", StatsPeriod.LIFETIME)).thenReturn(Optional.of(profile));
        when(stats.sessions(target)).thenReturn(records);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(plugin).executeAsync(any());
        List<String> messages = new ArrayList<>();
        LanguageService language = new LanguageService(directory.toFile(), plugin.getLogger());
        try (MockedConstruction<ProfilePreferences> preferences = mockConstruction(ProfilePreferences.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<ComponentMessenger> messenger = mockStatic(ComponentMessenger.class)) {
            when(plugin.getLanguageService()).thenReturn(language);
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(2).run();
                return true;
            });
            messenger.when(() -> ComponentMessenger.sendMarkup(eq(player), any(String.class))).thenAnswer(invocation -> {
                messages.add(ComponentText.markup(invocation.getArgument(1)).plain());
                return null;
            });
            new StatsGui(plugin).sessions(player, "River", 2);
            if (otherPlayer) {
                verify(presentation).command(eq(player), eq(StaticMessages.NO_PERMISSION), any(), eq(FeedbackTone.FAILURE));
            }
            assertFalse(messages.stream().anyMatch(line -> line.contains("{player}")));
            return messages;
        } finally {
            language.close();
        }
    }
}
