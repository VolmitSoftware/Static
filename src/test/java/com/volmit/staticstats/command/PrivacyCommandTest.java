package com.volmit.staticstats.command;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.config.StaticConfig;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.PrivacyMessages;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.privacy.ProfileVisibility;
import com.volmit.staticstats.privacy.Visibility;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyCommandTest {
    @Test
    void administratorReceivesSuccessOnlyAfterTheWriteCompletes() {
        Harness harness = new Harness(true);
        harness.command.execute(harness.sender, "public", "River");
        verify(harness.language, never()).send(eq(harness.sender), eq(PrivacyMessages.CHANGED), any(MessageArgs.class));
        harness.pending.complete(Visibility.PUBLIC);
        verify(harness.language).send(eq(harness.sender), eq(PrivacyMessages.CHANGED), any(MessageArgs.class));
    }

    @Test
    void failedPersistenceReportsFailureWithoutClaimingSuccess() {
        Harness harness = new Harness(true);
        harness.command.execute(harness.sender, "private", "River");
        harness.pending.completeExceptionally(new IllegalStateException("write failed"));
        verify(harness.language).send(harness.sender, PrivacyMessages.FAILED);
        verify(harness.language, never()).send(eq(harness.sender), eq(PrivacyMessages.CHANGED), any(MessageArgs.class));
    }

    @Test
    void otherProfileRequiresPrivacyOthersPermission() {
        Harness harness = new Harness(false);
        harness.command.execute(harness.sender, "public", "River");
        verify(harness.visibility, never()).set(any(UUID.class), any(Visibility.class));
        verify(harness.language).send(harness.sender, StaticMessages.NO_PERMISSION);
    }

    private static final class Harness {
        private final StaticPlugin plugin = mock(StaticPlugin.class);
        private final CommandSender sender = mock(CommandSender.class);
        private final LanguageService language = mock(LanguageService.class);
        private final ProfileVisibility visibility = mock(ProfileVisibility.class);
        private final CompletableFuture<Visibility> pending = new CompletableFuture<>();
        private final PrivacyCommand command;

        private Harness(boolean others) {
            StatsService stats = mock(StatsService.class);
            ConfigService config = mock(ConfigService.class);
            when(plugin.getStatsService()).thenReturn(stats);
            when(plugin.getProfileVisibility()).thenReturn(visibility);
            when(plugin.getLanguageService()).thenReturn(language);
            when(plugin.getConfigService()).thenReturn(config);
            when(config.runtime()).thenReturn(RuntimeConfig.from(new StaticConfig()));
            when(sender.hasPermission("static.privacy")).thenReturn(true);
            when(sender.hasPermission("static.privacy.others")).thenReturn(others);
            when(visibility.ready()).thenReturn(CompletableFuture.completedFuture(null));
            when(visibility.available()).thenReturn(true);
            when(visibility.set(any(UUID.class), any(Visibility.class))).thenReturn(pending);
            when(language.render(eq(sender), any(TextKey.class))).thenReturn("Public");
            when(stats.find("River")).thenReturn(Optional.of(new PlayerStatsSnapshot(UUID.randomUUID(), "River", Map.of(), 10, 1)));
            doAnswer(call -> {
                call.<Runnable>getArgument(0).run();
                return null;
            }).when(plugin).executeAsync(any());
            command = new PrivacyCommand(plugin);
        }
    }
}

