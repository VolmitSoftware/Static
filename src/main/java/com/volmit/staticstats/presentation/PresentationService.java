package com.volmit.staticstats.presentation;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.localization.LanguageService;
import com.volmit.staticstats.localization.StaticMessages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

public final class PresentationService implements Listener {
    private final StaticPlugin plugin;
    private final ConfigService config;
    private final LanguageService language;
    private volatile boolean closed;

    public PresentationService(Dependencies dependencies) {
        this.plugin = dependencies.plugin();
        this.config = dependencies.config();
        this.language = dependencies.language();
    }

    public record Dependencies(StaticPlugin plugin, ConfigService config, LanguageService language) {
    }

    public void command(CommandSender sender, TextKey key, FeedbackTone tone) {
        command(sender, key, MessageArgs.empty(), tone);
    }

    public void command(CommandSender sender, TextKey key, MessageArgs arguments, FeedbackTone tone) {
        if (closed) {
            return;
        }
        if (sender instanceof Player player) {
            FoliaScheduler.runEntity(plugin, player, () -> {
                if (closed) {
                    return;
                }
                language.send(player, key, arguments);
                if (config.runtime().commandSounds()) {
                    String sound = tone == FeedbackTone.FAILURE ? "minecraft:block.note_block.bass"
                            : "minecraft:ui.button.click";
                    player.playSound(player.getLocation(), sound, 0.3F, tone == FeedbackTone.FAILURE ? 0.7F : 1.2F);
                }
            });
            return;
        }
        language.send(sender, key, arguments);
    }

    public void hotReload(Player player, boolean success) {
        if (closed) {
            return;
        }
        FoliaScheduler.runEntity(plugin, player, () -> ComponentMessenger.sendActionBarMarkup(player,
                language.render(player, success ? StaticMessages.HOT_RELOAD_SUCCESS : StaticMessages.HOT_RELOAD_FAILED)));
    }

    public void shutdown() {
        closed = true;
    }
}
