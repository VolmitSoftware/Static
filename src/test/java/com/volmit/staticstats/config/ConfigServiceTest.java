package com.volmit.staticstats.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigServiceTest {
    @TempDir
    Path directory;

    @Test
    void commitRechecksTheBoundaryAfterSavingAndRestoresRejectedChanges() throws IOException {
        ConfigService service = loadedService();
        RuntimeConfig previous = service.runtime();
        String original = readConfig();
        AtomicLong clock = new AtomicLong(9);
        AtomicReference<RuntimeConfig> installedPolicy = new AtomicReference<>(previous);
        IllegalArgumentException rejection = new IllegalArgumentException("Event definition is fixed after its start");
        service.setValidator(candidate -> {
            assertThat(clock.get()).isLessThan(10);
            clock.set(10);
        });
        service.setCommitter((candidate, publish) -> {
            assertThat(readConfig()).isNotEqualTo(original);
            assertThat(service.runtime()).isSameAs(previous);
            if (clock.get() >= 10) {
                throw rejection;
            }
            installedPolicy.set(candidate);
            publish.run();
        });

        assertThatThrownBy(() -> service.update(config -> config.tracking.autosaveSeconds = 120))
                .isSameAs(rejection);

        assertThat(service.runtime()).isSameAs(previous);
        assertThat(installedPolicy.get()).isSameAs(previous);
        assertThat(readConfig()).isEqualTo(original);
    }

    @Test
    void failedSaveNeverInvokesTheCommitterOrPublishesItsPolicy() throws IOException {
        ConfigService service = loadedService();
        RuntimeConfig previous = service.runtime();
        String original = readConfig();
        AtomicBoolean called = new AtomicBoolean();
        service.setCommitter((candidate, publish) -> {
            called.set(true);
            publish.run();
        });
        Files.createDirectory(directory.resolve("config.toml.tmp"));

        assertThatThrownBy(() -> service.update(config -> config.tracking.autosaveSeconds = 120))
                .isInstanceOf(IOException.class);

        assertThat(called).isFalse();
        assertThat(service.runtime()).isSameAs(previous);
        assertThat(readConfig()).isEqualTo(original);
    }

    @Test
    void successfulCommitControlsPublicationAndPrecedesSelfWriteNotification() throws IOException {
        ConfigService service = loadedService();
        RuntimeConfig previous = service.runtime();
        String original = readConfig();
        AtomicReference<RuntimeConfig> installedPolicy = new AtomicReference<>(previous);
        AtomicBoolean notified = new AtomicBoolean();
        service.setCommitter((candidate, publish) -> {
            assertThat(readConfig()).isNotEqualTo(original);
            assertThat(service.runtime()).isSameAs(previous);
            installedPolicy.set(candidate);
            publish.run();
            assertThat(service.runtime()).isSameAs(candidate);
        });
        service.setSelfWriteListener((file, contents) -> {
            assertThat(service.runtime()).isSameAs(installedPolicy.get());
            assertThat(contents).isEqualTo(readConfig());
            notified.set(true);
        });

        RuntimeConfig installed = service.update(config -> config.tracking.autosaveSeconds = 120);

        assertThat(installed.autosaveSeconds()).isEqualTo(120);
        assertThat(installedPolicy.get()).isSameAs(installed);
        assertThat(notified).isTrue();
    }

    @Test
    void rejectedExternalInstallationPreservesTheEditedFileAndPreviousRuntime() throws IOException {
        ConfigService service = loadedService();
        RuntimeConfig previous = service.runtime();
        StaticConfig candidate = service.editableCopy();
        candidate.tracking.autosaveSeconds = 120;
        ConfigRepository.PreparedConfig prepared = new ConfigRepository(directory.toFile()).save(candidate);
        service.setCommitter((runtime, publish) -> {
            assertThat(service.runtime()).isSameAs(previous);
            throw new IllegalArgumentException("Event has already begun");
        });

        assertThatThrownBy(() -> service.install(prepared)).isInstanceOf(IllegalArgumentException.class);

        assertThat(service.runtime()).isSameAs(previous);
        assertThat(readConfig()).isEqualTo(prepared.serialized());
    }

    @Test
    void successfulExternalInstallationUsesTheSamePublicationHook() throws IOException {
        ConfigService service = loadedService();
        RuntimeConfig previous = service.runtime();
        StaticConfig candidate = service.editableCopy();
        candidate.tracking.autosaveSeconds = 120;
        ConfigRepository.PreparedConfig prepared = new ConfigRepository(directory.toFile()).save(candidate);
        AtomicBoolean called = new AtomicBoolean();
        service.setCommitter((runtime, publish) -> {
            assertThat(runtime).isSameAs(prepared.runtime());
            assertThat(service.runtime()).isSameAs(previous);
            publish.run();
            called.set(true);
        });

        service.install(prepared);

        assertThat(called).isTrue();
        assertThat(service.runtime()).isSameAs(prepared.runtime());
    }

    @Test
    void failedRollbackIsSuppressedOnTheCommitFailureAndDoesNotPublishRuntime() throws IOException {
        ConfigService service = loadedService();
        RuntimeConfig previous = service.runtime();
        IllegalArgumentException rejection = new IllegalArgumentException("Event has already begun");
        service.setCommitter((candidate, publish) -> {
            try {
                Files.createDirectory(directory.resolve("config.toml.tmp"));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            throw rejection;
        });

        assertThatThrownBy(() -> service.update(config -> config.tracking.autosaveSeconds = 120))
                .isSameAs(rejection);

        assertThat(rejection.getSuppressed()).hasSize(1);
        assertThat(rejection.getSuppressed()[0]).isInstanceOf(IOException.class);
        assertThat(service.runtime()).isSameAs(previous);
    }

    @Test
    void aCommitFailureAfterPublicationRestoresThePreviousRuntimeAndFile() throws IOException {
        ConfigService service = loadedService();
        RuntimeConfig previous = service.runtime();
        String original = readConfig();
        service.setCommitter((candidate, publish) -> {
            publish.run();
            throw new IllegalStateException("Commit did not complete");
        });

        assertThatThrownBy(() -> service.update(config -> config.tracking.autosaveSeconds = 120))
                .isInstanceOf(IllegalStateException.class);

        assertThat(service.runtime()).isSameAs(previous);
        assertThat(readConfig()).isEqualTo(original);
    }

    private ConfigService loadedService() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        return service;
    }

    private String readConfig() {
        try {
            return Files.readString(directory.resolve("config.toml"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
