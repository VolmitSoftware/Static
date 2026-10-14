package com.volmit.staticstats.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageServiceTest {
    @TempDir
    Path directory;

    @Test
    void startupCreatesEnglishOnlyEvenForAnUnknownRequestedLocale() throws IOException {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        try {
            language.prepare("zz_ZZ");
            assertThat(directory.resolve("languages/en_US.toml")).isRegularFile();
            assertThat(directory.resolve("languages/zz_ZZ.toml")).doesNotExist();
        } finally {
            language.close();
        }
    }

    @Test
    void missingTranslationUsesCodeOwnedEnglishInsteadOfEditedEnglish() throws IOException {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        try {
            language.prepare("en_US");
            Files.writeString(directory.resolve("languages/en_US.toml"), "[runtime]\nplayer_only = \"CUSTOM ENGLISH\"\n");
            Files.writeString(directory.resolve("languages/de_DE.toml"), "[runtime]\nprefix = \"Static > \"\n");
            language.install(language.prepare("de_DE"));
            assertThat(language.render(StaticMessages.PLAYER_ONLY)).contains("This command can only be used by a player");
            assertThat(language.render(StaticMessages.PLAYER_ONLY)).doesNotContain("CUSTOM ENGLISH");
        } finally {
            language.close();
        }
    }

    @Test
    void invalidEnglishEditRetainsTheInstalledMessages() throws IOException {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        try {
            language.prepare("en_US");
            Path file = directory.resolve("languages/en_US.toml");
            String valid = Files.readString(file).replace("Unknown command", "Unknown command CUSTOM");
            Files.writeString(file, valid);
            language.install(language.prepare("en_US"));
            String previous = language.render(StaticMessages.UNKNOWN_COMMAND);
            assertThat(previous).contains("CUSTOM");
            Files.writeString(file, valid + "\n[unterminated\n");
            assertThatThrownBy(() -> language.prepare("en_US"))
                    .isInstanceOf(IOException.class).hasMessageContaining("Invalid TOML");
            assertThat(language.render(StaticMessages.UNKNOWN_COMMAND)).isEqualTo(previous);
        } finally {
            language.close();
        }
    }

    @Test
    void dashboardUsesInstalledDefaultLanguageAndPlainCodeOwnedFallback() throws IOException {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        try {
            language.prepare("en_US");
            Files.writeString(directory.resolve("languages/en_US.toml"), "[dashboard]\nplayers = \"CUSTOM ENGLISH\"\n");
            Files.writeString(directory.resolve("languages/de_DE.toml"),
                    "[dashboard]\noverview = \"<gradient:#FFB454:#FFE39C>Teilnahme</gradient>\"\n[stat]\nonline_time = \"&6Onlinezeit\"\n");
            language.install(language.prepare("de_DE"));
            Map<String, String> messages = language.dashboardMessages();
            assertThat(language.dashboardLocale()).isEqualTo("de_DE");
            assertThat(messages).containsEntry("dashboard.overview", "Teilnahme")
                    .containsEntry("dashboard.players", "Players")
                    .containsEntry("stat.online_time", "Onlinezeit");
            assertThat(messages.keySet()).allMatch(key -> key.startsWith("dashboard.")
                    || key.startsWith("stat.") || key.startsWith("details.counter."));
            assertThat(directory.resolve("languages/fr_FR.toml")).doesNotExist();
            assertThatThrownBy(() -> messages.put("dashboard.players", "Changed"))
                    .isInstanceOf(UnsupportedOperationException.class);
        } finally {
            language.close();
        }
    }

    @Test
    void repositoryTranslationsCoverEveryMessageWithValidPlaceholders() throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        for (MessageKey key : StaticMessages.catalog().keys()) {
            expected.add(key.id());
        }
        List<Path> files;
        try (Stream<Path> paths = Files.list(Path.of("src/main/resources/languages"))) {
            files = paths.filter(path -> path.toString().endsWith(".toml")).toList();
        }
        assertThat(files).hasSize(17);
        for (Path file : files) {
            String content = Files.readString(file);
            Map<String, String> translations = TomlLanguageParser.parseValidText(content, StaticMessages.catalog());
            assertThat(translations.keySet()).as(file.getFileName().toString())
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }
}
