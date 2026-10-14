package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import com.volmit.staticstats.stats.StatKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.UUID;

public final class StatsFormatter {
    private static final ThreadLocal<Formats> FORMATS = new ThreadLocal<>();

    private StatsFormatter() {
    }

    public static String format(StatKey key, double value, LanguageService language, CommandSender sender) {
        return formatForPlayer(key, value, language, playerId(sender));
    }

    public static String formatForPlayer(StatKey key, double value, LanguageService language, UUID playerId) {
        double safe = Double.isFinite(value) ? Math.max(0, value) : 0;
        Locale locale = language.formattingLocale(playerId);
        if (key == StatKey.ONLINE_TIME || key == StatKey.ACTIVE_TIME || key == StatKey.AFK_TIME) {
            long seconds = (long) (safe / 1000);
            long days = seconds / 86400;
            long hours = seconds % 86400 / 3600;
            long minutes = seconds % 3600 / 60;
            if (days > 0) {
                return unit(days, FormatMessages.DAYS, language, playerId, locale) + " "
                        + unit(hours, FormatMessages.HOURS, language, playerId, locale) + " "
                        + unit(minutes, FormatMessages.MINUTES, language, playerId, locale);
            }
            if (hours > 0) {
                return unit(hours, FormatMessages.HOURS, language, playerId, locale) + " "
                        + unit(minutes, FormatMessages.MINUTES, language, playerId, locale);
            }
            if (minutes > 0) {
                return unit(minutes, FormatMessages.MINUTES, language, playerId, locale) + " "
                        + unit(seconds % 60, FormatMessages.SECONDS, language, playerId, locale);
            }
            return unit(seconds, FormatMessages.SECONDS, language, playerId, locale);
        }
        if (key == StatKey.ARROW_ACCURACY) {
            return formats(locale).percent().format(Math.min(1, safe));
        }
        if (key.category().equals("movement")) {
            return safe >= 1000 ? decimal(safe / 1000, 2, locale) + " km" : decimal(safe, 1, locale) + " m";
        }
        return decimal(safe, key.derived() && key != StatKey.ARROWS_MISSED || key.name().contains("DAMAGE") ? 2 : 0, locale);
    }

    public static String number(double value, LanguageService language, CommandSender sender) {
        return decimal(value, 0, language.formattingLocale(playerId(sender)));
    }

    public static String decimal(double value, LanguageService language, CommandSender sender) {
        NumberFormat number = formats(language.formattingLocale(playerId(sender))).number();
        number.setMinimumFractionDigits(0);
        number.setMaximumFractionDigits(340);
        return number.format(value);
    }

    public static String date(long epochMillis, LanguageService language, CommandSender sender) {
        return dateForPlayer(epochMillis, language, playerId(sender));
    }

    public static String dateForPlayer(long epochMillis, LanguageService language, UUID playerId) {
        if (epochMillis <= 0) {
            return language.renderForPlayer(playerId, FormatMessages.UNKNOWN, MessageArgs.empty());
        }
        return formats(language.formattingLocale(playerId)).date().format(Instant.ofEpochMilli(epochMillis)) + " UTC";
    }

    private static String unit(long amount, TextKey key, LanguageService language, UUID playerId, Locale locale) {
        return language.renderForPlayer(playerId, key,
                MessageArgs.builder().untrusted("value", decimal(amount, 0, locale)).build());
    }

    private static String decimal(double value, int places, Locale locale) {
        NumberFormat number = formats(locale).number();
        number.setMinimumFractionDigits(places);
        number.setMaximumFractionDigits(places);
        return number.format(value);
    }

    private static Formats formats(Locale locale) {
        Formats current = FORMATS.get();
        if (current == null || !current.locale().equals(locale)) {
            NumberFormat percent = NumberFormat.getPercentInstance(locale);
            percent.setMinimumFractionDigits(1);
            percent.setMaximumFractionDigits(1);
            current = new Formats(locale, NumberFormat.getNumberInstance(locale), percent,
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneOffset.UTC));
            FORMATS.set(current);
        }
        return current;
    }

    private static UUID playerId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private record Formats(Locale locale, NumberFormat number, NumberFormat percent, DateTimeFormatter date) {
    }
}
