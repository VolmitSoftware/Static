package com.volmit.staticstats.dashboard;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

public record DashboardSettings(boolean enabled, int port, String publicOrigin, boolean publicProfilesEnabled) {
    public DashboardSettings {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("dashboard.port must be between 1024 and 65535");
        }
        publicOrigin = Objects.requireNonNull(publicOrigin, "dashboard.publicOrigin").trim();
        try {
            URI origin = new URI(publicOrigin);
            String scheme = origin.getScheme();
            String host = origin.getHost();
            if (host == null || scheme == null || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                    || origin.getPort() == 0 || origin.getPort() > 65535) {
                throw new IllegalArgumentException("dashboard.publicOrigin must be an origin without a path, credentials, query, or fragment");
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(scheme.equals("http") && (host.equals("127.0.0.1") || host.equals("localhost")))) {
                throw new IllegalArgumentException("dashboard.publicOrigin requires HTTPS except on localhost or 127.0.0.1");
            }
            int originPort = origin.getPort();
            if (originPort == (scheme.equals("https") ? 443 : 80)) {
                originPort = -1;
            }
            publicOrigin = new URI(scheme, null, host, originPort, null, null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("dashboard.publicOrigin is not a valid HTTP origin", exception);
        }
    }
}
