package com.volmit.staticstats.dashboard;

import com.google.gson.JsonObject;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardServerTest {
    @TempDir
    Path directory;

    @Test
    void disabledDoesNotCreateCredentialsAndCloseIsFinal() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.server.start(fixture.settings(false));
            assertFalse(Files.exists(fixture.tokenFile()));
            assertFalse(fixture.server.status().running());
            fixture.server.close();
            assertThrows(IllegalStateException.class, () -> fixture.server.start(fixture.settings(true)));
        }
    }

    @Test
    void tokenIsRequiredForEveryDataRouteAndNeverReturned() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            assertTrue(fixture.token().matches("[A-Za-z0-9_-]{43}"));
            for (String path : List.of("meta", "messages", "overview", "players", "rankings", "profile",
                    "detail-rankings", "seasons", "season", "milestones", "comparison")) {
                HttpResponse<String> denied = fixture.get("/api/" + path, false);
                assertEquals(401, denied.statusCode());
                assertFalse(denied.body().contains(fixture.token()));
            }
            HttpResponse<String> accepted = fixture.get("/api/meta", true);
            assertEquals(200, accepted.statusCode());
            assertEquals("{\"test\":true}", accepted.body());
            assertFalse(fixture.server.status().toString().contains(fixture.token()));
            assertEquals("no-store", accepted.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", accepted.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertTrue(accepted.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
            assertTrue(accepted.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
    }

    @Test
    void fixedAssetsArePublicAndPathsCannotReadFilesOrPrefixMatch() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            for (String path : List.of("/", "/dashboard.js", "/dashboard.css")) {
                assertEquals(200, fixture.get(path, false).statusCode());
            }
            for (String path : List.of("/dashboard/access-token", "/api/metax", "/api/meta/", "/%61pi/meta", "/../access-token", "/dashboard.js/")) {
                assertEquals(404, fixture.get(path, true).statusCode(), path);
            }
            assertEquals(400, fixture.get("/dashboard.js?token=anything", false).statusCode());
        }
    }

    @Test
    void rejectsCrossOriginHostDuplicatesCookiesAndUrlCredentials() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            assertEquals(403, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: attacker.example\r\nAuthorization: Bearer " + fixture.token() + "\r\n"));
            assertEquals(403, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: " + fixture.authority() + "\r\nOrigin: https://attacker.example\r\n"));
            assertEquals(403, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: " + fixture.authority() + "\r\nOrigin: null\r\n"));
            assertEquals(403, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: " + fixture.authority() + "\r\nSec-Fetch-Site: cross-site\r\n"));
            assertEquals(401, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: " + fixture.authority() + "\r\nCookie: token=" + fixture.token() + "\r\n"));
            assertEquals(401, fixture.get("/api/meta?token=" + fixture.token(), false).statusCode());
            assertEquals(401, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: " + fixture.authority() + "\r\nAuthorization: Bearer " + fixture.token() + "\r\nAuthorization: Bearer " + fixture.token() + "\r\n"));
        }
    }

    @Test
    void rejectsMethodsBodiesOversizedAndAmbiguousQueries() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            for (String method : List.of("POST", "PUT", "DELETE", "OPTIONS", "HEAD")) {
                assertEquals(405, fixture.raw(method + " /api/meta HTTP/1.1\r\nHost: " + fixture.authority() + "\r\n"));
            }
            assertEquals(400, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: " + fixture.authority() + "\r\nContent-Length: 1\r\n", "x"));
            assertEquals(400, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: " + fixture.authority() + "\r\nTransfer-Encoding: chunked\r\n", "0\r\n\r\n"));
            for (String query : List.of("x=1", "period=day&period=week", "period=day&%70eriod=week", "period=%00", "period=" + "x".repeat(129), "page=-1", "page=99999999999999")) {
                assertEquals(400, fixture.get("/api/players?" + query, true).statusCode(), query);
            }
            assertEquals(400, fixture.get("/api/players?q=" + "x".repeat(2100), true).statusCode());
        }
    }

    @Test
    void headRejectionReturnsNoBodyWithoutTransportWarnings() throws Exception {
        Logger transport = Logger.getLogger("com.sun.net.httpserver");
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        transport.addHandler(handler);
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + fixture.authority() + "/api/meta"))
                    .timeout(Duration.ofSeconds(5)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = fixture.client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode());
            assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
            assertTrue(response.body().isEmpty());
            assertTrue(warnings.isEmpty(), () -> warnings.toString());
        } finally {
            transport.removeHandler(handler);
            handler.close();
        }
    }

    @Test
    void routesValidatedArgumentsAndDistinguishesUnknownProfile() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            UUID uuid = UUID.randomUUID();
            JsonObject result = fixture.result;
            when(fixture.data.overview(StatsPeriod.DAY, 0)).thenReturn(CompletableFuture.completedFuture(result));
            when(fixture.data.rankings(StatKey.ONLINE_TIME, StatsPeriod.LIFETIME, 0, 25)).thenReturn(CompletableFuture.completedFuture(result));
            when(fixture.data.players("Ada Smith", 2, 10)).thenReturn(result);
            when(fixture.data.profile(uuid, StatsPeriod.WEEK)).thenReturn(Optional.empty());
            fixture.start();
            assertEquals(200, fixture.get("/api/overview", true).statusCode());
            assertEquals(200, fixture.get("/api/rankings", true).statusCode());
            assertEquals(200, fixture.get("/api/players?q=Ada+Smith&page=2&size=10", true).statusCode());
            assertEquals(404, fixture.get("/api/profile?uuid=" + uuid + "&period=week", true).statusCode());
            assertEquals(400, fixture.get("/api/profile?uuid=1-1-1-1-1", true).statusCode());
            verify(fixture.data).players("Ada Smith", 2, 10);
        }
    }

    @Test
    void warmingHasFiniteWaitAndDoesNotCancelSharedWork() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            CompletableFuture<JsonObject> pending = new CompletableFuture<>();
            when(fixture.data.overview(StatsPeriod.DAY, 0)).thenReturn(pending);
            fixture.start();
            HttpResponse<String> response = fixture.get("/api/overview", true);
            assertEquals(202, response.statusCode());
            assertTrue(response.body().contains("warming"));
            assertFalse(pending.isCancelled());
            pending.complete(fixture.result);
            assertEquals(200, fixture.get("/api/overview", true).statusCode());
        }
    }

    @Test
    void operatorReportsRouteDefaultsAndExplicitSelections() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            UUID player = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            when(fixture.data.detailRankings(DetailKey.MINED_DIAMOND_ORE, 0, 25))
                    .thenReturn(CompletableFuture.completedFuture(fixture.result));
            when(fixture.data.seasons(0, 25)).thenReturn(fixture.result);
            when(fixture.data.season("spring-26", 2, 50))
                    .thenReturn(CompletableFuture.completedFuture(Optional.of(fixture.result)));
            when(fixture.data.milestones(player, 1, 10)).thenReturn(Optional.of(fixture.result));
            when(fixture.data.comparison(player, Optional.empty(), StatKey.ONLINE_TIME, StatsPeriod.DAY, 12))
                    .thenReturn(Optional.of(fixture.result));
            when(fixture.data.comparison(player, Optional.of(other), StatKey.BLOCKS_BROKEN, StatsPeriod.MONTH, 3))
                    .thenReturn(Optional.of(fixture.result));
            fixture.start();
            for (String route : List.of("/api/detail-rankings?counter=mined_diamond_ore", "/api/seasons",
                    "/api/season?id=spring-26&page=2&size=50", "/api/milestones?uuid=" + player + "&page=1&size=10",
                    "/api/comparison?uuid=" + player + "&stat=online_time",
                    "/api/comparison?uuid=" + player + "&other=" + other + "&stat=blocks_broken&period=month&count=3")) {
                HttpResponse<String> response = fixture.get(route, true);
                assertEquals(200, response.statusCode(), route);
                assertEquals(fixture.result.toString(), response.body());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            }
            verify(fixture.data).detailRankings(DetailKey.MINED_DIAMOND_ORE, 0, 25);
            verify(fixture.data).seasons(0, 25);
            verify(fixture.data).season("spring-26", 2, 50);
            verify(fixture.data).milestones(player, 1, 10);
            verify(fixture.data).comparison(player, Optional.empty(), StatKey.ONLINE_TIME, StatsPeriod.DAY, 12);
            verify(fixture.data).comparison(player, Optional.of(other), StatKey.BLOCKS_BROKEN, StatsPeriod.MONTH, 3);
        }
    }

    @Test
    void malformedOperatorReportQueriesAreRejectedBeforeDataAccess() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            String player = UUID.randomUUID().toString();
            String comparison = "/api/comparison?uuid=" + player + "&stat=online_time";
            fixture.start();
            clearInvocations(fixture.data);
            for (String route : List.of("/api/detail-rankings", "/api/detail-rankings?counter=unknown",
                    "/api/detail-rankings?counter=mined_diamond_ore&period=day",
                    "/api/seasons?size=0", "/api/seasons?size=51", "/api/seasons?page=-1",
                    "/api/season", "/api/season?id=Uppercase", "/api/season?id=../secret",
                    "/api/season?id=" + "a".repeat(49), "/api/season?id=spring&size=51",
                    "/api/milestones", "/api/milestones?uuid=1-1-1-1-1", "/api/milestones?uuid=" + player + "&size=0",
                    "/api/comparison?uuid=" + player, "/api/comparison?stat=online_time",
                    comparison + "&other=", comparison + "&other=1-1-1-1-1", comparison + "&period=lifetime",
                    comparison + "&count=0", comparison + "&count=13", comparison + "&count=999999999",
                    comparison + "&uuid=" + player, comparison + "&token=ignored")) {
                assertEquals(400, fixture.get(route, true).statusCode(), route);
            }
            verifyNoInteractions(fixture.data);
        }
    }

    @Test
    void unknownReportResourcesReturnUniformNotFoundAndPublicAliasesStayAbsent() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            UUID player = UUID.randomUUID();
            when(fixture.data.season("missing", 0, 25)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
            when(fixture.data.milestones(player, 0, 25)).thenReturn(Optional.empty());
            when(fixture.data.comparison(player, Optional.empty(), StatKey.ONLINE_TIME, StatsPeriod.DAY, 12))
                    .thenReturn(Optional.empty());
            fixture.server.start(fixture.publicSettings(true));
            for (String route : List.of("/api/season?id=missing", "/api/milestones?uuid=" + player,
                    "/api/comparison?uuid=" + player + "&stat=online_time")) {
                HttpResponse<String> response = fixture.get(route, true);
                assertEquals(404, response.statusCode(), route);
                assertEquals("{\"error\":\"not_found\"}", response.body());
            }
            for (String route : List.of("detail-rankings", "seasons", "season", "milestones", "comparison")) {
                assertEquals(401, fixture.get("/api/" + route, false).statusCode());
                assertEquals(404, fixture.get("/api/public/" + route, false).statusCode());
                assertEquals(404, fixture.get("/api/" + route + "/", true).statusCode());
                assertEquals(404, fixture.get("/%61pi/" + route, true).statusCode());
            }
        }
    }

    @Test
    void reportRankingWarmupIsBoundedAndRetainsSharedFutures() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            CompletableFuture<JsonObject> detail = new CompletableFuture<>();
            CompletableFuture<Optional<JsonObject>> season = new CompletableFuture<>();
            when(fixture.data.detailRankings(DetailKey.MINED_DIAMOND_ORE, 0, 25)).thenReturn(detail);
            when(fixture.data.season("spring", 0, 25)).thenReturn(season);
            fixture.start();
            for (String route : List.of("/api/detail-rankings?counter=mined_diamond_ore", "/api/season?id=spring")) {
                HttpResponse<String> response = fixture.get(route, true);
                assertEquals(202, response.statusCode(), route);
                assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
                assertEquals("{\"error\":\"warming\"}", response.body());
            }
            assertFalse(detail.isCancelled());
            assertFalse(season.isCancelled());
            detail.complete(fixture.result);
            season.complete(Optional.of(fixture.result));
            assertEquals(200, fixture.get("/api/detail-rankings?counter=mined_diamond_ore", true).statusCode());
            assertEquals(200, fixture.get("/api/season?id=spring", true).statusCode());
        }
    }

    @Test
    void responseLimitAndFailedQueriesDoNotLeakExceptionData() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            JsonObject enormous = new JsonObject();
            enormous.addProperty("data", "x".repeat(2 * 1024 * 1024));
            when(fixture.data.metadata()).thenReturn(enormous);
            fixture.start();
            assertEquals(503, fixture.get("/api/meta", true).statusCode());
            when(fixture.data.metadata()).thenThrow(new IllegalStateException("sensitive internal text"));
            HttpResponse<String> response = fixture.get("/api/meta", true);
            assertEquals(503, response.statusCode());
            assertFalse(response.body().contains("sensitive"));
        }
    }

    @Test
    void failedConfigurationPublicationPreservesListenerAndReleasesCandidatePort() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            int replacementPort = availablePort();
            DashboardSettings replacement = new DashboardSettings(true, replacementPort, "http://127.0.0.1:" + replacementPort, false);
            assertThrows(IllegalStateException.class, () -> fixture.server.reconfigure(replacement, () -> {
                assertEquals(503, uncheckedGet(replacement.publicOrigin(), fixture));
                throw new IllegalStateException("rejected");
            }));
            assertEquals(fixture.port, fixture.server.status().port());
            assertEquals(200, fixture.get("/api/meta", true).statusCode());
            try (ServerSocket released = new ServerSocket()) {
                released.bind(new InetSocketAddress("127.0.0.1", replacementPort));
            }
        }
    }

    @Test
    void occupiedPortDoesNotPublishConfigurationOrStopPreviousListener() throws Exception {
        try (Fixture fixture = new Fixture(directory); ServerSocket occupied = new ServerSocket(0)) {
            fixture.start();
            AtomicBoolean published = new AtomicBoolean();
            DashboardSettings candidate = new DashboardSettings(true, occupied.getLocalPort(), "http://localhost:" + occupied.getLocalPort(), false);
            assertThrows(IOException.class, () -> fixture.server.reconfigure(candidate, () -> published.set(true)));
            assertFalse(published.get());
            assertEquals(200, fixture.get("/api/meta", true).statusCode());
        }
    }

    @Test
    void originUpdatesOnlyAfterPublicationAndProxyMustPreserveHost() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            DashboardSettings proxy = new DashboardSettings(true, fixture.port, "https://stats.example.org", false);
            assertThrows(IllegalStateException.class, () -> fixture.server.reconfigure(proxy, () -> {
                throw new IllegalStateException("rejected");
            }));
            assertEquals(200, fixture.get("/api/meta", true).statusCode());
            fixture.server.reconfigure(proxy, () -> assertEquals(200, uncheckedGet(fixture)));
            assertEquals(403, fixture.get("/api/meta", true).statusCode());
            assertEquals(200, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: stats.example.org\r\nOrigin: https://stats.example.org\r\nAuthorization: Bearer " + fixture.token() + "\r\n"));
        }
    }

    @Test
    void disableReenableReadsRotatedTokenAndCloseReleasesPort() throws Exception {
        Fixture fixture = new Fixture(directory);
        try (fixture) {
            fixture.start();
            String previous = fixture.token();
            fixture.server.reconfigure(fixture.settings(false), () -> { });
            assertFalse(fixture.server.status().running());
            Files.delete(fixture.tokenFile());
            fixture.start();
            assertNotEquals(previous, fixture.token());
            assertEquals(401, fixture.raw("GET /api/meta HTTP/1.1\r\nHost: " + fixture.authority() + "\r\nAuthorization: Bearer " + previous + "\r\n"));
        }
        try (ServerSocket released = new ServerSocket()) {
            released.bind(new InetSocketAddress("127.0.0.1", fixture.port));
        }
    }

    @Test
    void rejectedDisableLeavesAuthenticatedListenerActive() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            assertThrows(IllegalStateException.class, () -> fixture.server.reconfigure(fixture.settings(false), () -> {
                throw new IllegalStateException("rejected");
            }));
            assertTrue(fixture.server.status().running());
            assertEquals(200, fixture.get("/api/meta", true).statusCode());
        }
    }

    @Test
    void successfulPortMoveReleasesOldSocketAndPreservesCredential() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            String token = fixture.token();
            int replacementPort = availablePort();
            DashboardSettings replacement = new DashboardSettings(true, replacementPort, "http://127.0.0.1:" + replacementPort, false);
            fixture.server.reconfigure(replacement, () -> { });
            assertEquals(replacementPort, fixture.server.status().port());
            assertEquals(token, fixture.token());
            HttpRequest request = HttpRequest.newBuilder(URI.create(replacement.publicOrigin() + "/api/meta"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).GET().build();
            assertEquals(200, fixture.client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            try (ServerSocket released = new ServerSocket()) {
                released.bind(new InetSocketAddress("127.0.0.1", fixture.port));
            }
        }
    }

    @Test
    void invalidCredentialRejectsEnableWithoutPublishing() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            Files.createDirectories(fixture.tokenFile().getParent());
            Files.writeString(fixture.tokenFile(), "too-short");
            AtomicBoolean published = new AtomicBoolean();
            assertThrows(IOException.class, () -> fixture.server.reconfigure(fixture.settings(true), () -> published.set(true)));
            assertFalse(published.get());
            assertFalse(fixture.server.status().running());
            assertEquals("too-short", Files.readString(fixture.tokenFile()));
        }
    }

    @Test
    void burstRateLimitBoundsUnauthenticatedTraffic() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            boolean limited = false;
            for (int index = 0; index < 500; index++) {
                if (fixture.get("/api/meta", false).statusCode() == 429) {
                    limited = true;
                    break;
                }
            }
            assertTrue(limited);
        }
    }

    @Test
    void publicPagesAreDisabledByDefaultWithoutWeakeningOperatorAuthentication() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            UUID uuid = UUID.randomUUID();
            fixture.start();
            for (String path : List.of("/player/" + uuid, "/public.js", "/api/public/messages", "/api/public/profile?uuid=" + uuid)) {
                HttpResponse<String> response = fixture.get(path, false);
                assertEquals(404, response.statusCode(), path);
                assertEquals("{\"error\":\"not_found\"}", response.body());
                assertEquals(404, fixture.get(path, true).statusCode(), path);
            }
            assertEquals(401, fixture.get("/api/profile?uuid=" + uuid, false).statusCode());
            assertEquals(200, fixture.get("/api/meta", true).statusCode());
        }
    }

    @Test
    void enabledPublicRoutesUseOnlyThePublicProjectionAndNeverRequireOrExpandForToken() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            UUID uuid = UUID.randomUUID();
            JsonObject profile = new JsonObject();
            profile.addProperty("uuid", uuid.toString());
            profile.addProperty("name", "VisiblePlayer");
            when(fixture.data.publicProfile(uuid)).thenReturn(Optional.of(profile));
            fixture.server.start(fixture.publicSettings(true));
            HttpResponse<String> publicResponse = fixture.get("/api/public/profile?uuid=" + uuid, false);
            assertEquals(200, publicResponse.statusCode());
            assertEquals(profile.toString(), publicResponse.body());
            assertEquals(publicResponse.body(), fixture.get("/api/public/profile?uuid=" + uuid, true).body());
            assertEquals("no-store", publicResponse.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("noindex, nofollow, noarchive", publicResponse.headers().firstValue("X-Robots-Tag").orElseThrow());
            assertEquals(200, fixture.get("/player/" + uuid, false).statusCode());
            assertEquals(200, fixture.get("/public.js", false).statusCode());
            assertEquals(200, fixture.get("/api/public/messages", false).statusCode());
            for (String route : List.of("meta", "overview", "rankings", "players", "profile", "messages",
                    "detail-rankings", "seasons", "season", "milestones", "comparison")) {
                assertEquals(401, fixture.get("/api/" + route, false).statusCode());
            }
        }
    }

    @Test
    void privateAndUnknownPublicProfilesHaveTheSameUnavailableResponseAndNoCachedDisclosure() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            UUID privateId = UUID.randomUUID();
            UUID unknownId = UUID.randomUUID();
            when(fixture.data.publicProfile(privateId)).thenReturn(Optional.of(fixture.result));
            when(fixture.data.publicProfile(unknownId)).thenReturn(Optional.empty());
            fixture.server.start(fixture.publicSettings(true));
            assertEquals(200, fixture.get("/api/public/profile?uuid=" + privateId, false).statusCode());
            when(fixture.data.publicProfile(privateId)).thenReturn(Optional.empty());
            HttpResponse<String> hidden = fixture.get("/api/public/profile?uuid=" + privateId, false);
            HttpResponse<String> missing = fixture.get("/api/public/profile?uuid=" + unknownId, false);
            assertEquals(404, hidden.statusCode());
            assertEquals(missing.statusCode(), hidden.statusCode());
            assertEquals(missing.body(), hidden.body());
            assertEquals(404, fixture.get("/api/public/profile?uuid=" + privateId, true).statusCode());
        }
    }

    @Test
    void publicShareLinksPermitOnlyTopLevelNavigationAcrossSites() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            UUID uuid = UUID.randomUUID();
            fixture.server.start(fixture.publicSettings(true));
            String headers = " HTTP/1.1\r\nHost: " + fixture.authority()
                    + "\r\nSec-Fetch-Site: cross-site\r\nSec-Fetch-Mode: navigate\r\nSec-Fetch-Dest: document\r\n";
            assertEquals(200, fixture.raw("GET /player/" + uuid + headers));
            assertEquals(403, fixture.raw("GET /api/public/profile?uuid=" + uuid + headers));
            assertEquals(403, fixture.raw("GET /api/public/messages" + headers));
            assertEquals(403, fixture.raw("GET /public.js" + headers));
            assertEquals(403, fixture.raw("GET /" + headers));
            assertEquals(403, fixture.raw("GET /player/" + uuid + headers + "Origin: https://other.example\r\n"));
            assertEquals(403, fixture.raw("GET /player/" + uuid + headers.replace("document", "iframe")));
            assertEquals(403, fixture.raw("GET /player/" + uuid + headers.replace("navigate", "cors")));
            assertEquals(403, fixture.raw("GET /player/" + uuid + headers.replace(fixture.authority(), "other.example")));
        }
    }

    @Test
    void publicRoutesRejectAliasesTraversalAdditionalFiltersAndMutations() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            String uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            fixture.server.start(fixture.publicSettings(true));
            for (String path : List.of("/player/", "/player/" + uuid + "/", "/player/" + uuid.toUpperCase(),
                    "/player/%61" + uuid.substring(1), "/public.html", "/api/public/players", "/api/public/rankings",
                    "/api/public/overview", "/api/public/profile/", "/public.js/", "/player/../dashboard/access-token")) {
                assertEquals(404, fixture.get(path, false).statusCode(), path);
            }
            for (String query : List.of("uuid=" + uuid + "&period=lifetime", "uuid=" + uuid + "&uuid=" + uuid,
                    "uuid=" + uuid.toUpperCase(), "uuid=1-1-1-1-1", "uuid=" + uuid + "&token=anything")) {
                assertEquals(400, fixture.get("/api/public/profile?" + query, false).statusCode());
            }
            assertEquals(400, fixture.get("/player/" + uuid + "?token=anything", false).statusCode());
            assertEquals(405, fixture.raw("POST /api/public/profile?uuid=" + uuid + " HTTP/1.1\r\nHost: " + fixture.authority() + "\r\n"));
        }
    }

    @Test
    void disablingPublicPagesRejectsAlreadyRunningProjectionAndLeavesOperatorAccessActive() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            UUID uuid = UUID.randomUUID();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(fixture.data.publicProfile(uuid)).thenAnswer(invocation -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return Optional.of(fixture.result);
            });
            fixture.server.start(fixture.publicSettings(true));
            CompletableFuture<HttpResponse<String>> pending = fixture.client.sendAsync(
                    fixture.request("/api/public/profile?uuid=" + uuid, false), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                fixture.server.reconfigure(fixture.publicSettings(false), () -> { });
            } finally {
                release.countDown();
            }
            HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
            assertEquals(404, response.statusCode());
            assertEquals("{\"error\":\"not_found\"}", response.body());
            assertEquals(200, fixture.get("/api/meta", true).statusCode());
            assertEquals(404, fixture.get("/api/public/messages", false).statusCode());
        }
    }

    @Test
    void publicSettingChangesOnlyTakeEffectAfterSuccessfulPublication() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            assertThrows(IllegalStateException.class, () -> fixture.server.reconfigure(fixture.publicSettings(true), () -> {
                throw new IllegalStateException("rejected");
            }));
            assertEquals(404, fixture.get("/api/public/messages", false).statusCode());
            fixture.server.reconfigure(fixture.publicSettings(true), () -> { });
            assertEquals(200, fixture.get("/api/public/messages", false).statusCode());
        }
    }

    private int uncheckedGet(Fixture fixture) {
        return uncheckedGet("http://" + fixture.authority(), fixture);
    }

    private int uncheckedGet(String origin, Fixture fixture) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(origin + "/api/meta"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + fixture.token()).GET().build();
            return fixture.client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path directory;
        private final int port;
        private final DashboardData data = mock(DashboardData.class);
        private final JsonObject result = new JsonObject();
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        private final DashboardServer server;

        private Fixture(Path directory) throws IOException {
            this.directory = directory;
            port = availablePort();
            result.addProperty("test", true);
            when(data.metadata()).thenReturn(result);
            server = new DashboardServer(directory, data, () -> result, Logger.getAnonymousLogger());
        }

        private void start() throws IOException {
            server.start(settings(true));
        }

        private DashboardSettings settings(boolean enabled) {
            return new DashboardSettings(enabled, port, "http://" + authority(), false);
        }

        private DashboardSettings publicSettings(boolean publicEnabled) {
            return new DashboardSettings(true, port, "http://" + authority(), publicEnabled);
        }

        private String authority() {
            return "127.0.0.1:" + port;
        }

        private Path tokenFile() {
            return directory.resolve("dashboard/access-token");
        }

        private String token() throws IOException {
            return Files.readString(tokenFile()).strip();
        }

        private HttpRequest request(String path, boolean authenticated) throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://" + authority() + path)).timeout(Duration.ofSeconds(5));
            if (authenticated) {
                builder.header("Authorization", "Bearer " + token());
            }
            return builder.GET().build();
        }

        private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
            return client.send(request(path, authenticated), HttpResponse.BodyHandlers.ofString());
        }

        private int raw(String request) throws IOException {
            return raw(request, "");
        }

        private int raw(String request, String body) throws IOException {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write((request + "Connection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                return Integer.parseInt(response.split(" ", 3)[1]);
            }
        }

        @Override
        public void close() {
            server.close();
            client.close();
        }
    }
}
