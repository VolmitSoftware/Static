package com.volmit.staticstats.privacy;

import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.detailed.DetailRankingSnapshot;
import com.volmit.staticstats.stats.RankingSnapshot;
import org.bukkit.command.CommandSender;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class VisibilityFixtures {
    private VisibilityFixtures() {
    }

    public static StaticPlugin plugin() {
        StaticPlugin plugin = mock(StaticPlugin.class);
        ProfileVisibility visibility = mock(ProfileVisibility.class);
        when(plugin.getProfileVisibility()).thenReturn(visibility);
        when(visibility.canView(any(CommandSender.class), any(UUID.class))).thenReturn(true);
        when(visibility.isServerVisible(any(UUID.class))).thenReturn(true);
        when(visibility.filter(any(RankingSnapshot.class))).thenAnswer(call -> call.getArgument(0));
        when(visibility.filter(any(DetailRankingSnapshot.class))).thenAnswer(call -> call.getArgument(0));
        return plugin;
    }
}
