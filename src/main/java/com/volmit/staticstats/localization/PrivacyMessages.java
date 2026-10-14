package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import com.volmit.staticstats.privacy.Visibility;

import java.util.List;

public final class PrivacyMessages {
    public static final TextKey DESCRIPTION = TextKey.of("privacy.description", "Choose who can view your statistics");
    public static final TextKey MODE = TextKey.of("privacy.parameter.mode", "Visibility: private, server, or public; omit to view");
    public static final TextKey PLAYER = TextKey.of("privacy.parameter.player", "Recorded player name or UUID; self by default");
    public static final TextKey STATUS = TextKey.of("privacy.status", "{prefix}&7Visibility for {player}: &f{mode}");
    public static final TextKey CHANGED = TextKey.of("privacy.changed", "{prefix}&aVisibility for {player} is now {mode}.");
    public static final TextKey INVALID = TextKey.of("privacy.invalid", "{prefix}&cChoose private, server, or public.");
    public static final TextKey FAILED = TextKey.of("privacy.failed", "{prefix}&cVisibility could not be saved. Your previous setting remains active.");
    public static final TextKey LINK = TextKey.of("privacy.link", "{prefix}&7Public profile: {url}");
    public static final TextKey PRIVATE = TextKey.of("privacy.private", "Private (you and administrators)");
    public static final TextKey SERVER = TextKey.of("privacy.server", "Server (in-game views only)");
    public static final TextKey PUBLIC = TextKey.of("privacy.public", "Public (server and public web)");

    private PrivacyMessages() {
    }

    public static TextKey label(Visibility visibility) {
        return switch (visibility) {
            case PRIVATE -> PRIVATE;
            case SERVER -> SERVER;
            case PUBLIC -> PUBLIC;
        };
    }

    public static List<MessageKey> keys() {
        return List.of(DESCRIPTION, MODE, PLAYER, STATUS, CHANGED, INVALID, FAILED, PRIVATE, SERVER, PUBLIC, LINK);
    }
}
