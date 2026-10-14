package com.volmit.staticstats.localization;

import com.volmit.staticstats.stats.StatKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsFormatterTest {
    @TempDir
    Path directory;

    @Test
    void formatsNumbersPercentagesDurationAndDatesInTheSelectedLocale() throws IOException {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        try {
            language.install(language.prepare("en_US"));
            Files.writeString(directory.resolve("languages/de_DE.toml"), """
                    [format]
                    hours = "{value} Std."
                    minutes = "{value} Min."
                    unknown = "Unbekannt"
                    """);
            language.install(language.prepare("de_DE"));
            assertEquals("1.234", StatsFormatter.formatForPlayer(StatKey.BLOCKS_BROKEN, 1234, language, null));
            assertEquals("10,5", StatsFormatter.decimal(10.5, language, null));
            assertEquals("0,0000000001", StatsFormatter.decimal(0.0000000001, language, null));
            assertEquals("1,25 km", StatsFormatter.formatForPlayer(StatKey.DISTANCE_TRAVELED, 1250, language, null));
            assertTrue(StatsFormatter.formatForPlayer(StatKey.ARROW_ACCURACY, .8, language, null).startsWith("80,0"));
            assertEquals("1 Std. 1 Min.", StatsFormatter.formatForPlayer(StatKey.ONLINE_TIME, 3665000, language, null));
            assertEquals("Unbekannt", StatsFormatter.dateForPlayer(0, language, null));
            assertTrue(StatsFormatter.dateForPlayer(1000, language, null).endsWith(" UTC"));
            language.install(language.prepare("en_US"));
            assertEquals("1,234", StatsFormatter.formatForPlayer(StatKey.BLOCKS_BROKEN, 1234, language, null));
            assertEquals("1.25 km", StatsFormatter.formatForPlayer(StatKey.DISTANCE_TRAVELED, 1250, language, null));
        } finally {
            language.close();
        }
    }

    @Test
    void missingDurationUnitUsesBuiltInEnglishWhileNumbersRemainLocalized() throws IOException {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        try {
            language.install(language.prepare("en_US"));
            Files.writeString(directory.resolve("languages/de_DE.toml"), "[format]\nunknown = 'Unbekannt'\n");
            language.install(language.prepare("de_DE"));
            assertEquals("1h 1m", StatsFormatter.formatForPlayer(StatKey.ONLINE_TIME, 3665000, language, null));
            assertEquals("0", StatsFormatter.formatForPlayer(StatKey.BLOCKS_BROKEN, Double.NaN, language, null));
        } finally {
            language.close();
        }
    }
}
