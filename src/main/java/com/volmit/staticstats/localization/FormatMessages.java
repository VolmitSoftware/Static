package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

public final class FormatMessages {
    public static final TextKey DAYS = TextKey.of("format.days", "{value}d");
    public static final TextKey HOURS = TextKey.of("format.hours", "{value}h");
    public static final TextKey MINUTES = TextKey.of("format.minutes", "{value}m");
    public static final TextKey SECONDS = TextKey.of("format.seconds", "{value}s");
    public static final TextKey UNKNOWN = TextKey.of("format.unknown", "Unknown");

    private FormatMessages() {
    }

    public static List<MessageKey> keys() {
        return List.of(DAYS, HOURS, MINUTES, SECONDS, UNKNOWN);
    }
}
