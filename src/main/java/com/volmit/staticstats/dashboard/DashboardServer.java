package com.volmit.staticstats.dashboard;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DashboardServer implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> API_PATHS = Set.of("/api/meta", "/api/overview", "/api/rankings", "/api/players", "/api/profile", "/api/messages",
            "/api/detail-rankings", "/api/seasons", "/api/season", "/api/milestones", "/api/comparison");
    private static final Set<String> PUBLIC_API_PATHS = Set.of("/api/public/profile", "/api/public/messages");
    private static final Map<String, String> ASSET_TYPES = Map.of("/", "text/html; charset=utf-8", "/dashboard.css", "text/css; charset=utf-8", "/dashboard.js", "text/javascript; charset=utf-8", "/public.js", "text/javascript; charset=utf-8");
    private final Path dataFolder;
    private final DashboardData data;
    private final Supplier<JsonObject> messages;
    private final Logger logger;
    private volatile Listener listener;
    private boolean closed;

    public DashboardServer(Path dataFolder, DashboardData data, Supplier<JsonObject> messages, Logger logger) {
        this.dataFolder = Objects.requireNonNull(dataFolder);
        this.data = Objects.requireNonNull(data);
        this.messages = Objects.requireNonNull(messages);
        this.logger = Objects.requireNonNull(logger);
    }

    public synchronized void start(DashboardSettings settings) throws IOException {
        reconfigure(settings, () -> { });
    }

    public synchronized void reconfigure(DashboardSettings candidate, Runnable publish) throws IOException {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(publish);
        if (closed) {
            throw new IllegalStateException("Dashboard is closed");
        }
        Listener previous = listener;
        if (!candidate.enabled()) {
            publish.run();
            listener = null;
            if (previous != null) {
                previous.close();
            }
            return;
        }
        Policy policy = policy(candidate);
        if (previous != null && previous.port() == candidate.port()) {
            publish.run();
            previous.policy = policy;
            return;
        }
        Listener prepared = prepare(candidate, policy);
        try {
            publish.run();
            prepared.ready = true;
            listener = prepared;
        } catch (RuntimeException | Error failure) {
            prepared.close();
            throw failure;
        }
        if (previous != null) {
            previous.close();
        }
    }

    public Status status() {
        Listener current = listener;
        return current == null ? new Status(false, 0, "") : new Status(true, current.port(), current.policy.origin());
    }

    @Override
    public synchronized void close() {
        closed = true;
        Listener previous = listener;
        listener = null;
        if (previous != null) {
            previous.close();
        }
    }

    private Listener prepare(DashboardSettings settings, Policy policy) throws IOException {
        byte[] token = loadToken();
        Map<String, byte[]> assets = loadAssets();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", settings.port()), 32);
        Listener prepared = new Listener(server, token, assets, policy);
        try {
            server.setExecutor(prepared.executor);
            server.createContext("/", exchange -> handle(prepared, exchange));
            server.start();
            return prepared;
        } catch (RuntimeException | Error failure) {
            prepared.close();
            throw failure;
        }
    }

    private byte[] loadToken() throws IOException {
        Path directory = dataFolder.resolve("dashboard");
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Dashboard credential directory must not be a symbolic link");
        }
        Files.createDirectories(directory);
        Path file = directory.resolve("access-token");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] entropy = new byte[32];
            new SecureRandom().nextBytes(entropy);
            byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(entropy);
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.write(file, encoded, StandardOpenOption.WRITE);
            } else {
                Files.write(file, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 128) {
            throw new IOException("Invalid dashboard credential file");
        }
        String token = Files.readString(file, StandardCharsets.US_ASCII).strip();
        if (!token.matches("[A-Za-z0-9_-]{43}") || Base64.getUrlDecoder().decode(token).length != 32) {
            throw new IOException("Dashboard credential must contain a 256-bit URL-safe token");
        }
        return token.getBytes(StandardCharsets.US_ASCII);
    }

    private Map<String, byte[]> loadAssets() throws IOException {
        Map<String, byte[]> assets = new HashMap<>();
        for (String path : ASSET_TYPES.keySet()) {
            String resource = "dashboard/" + (path.equals("/") ? "index.html" : path.substring(1));
            assets.put(path, loadAsset(resource));
        }
        assets.put("public-document", loadAsset("dashboard/public.html"));
        return Map.copyOf(assets);
    }

    private byte[] loadAsset(String resource) throws IOException {
        try (InputStream input = DashboardServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing dashboard asset: " + resource);
            }
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) {
                throw new IOException("Dashboard asset exceeds size limit: " + resource);
            }
            return bytes;
        }
    }

    private Policy policy(DashboardSettings settings) {
        URI origin = URI.create(settings.publicOrigin());
        String authority = origin.getRawAuthority();
        return new Policy(settings.publicOrigin(), authority, settings.publicProfilesEnabled());
    }

    private void handle(Listener active, HttpExchange exchange) {
        ScheduledFuture<?> timeout;
        try {
            timeout = active.timeouts.schedule(exchange::close, 10, TimeUnit.SECONDS);
        } catch (RejectedExecutionException stopping) {
            exchange.close();
            return;
        }
        try {
            securityHeaders(exchange.getResponseHeaders());
            if (!active.ready) {
                error(exchange, 503, "temporarily_unavailable");
                return;
            }
            if (!active.permit()) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                error(exchange, 429, "rate_limited");
                return;
            }
            String path = Objects.requireNonNullElse(exchange.getRequestURI().getRawPath(), "");
            boolean publicDocument = publicDocument(path);
            if (!validAuthority(exchange, active.policy, publicDocument)) {
                error(exchange, 403, "origin_rejected");
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                error(exchange, 405, "method_not_allowed");
                return;
            }
            if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")
                    || !noBody(exchange.getRequestHeaders())) {
                error(exchange, 400, "request_body_rejected");
                return;
            }
            URI uri = exchange.getRequestURI();
            if (uri.isAbsolute() || uri.getRawFragment() != null || uri.toASCIIString().length() > 2048) {
                error(exchange, 400, "invalid_request");
                return;
            }
            boolean publicRequest = publicDocument || path.equals("/public.js") || PUBLIC_API_PATHS.contains(path);
            if (publicRequest && !active.policy.publicProfilesEnabled()) {
                error(exchange, 404, "not_found");
                return;
            }
            if (publicDocument) {
                if (uri.getRawQuery() != null) {
                    error(exchange, 400, "invalid_query");
                    return;
                }
                respond(exchange, 200, "text/html; charset=utf-8", active.assets.get("public-document"));
                return;
            }
            if (active.assets.containsKey(path)) {
                if (uri.getRawQuery() != null) {
                    error(exchange, 400, "invalid_query");
                    return;
                }
                respond(exchange, 200, ASSET_TYPES.get(path), active.assets.get(path));
                return;
            }
            if (!API_PATHS.contains(path) && !PUBLIC_API_PATHS.contains(path)) {
                error(exchange, 404, "not_found");
                return;
            }
            if (!publicRequest && !authenticated(exchange.getRequestHeaders(), active.token)) {
                error(exchange, 401, "authentication_required");
                return;
            }
            Map<String, String> query = parseQuery(uri.getRawQuery());
            JsonObject result = publicRequest ? publicRoute(path, query) : route(path, query);
            byte[] payload = result.toString().getBytes(StandardCharsets.UTF_8);
            if (payload.length > MAX_RESPONSE_BYTES) {
                error(exchange, 503, "response_limit");
                return;
            }
            if (publicRequest && !active.policy.publicProfilesEnabled()) {
                error(exchange, 404, "not_found");
                return;
            }
            respond(exchange, 200, "application/json; charset=utf-8", payload);
        } catch (RequestFailure expected) {
            if (expected.status == 202) {
                exchange.getResponseHeaders().set("Retry-After", "1");
            }
            safeError(exchange, expected.status, expected.code);
        } catch (IllegalArgumentException invalid) {
            safeError(exchange, 400, "invalid_query");
        } catch (IOException disconnected) {
            exchange.close();
        } catch (RuntimeException failure) {
            if (active.recordFailure()) {
                logger.log(Level.SEVERE, "Static dashboard request failed", failure);
            }
            safeError(exchange, 503, "temporarily_unavailable");
        } finally {
            timeout.cancel(false);
            exchange.close();
        }
    }

    private JsonObject route(String path, Map<String, String> query) {
        return switch (path) {
            case "/api/messages" -> {
                queryKeys(query, Set.of());
                yield messages.get();
            }
            case "/api/meta" -> {
                queryKeys(query, Set.of());
                yield data.metadata();
            }
            case "/api/overview" -> {
                queryKeys(query, Set.of("period", "offset"));
                yield await(data.overview(period(query, StatsPeriod.DAY), number(query, "offset", 0)));
            }
            case "/api/rankings" -> {
                queryKeys(query, Set.of("stat", "period", "page", "size"));
                StatKey stat = StatKey.valueOf(query.getOrDefault("stat", "online_time").toUpperCase(Locale.ROOT));
                yield await(data.rankings(stat, period(query, StatsPeriod.LIFETIME), number(query, "page", 0), number(query, "size", 25)));
            }
            case "/api/players" -> {
                queryKeys(query, Set.of("q", "page", "size"));
                yield data.players(query.getOrDefault("q", ""), number(query, "page", 0), number(query, "size", 25));
            }
            case "/api/profile" -> {
                queryKeys(query, Set.of("uuid", "period"));
                UUID uuid = UUID.fromString(query.getOrDefault("uuid", ""));
                if (!uuid.toString().equalsIgnoreCase(query.get("uuid"))) {
                    throw new IllegalArgumentException("Invalid UUID");
                }
                yield data.profile(uuid, period(query, StatsPeriod.LIFETIME))
                        .orElseThrow(() -> new RequestFailure(404, "not_found"));
            }
            case "/api/detail-rankings" -> {
                queryKeys(query, Set.of("counter", "page", "size"));
                DetailKey counter = DetailKey.find(query.get("counter"))
                        .orElseThrow(() -> new IllegalArgumentException("Invalid counter"));
                yield await(data.detailRankings(counter, number(query, "page", 0), pageSize(query)));
            }
            case "/api/seasons" -> {
                queryKeys(query, Set.of("page", "size"));
                yield data.seasons(number(query, "page", 0), pageSize(query));
            }
            case "/api/season" -> {
                queryKeys(query, Set.of("id", "page", "size"));
                String id = query.getOrDefault("id", "");
                if (!id.matches("[a-z0-9][a-z0-9_-]{0,47}")) {
                    throw new IllegalArgumentException("Invalid season");
                }
                yield await(data.season(id, number(query, "page", 0), pageSize(query)))
                        .orElseThrow(() -> new RequestFailure(404, "not_found"));
            }
            case "/api/milestones" -> {
                queryKeys(query, Set.of("uuid", "page", "size"));
                yield data.milestones(uuid(query, "uuid"), number(query, "page", 0), pageSize(query))
                        .orElseThrow(() -> new RequestFailure(404, "not_found"));
            }
            case "/api/comparison" -> {
                queryKeys(query, Set.of("uuid", "other", "stat", "period", "count"));
                UUID uuid = uuid(query, "uuid");
                Optional<UUID> other = query.containsKey("other") ? Optional.of(uuid(query, "other")) : Optional.empty();
                StatKey stat = StatKey.valueOf(query.getOrDefault("stat", "").toUpperCase(Locale.ROOT));
                StatsPeriod period = period(query, StatsPeriod.DAY);
                if (period == StatsPeriod.LIFETIME) {
                    throw new IllegalArgumentException("Comparison requires a calendar period");
                }
                yield data.comparison(uuid, other, stat, period, boundedNumber(query, "count", 12, 1, 12))
                        .orElseThrow(() -> new RequestFailure(404, "not_found"));
            }
            default -> throw new RequestFailure(404, "not_found");
        };
    }

    private JsonObject publicRoute(String path, Map<String, String> query) {
        if (path.equals("/api/public/messages")) {
            queryKeys(query, Set.of());
            return messages.get();
        }
        queryKeys(query, Set.of("uuid"));
        String requested = query.getOrDefault("uuid", "");
        UUID uuid = UUID.fromString(requested);
        if (!uuid.toString().equals(requested)) {
            throw new IllegalArgumentException("Invalid UUID");
        }
        return data.publicProfile(uuid).orElseThrow(() -> new RequestFailure(404, "not_found"));
    }

    private boolean publicDocument(String path) {
        if (!path.startsWith("/player/") || path.length() != 44) {
            return false;
        }
        try {
            String requested = path.substring(8);
            return UUID.fromString(requested).toString().equals(requested);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException warming) {
            throw new RequestFailure(202, "warming");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new RequestFailure(503, "temporarily_unavailable");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalStateException("Dashboard statistics request failed", failed.getCause());
        }
    }

    private void queryKeys(Map<String, String> query, Set<String> allowed) {
        if (!allowed.containsAll(query.keySet())) {
            throw new IllegalArgumentException("Unknown query parameter");
        }
    }

    private StatsPeriod period(Map<String, String> query, StatsPeriod fallback) {
        return StatsPeriod.valueOf(query.getOrDefault("period", fallback.name()).toUpperCase(Locale.ROOT));
    }

    private int number(Map<String, String> query, String key, int fallback) {
        String value = query.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.matches("[0-9]{1,9}")) {
            throw new IllegalArgumentException("Invalid number");
        }
        return Integer.parseInt(value);
    }

    private UUID uuid(Map<String, String> query, String key) {
        String value = query.getOrDefault(key, "");
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid UUID");
        }
        return uuid;
    }

    private int pageSize(Map<String, String> query) {
        return boundedNumber(query, "size", 25, 1, 50);
    }

    private int boundedNumber(Map<String, String> query, String key, int fallback, int minimum, int maximum) {
        int value = number(query, key, fallback);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Query number outside allowed range");
        }
        return value;
    }

    private Map<String, String> parseQuery(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        String[] entries = raw.split("&", -1);
        if (entries.length > 8) {
            throw new IllegalArgumentException("Too many query parameters");
        }
        Map<String, String> query = new HashMap<>();
        for (String entry : entries) {
            int separator = entry.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Invalid query parameter");
            }
            String key = URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8);
            if (!key.matches("[a-zA-Z]{1,32}") || value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl)
                    || query.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Invalid query parameter");
            }
        }
        return Map.copyOf(query);
    }

    private boolean validAuthority(HttpExchange exchange, Policy policy, boolean publicDocument) {
        Headers headers = exchange.getRequestHeaders();
        List<String> host = headers.get("Host");
        if (host == null || host.size() != 1 || !host.getFirst().equalsIgnoreCase(policy.authority())) {
            return false;
        }
        List<String> origins = headers.get("Origin");
        if (origins != null && (origins.size() != 1 || !origins.getFirst().equals(policy.origin()))) {
            return false;
        }
        List<String> fetchSite = headers.get("Sec-Fetch-Site");
        if (fetchSite == null || fetchSite.size() == 1 && Set.of("same-origin", "none").contains(fetchSite.getFirst())) {
            return true;
        }
        return publicDocument && exchange.getRequestMethod().equals("GET") && fetchSite.size() == 1
                && Set.of("cross-site", "same-site").contains(fetchSite.getFirst())
                && List.of("navigate").equals(headers.get("Sec-Fetch-Mode"))
                && List.of("document").equals(headers.get("Sec-Fetch-Dest"));
    }

    private boolean noBody(Headers headers) {
        List<String> lengths = headers.get("Content-Length");
        return lengths == null || lengths.size() == 1 && lengths.getFirst().equals("0");
    }

    private boolean authenticated(Headers headers, byte[] expected) {
        List<String> values = headers.get("Authorization");
        if (values == null || values.size() != 1) {
            return false;
        }
        String value = values.getFirst();
        return value.length() == 50 && value.startsWith("Bearer ")
                && MessageDigest.isEqual(expected, value.substring(7).getBytes(StandardCharsets.US_ASCII));
    }

    private void securityHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("X-Robots-Tag", "noindex, nofollow, noarchive");
        headers.set("Connection", "close");
    }

    private void error(HttpExchange exchange, int status, String code) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("error", code);
        respond(exchange, status, "application/json; charset=utf-8", result.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void safeError(HttpExchange exchange, int status, String code) {
        try {
            error(exchange, status, code);
        } catch (IOException disconnected) {
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public record Status(boolean running, int port, String publicOrigin) { }

    private record Policy(String origin, String authority, boolean publicProfilesEnabled) { }

    private static final class RequestFailure extends RuntimeException {
        private final int status;
        private final String code;

        private RequestFailure(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }

    private static final class Listener implements AutoCloseable {
        private final HttpServer server;
        private final byte[] token;
        private final Map<String, byte[]> assets;
        private final ThreadPoolExecutor executor;
        private final ScheduledThreadPoolExecutor timeouts;
        private volatile Policy policy;
        private volatile boolean ready;
        private double allowance = 120;
        private long lastRequest = System.nanoTime();
        private long lastFailure;

        private Listener(HttpServer server, byte[] token, Map<String, byte[]> assets, Policy policy) {
            this.server = server;
            this.token = token;
            this.assets = assets;
            this.policy = policy;
            AtomicInteger sequence = new AtomicInteger();
            executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), operation -> {
                Thread thread = new Thread(operation, "Static-Dashboard-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            timeouts = new ScheduledThreadPoolExecutor(1, operation -> {
                Thread thread = new Thread(operation, "Static-Dashboard-Timeout");
                thread.setDaemon(true);
                return thread;
            });
            timeouts.setRemoveOnCancelPolicy(true);
            timeouts.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private synchronized boolean permit() {
            long now = System.nanoTime();
            allowance = Math.min(120, allowance + (now - lastRequest) / 1_000_000_000.0 * 20);
            lastRequest = now;
            if (allowance < 1) {
                return false;
            }
            allowance--;
            return true;
        }

        private synchronized boolean recordFailure() {
            long now = System.nanoTime();
            if (lastFailure != 0 && now - lastFailure < TimeUnit.MINUTES.toNanos(1)) {
                return false;
            }
            lastFailure = now;
            return true;
        }

        @Override
        public void close() {
            ready = false;
            server.stop(0);
            executor.shutdownNow();
            timeouts.shutdownNow();
        }
    }
}
