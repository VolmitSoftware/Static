package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.TextValue;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PersonalLanguageReloadTest {
    @TempDir
    Path directory;

    @Test
    void personalCatalogChangesRetainMalformedDataUseEnglishAfterDeletionAndRefreshAfterRestoration() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        AtomicReference<String> locale = new AtomicReference<>("en_US");
        
        LanguageService languages = new LanguageService(directory.toFile(), logger);
        try (MockedStatic<BukkitLanguageSwitcher> registration = mockStatic(BukkitLanguageSwitcher.class)) {
            languages.install(languages.prepare("en_US"));
            languages.initializeSelections(locale::get, (selected, snapshot) -> {
                locale.set(selected);
                languages.install(new LanguageService.PreparedLanguage(selected, languages.languageFile(selected), snapshot, true));
            });
            Path personal = directory.resolve("languages/de_DE.toml");
            Path server = directory.resolve("languages/fr_FR.toml");
            Files.createDirectories(personal.getParent());
            Files.createDirectories(server.getParent());
            Files.writeString(personal, title("LocaleQA"));
            Files.writeString(server, title("ServerQA"));
            Field selectionField = LanguageService.class.getDeclaredField("selections");
            selectionField.setAccessible(true);
            PluginLanguageService selections = (PluginLanguageService) selectionField.get(languages);
            UUID player = UUID.randomUUID();
            selections.selectDefault("fr_FR").get(5L, TimeUnit.SECONDS);
            selections.selectPlayer(player, "de_DE").get(5L, TimeUnit.SECONDS);
            assertEquals(new TextValue("LocaleQA {plugin} {section}"), selections.snapshot(player).value(BukkitLanguageMessages.EDITOR_TITLE));
            String savedChoice = Files.readString(directory.resolve("languages/language-preferences.properties"));

            Files.writeString(personal, title("LiveQA"));
            languages.install(languages.prepare(selections.defaultLocale()));
                languages.reloadInstalledLocales();
            assertEquals(new TextValue("LiveQA {plugin} {section}"), selections.snapshot(player).value(BukkitLanguageMessages.EDITOR_TITLE));

            Files.writeString(personal, "[language.editor\n");
            Files.writeString(server, title("ServerUpdated"));
            languages.install(languages.prepare(selections.defaultLocale()));
                languages.reloadInstalledLocales();
            assertEquals(new TextValue("LiveQA {plugin} {section}"), selections.snapshot(player).value(BukkitLanguageMessages.EDITOR_TITLE));
            assertEquals(new TextValue("ServerUpdated {plugin} {section}"), selections.snapshot().value(BukkitLanguageMessages.EDITOR_TITLE));
            assertEquals("[language.editor\n", Files.readString(personal));

            Files.writeString(personal, "[language.menu]\nyour_language = 'Meine Sprache'\n");
            languages.install(languages.prepare(selections.defaultLocale()));
                languages.reloadInstalledLocales();
            assertEquals(BukkitLanguageMessages.EDITOR_TITLE.englishValue(), selections.snapshot(player).value(BukkitLanguageMessages.EDITOR_TITLE));
            assertEquals(new TextValue("Meine Sprache"), selections.snapshot(player).value(BukkitLanguageMessages.YOUR_LANGUAGE));

            Files.delete(personal);
            languages.install(languages.prepare(selections.defaultLocale()));
                languages.reloadInstalledLocales();
            assertEquals(BukkitLanguageMessages.EDITOR_TITLE.englishValue(), selections.snapshot(player).value(BukkitLanguageMessages.EDITOR_TITLE));
            assertEquals(BukkitLanguageMessages.YOUR_LANGUAGE.englishValue(), selections.snapshot(player).value(BukkitLanguageMessages.YOUR_LANGUAGE));
            assertFalse(Files.exists(personal));
            assertEquals("de_DE", selections.playerLocale(player).orElseThrow());
            assertEquals(savedChoice, Files.readString(directory.resolve("languages/language-preferences.properties")));

            Files.writeString(personal, title("RestoredQA"));
            languages.install(languages.prepare(selections.defaultLocale()));
                languages.reloadInstalledLocales();
            assertEquals(new TextValue("RestoredQA {plugin} {section}"), selections.snapshot(player).value(BukkitLanguageMessages.EDITOR_TITLE));
            assertEquals(savedChoice, Files.readString(directory.resolve("languages/language-preferences.properties")));
        } finally {
            languages.close();
        }
    }

    private static String title(String label) {
        return "[language.editor]\ntitle = '" + label + " {plugin} {section}'\n";
    }
}
