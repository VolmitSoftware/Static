package com.volmit.staticstats;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"26.1-R0.1-SNAPSHOT", "26.1.2-R0.1-SNAPSHOT", "26.2-R0.1-SNAPSHOT", "26.3-R0.1-SNAPSHOT", "26.3.1",
            "26.1.2.build.74-stable", "26.3.build.169-beta"})
    void acceptsSupportedReleaseLines(String version) {
        assertDoesNotThrow(() -> StaticPlugin.requireSupportedServer(version));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1.21.11-R0.1-SNAPSHOT", "26.0", "26.4-R0.1-SNAPSHOT", "27.1", "26.30", "26.3invalid", "unknown",
            "26.4.build.1-stable", "26.1.2.build.invalid"})
    void rejectsUnsupportedOrUnidentifiableServers(String version) {
        assertThrows(IllegalStateException.class, () -> StaticPlugin.requireSupportedServer(version));
    }
}
