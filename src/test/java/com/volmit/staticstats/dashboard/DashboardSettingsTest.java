package com.volmit.staticstats.dashboard;

import com.volmit.staticstats.config.ConfigService;
import com.volmit.staticstats.config.RuntimeConfig;
import com.volmit.staticstats.config.StaticConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardSettingsTest {
    @TempDir
    Path directory;

    @Test
    void defaultsAreDisabledAndSeparateCredentialsFromConfiguration() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        assertThat(service.runtime().dashboardSettings()).isEqualTo(
                new DashboardSettings(false, 8791, "http://127.0.0.1:8791", false));
        assertThat(Files.readString(service.configFile().toPath())).doesNotContain("access-token =", "bearer", "password");
        assertThat(directory.resolve("dashboard")).doesNotExist();
    }

    @Test
    void settingsRoundTripAndCopiesAreDetached() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        service.update(config -> {
            config.dashboard.enabled = true;
            config.dashboard.publicProfilesEnabled = true;
            config.dashboard.port = 8872;
            config.dashboard.publicOrigin = "https://STATS.example.com:443";
        });
        service.install(service.prepareFromDisk());
        DashboardSettings expected = new DashboardSettings(true, 8872, "https://stats.example.com", true);
        assertThat(service.runtime().dashboardSettings()).isEqualTo(expected);
        StaticConfig editable = service.editableCopy();
        editable.dashboard.enabled = false;
        assertThat(service.runtime().dashboardSettings()).isEqualTo(expected);
        assertThat(service.runtime().source().dashboard.enabled).isTrue();
    }

    @Test
    void rejectsOriginsThatCouldExposePlaintextRemoteCredentialsOrAlterRouting() {
        for (String invalid : List.of("http://example.com", "ftp://localhost", "https://example.com/",
                "https://example.com/path", "https://user:secret@example.com", "https://example.com?x=1",
                "https://example.com#fragment", "//example.com", "https://example.com:0", "https://example.com:65536",
                "https://example.com\\evil", "https://example.com\r\nX-Header: injected")) {
            assertThatThrownBy(() -> new DashboardSettings(true, 8791, invalid, false))
                    .as(invalid).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInvalidPortsEvenWhenDisabled() {
        for (int invalid : List.of(-1, 0, 80, 1023, 65536)) {
            StaticConfig config = new StaticConfig();
            config.dashboard.port = invalid;
            assertThatThrownBy(() -> RuntimeConfig.from(config)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void malformedEditsPreserveTheWorkingConfiguration() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        String before = Files.readString(service.configFile().toPath());
        assertThatThrownBy(() -> service.update(config -> config.dashboard.publicOrigin = "http://remote.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(service.configFile().toPath())).isEqualTo(before);
        assertThat(service.runtime().dashboardSettings().enabled()).isFalse();
    }
}
