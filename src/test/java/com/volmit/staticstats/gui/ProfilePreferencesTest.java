package com.volmit.staticstats.gui;

import com.volmit.staticstats.stats.StatKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilePreferencesTest {
    private static final UUID VIEWER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final Logger LOGGER = Logger.getLogger(ProfilePreferencesTest.class.getName());

    @TempDir
    Path directory;

    @Test
    void limitsFavoritesToEightAndPersistsViewerOrderAcrossRestart() throws Exception {
        Path file = directory.resolve("storage/profile-preferences.json");
        List<StatKey> expected = List.of(StatKey.values()).subList(0, 8);
        try (ProfilePreferences preferences = new ProfilePreferences(file, LOGGER)) {
            for (StatKey statistic : expected) {
                assertEquals(ProfilePreferences.ToggleResult.ADDED, preferences.toggle(VIEWER, statistic).get(5, TimeUnit.SECONDS));
            }
            assertEquals(ProfilePreferences.ToggleResult.LIMIT, preferences.toggle(VIEWER, StatKey.values()[8]).get(5, TimeUnit.SECONDS));
            assertEquals(ProfilePreferences.ToggleResult.ADDED, preferences.toggle(OTHER, StatKey.VOTES).get(5, TimeUnit.SECONDS));
            assertEquals(expected, preferences.favorites(VIEWER));
            assertThrows(UnsupportedOperationException.class, () -> preferences.favorites(VIEWER).clear());
        }
        try (ProfilePreferences restored = new ProfilePreferences(file, LOGGER)) {
            restored.ready().get(5, TimeUnit.SECONDS);
            assertEquals(expected, restored.favorites(VIEWER));
            assertEquals(List.of(StatKey.VOTES), restored.favorites(OTHER));
            assertEquals(ProfilePreferences.ToggleResult.REMOVED, restored.toggle(VIEWER, expected.getFirst()).get(5, TimeUnit.SECONDS));
            assertEquals(ProfilePreferences.ToggleResult.ADDED, restored.toggle(VIEWER, StatKey.ARROW_ACCURACY).get(5, TimeUnit.SECONDS));
            assertEquals(StatKey.ARROW_ACCURACY, restored.favorites(VIEWER).getLast());
        }
    }

    @Test
    void closeFlushesAcceptedWritesAndRejectsLaterEdits() throws Exception {
        Path file = directory.resolve("profile-preferences.json");
        ProfilePreferences preferences = new ProfilePreferences(file, LOGGER);
        List<CompletableFuture<ProfilePreferences.ToggleResult>> writes = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            writes.add(preferences.toggle(VIEWER, StatKey.values()[index]));
        }
        preferences.close();
        for (CompletableFuture<ProfilePreferences.ToggleResult> write : writes) {
            assertEquals(ProfilePreferences.ToggleResult.ADDED, write.get(5, TimeUnit.SECONDS));
        }
        assertThrows(ExecutionException.class, () -> preferences.toggle(VIEWER, StatKey.VOTES).get(5, TimeUnit.SECONDS));
        try (ProfilePreferences restored = new ProfilePreferences(file, LOGGER)) {
            restored.ready().get(5, TimeUnit.SECONDS);
            assertEquals(8, restored.favorites(VIEWER).size());
        }
    }

    @Test
    void failedWriteKeepsExistingFavoritesAndCanRetry() throws Exception {
        Path file = directory.resolve("storage/profile-preferences.json");
        try (ProfilePreferences preferences = new ProfilePreferences(file, LOGGER)) {
            preferences.toggle(VIEWER, StatKey.VOTES).get(5, TimeUnit.SECONDS);
            String previous = Files.readString(file);
            Files.delete(file);
            Files.delete(file.getParent());
            Files.writeString(file.getParent(), "occupied");
            assertThrows(ExecutionException.class, () -> preferences.toggle(VIEWER, StatKey.JOINS).get(5, TimeUnit.SECONDS));
            assertEquals(List.of(StatKey.VOTES), preferences.favorites(VIEWER));
            Files.delete(file.getParent());
            Files.createDirectory(file.getParent());
            Files.writeString(file, previous);
            assertEquals(ProfilePreferences.ToggleResult.ADDED, preferences.toggle(VIEWER, StatKey.JOINS).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidCurrentFileIsPreservedAndCannotBeOverwrittenByAnEdit() throws Exception {
        Path file = directory.resolve("profile-preferences.json");
        String invalid = "{\"version\":1,\"players\":{\"not-a-uuid\":[\"votes\"]}}";
        Files.writeString(file, invalid);
        try (ProfilePreferences preferences = new ProfilePreferences(file, LOGGER)) {
            assertThrows(ExecutionException.class, () -> preferences.ready().get(5, TimeUnit.SECONDS));
            assertFalse(preferences.available());
            assertThrows(ExecutionException.class, () -> preferences.toggle(VIEWER, StatKey.VOTES).get(5, TimeUnit.SECONDS));
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test
    void removingLastFavoritePersistsAnEmptySelectionWithoutTemporaryFiles() throws Exception {
        Path file = directory.resolve("profile-preferences.json");
        try (ProfilePreferences preferences = new ProfilePreferences(file, LOGGER)) {
            preferences.toggle(VIEWER, StatKey.VOTES).get(5, TimeUnit.SECONDS);
            preferences.toggle(VIEWER, StatKey.VOTES).get(5, TimeUnit.SECONDS);
            assertTrue(preferences.favorites(VIEWER).isEmpty());
        }
        try (ProfilePreferences restored = new ProfilePreferences(file, LOGGER)) {
            restored.ready().get(5, TimeUnit.SECONDS);
            assertTrue(restored.favorites(VIEWER).isEmpty());
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(file), files.toList());
        }
    }
}
