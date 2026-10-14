package com.volmit.staticstats.config;

import art.arcane.volmlib.util.config.TomlCodec;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.localization.LanguageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigHotReloadServiceTest {
    @TempDir
    Path directory;

    @Test
    void externalEditAfterGuiSaveSurvivesPendingConfigurationInstallation() throws IOException {
        verifyExternalEditAfterSelfWrite(false);
    }

    @Test
    void externalEditSurvivesAChangedPollIntervalAndCooldown() throws IOException {
        verifyExternalEditAfterSelfWrite(true);
    }

    private void verifyExternalEditAfterSelfWrite(boolean changeTiming) throws IOException {
        ConfigService config = new ConfigService(directory.toFile());
        config.install(config.prepareFromDisk());
        config.update(candidate -> {
            candidate.tracking.autosaveSeconds = 60;
            candidate.hotReload.pollIntervalMillis = 60_000;
            candidate.hotReload.cooldownMillis = 250;
            candidate.hotReload.notifyOperators = false;
        });
        File languages = Files.createDirectories(directory.resolve("languages")).toFile();
        LanguageService language = mock(LanguageService.class);
        when(language.languageDirectory()).thenReturn(languages);
        when(language.isLanguageFile(any(File.class))).thenReturn(false);
        StaticPlugin plugin = mock(StaticPlugin.class);
        when(plugin.getLanguageService()).thenReturn(language);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StaticHotReloadTest"));
        AtomicInteger reloads = new AtomicInteger();
        AtomicReference<ConfigHotReloadService> watcher = new AtomicReference<>();
        when(plugin.reloadAll(false)).thenAnswer(invocation -> {
            config.install(config.prepareFromDisk());
            watcher.get().requestReconfigure();
            reloads.incrementAndGet();
            return true;
        });
        ConfigHotReloadService service = new ConfigHotReloadService(plugin, config);
        watcher.set(service);
        service.start();
        try {
            config.update(candidate -> {
                candidate.tracking.autosaveSeconds = 17;
                if (changeTiming) {
                    candidate.hotReload.pollIntervalMillis = 250;
                    candidate.hotReload.cooldownMillis = 1_000;
                }
            });
            service.requestReconfigure();
            StaticConfig external = config.editableCopy();
            external.tracking.autosaveSeconds = 19;
            Files.writeString(config.configFile().toPath(), TomlCodec.toToml(external, "static"));
            assertEquals(17, config.runtime().autosaveSeconds());
            for (int poll = 0; poll < 40 && config.runtime().autosaveSeconds() != 19; poll++) {
                service.pollChanges();
            }
            assertEquals(19, config.runtime().autosaveSeconds());
            assertEquals(changeTiming ? 250 : 60_000, config.runtime().hotReloadPollMillis());
            assertEquals(changeTiming ? 1_000 : 250, config.runtime().hotReloadCooldownMillis());
            assertEquals(1, reloads.get());
        } finally {
            service.close();
        }
    }
}
