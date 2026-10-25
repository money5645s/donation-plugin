package kr.unofficialdonation;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CI.ME Open API donation receiver.
 *
 * A user OAuth token creates a USER session, then the session subscribes to DONATION.
 * Once that subscription succeeds the public stream4j CI.ME client is stopped for the
 * channel, preventing one real donation from being executed by both protocols.
 */
final class CimeOfficialApiClient {
    private static final String API_BASE = "https://ci.me/api/openapi";
    private static final String USER_AGENT = "ChzzkDonationBridge/1.0";
    private static final long REFRESH_EARLY_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private final JavaPlugin plugin;
    private final BindingRepository bindings;
    private final DonationDispatcher dispatcher;
    private final EventFeed eventFeed;
    private final MultiPlatformLiveClient unofficialClient;
    private final HttpClient http;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Path tokenFile;
    private final Path followerFile;
    private final ConcurrentMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();
    /** Channel IDs already observed from the official followers API; persisted across restarts. */
    private final ConcurrentMap<String, Set<String>> knownFollowers = new ConcurrentHashMap<>();
    private final Set<String> followerBaselines = ConcurrentHashMap.newKeySet();
    private final RecentEventCache recentDonations = new RecentEventCache(10_000, 8_000L);
    private volatile boolean running;

    CimeOfficialApiClient(JavaPlugin plugin, BindingRepository bindings, DonationDispatcher dispatcher,
                          EventFeed eventFeed, MultiPlatformLiveClient unofficialClient) {
        this.plugin = plugin;
        this.bindings = bindings;
        this.dispatcher = dispatcher;
        this.eventFeed = eventFeed;
        this.unofficialClient = unofficialClient;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).executor(workers).build();
        this.tokenFile = plugin.getDataFolder().toPath().resolve("cime-official-tokens.json");
        this.followerFile = plugin.getDataFolder().toPath().resolve("cime-followers.json");
        loadAccounts();
        loadFollowers();
    }

    void start() {
        running = true;
        for (Account account : accounts.values()) {
            if (bindings.findChannel(StreamingPlatform.CIME, account.channelId()).isEmpty()) {
                accounts.remove(key(account.channelId()), account);
                continue;
            }
            open(account.channelId());
        }
        saveAccountsQuietly();
    }

    /** Saves the OAuth refresh token and begins an authenticated official donation session. */
    void connect(String channelId, Tokens tokens) {
        if (channelId == null || channelId.isBlank() || tokens == null || tokens.accessToken().isBlank()) return;
        accounts.put(key(channelId), new Account(channelId, tokens));
        Connection existing = connections.get(key(channelId));
        if (existing != null) existing.invalidateSession();
        saveAccountsQuietly();
        open(channelId);
    }

    /** Removes official credentials only once no Minecraft player still uses this channel. */
    void disconnectUnused(String channelId) {
        if (channelId == null || !bindings.findChannel(StreamingPlatform.CIME, channelId).isEmpty()) return;
        Account account = accounts.remove(key(channelId));
        Connection connection = connections.remove(key(channelId));
        if (connection != null) connection.close();
        unofficialClient.setCimeOfficialActive(channelId, false);
        if (account != null) saveAccountsQuietly();
    }

    void stop() {
        running = false;
        connections.values().forEach(Connection::close);
        connections.clear();
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    private void open(String channelId) {
        if (!running) return;
        Account account = accounts.get(key(channelId));
        if (account == null) return;
        Connection connection = connections.computeIfAbsent(key(channelId), ignored -> new Connection(account.channelId()));
        if (!connection.connecting.compareAndSet(false, true)) return;
        workers.execute(() -> {
            try {
                if (connection.reconnectable()) {
                    connection.replaceSocket(connection.address);
                    unofficialClient.setCimeOfficialActive(connection.channelId, true);
                    plugin.getLogger().info("[씨미 공식] " + connection.channelId + " 채널의 기존 후원 이벤트 세션에 다시 연결했습니다.");
                    return;
                }
                Tokens tokens = currentTokens(account.channelId());
                String address = sessionAddress(tokens.accessToken());
                connection.replaceSocket(address);
                subscribeDonation(connection.channelId, tokens.accessToken(), connection.sessionKey(address));
                unofficialClient.setCimeOfficialActive(connection.channelId, true);
                scheduleFollowerPolling(connection.channelId);
                plugin.getLogger().info("[씨미 공식] " + connection.channelId + " 채널의 실제 후원 이벤트 수신이 준비되었습니다. 비공식 수신기는 일시 중지합니다.");
            } catch (Exception exception) {
                String message = safeMessage(rootMessage(exception));
                plugin.getLogger().warning("[씨미 공식] " + channelId + " 채널 후원 이벤트 세션을 열지 못했습니다: " + message);
                // An unsuccessful official connection must never silence the existing public receiver.
                unofficialClient.setCimeOfficialActive(channelId, false);
                // FOLLOW is a separate REST scope. Keep checking it even if DONATION was not approved.
                scheduleFollowerPolling(channelId);
                scheduleReconnect(channelId);
            } finally {
                connection.connecting.set(false);
            }
        });
    }

    private Tokens currentTokens(String channelId) throws IOException, InterruptedException {
        Account account = accounts.get(key(channelId));
        if (account == null) throw new IOException("연결된 씨미 OAuth 토큰이 없습니다.");
        Tokens tokens = account.tokens();
        if (tokens.expiresAtMillis() > System.currentTimeMillis() + REFRESH_EARLY_MILLIS) return tokens;
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) throw new IOException("씨미 로그인 토큰이 만료되었습니다. 다시 로그인해 주세요.");
        String clientId = plugin.getConfig().getString("cime.open-api.client-id", "");
        String clientSecret = plugin.getConfig().getString("cime.open-api.client-secret", "");
        if (clientId.isBlank() || clientSecret.isBlank()) throw new IOException("씨미 앱 정보가 등록되어 있지 않습니다.");
        Map<String, Object> body = Map.of("grantType", "refresh_token", "clientId", clientId,
                "clientSecret", clientSecret, "refreshToken", tokens.refreshToken());
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + "/auth/v1/token"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("토큰 갱신이 HTTP " + response.statusCode() + "로 거부되었습니다" + apiError(response.body()));
        }
        Tokens refreshed = parseTokens(response.body(), tokens.refreshToken());
        accounts.put(key(channelId), new Account(account.channelId(), refreshed));
        saveAccountsQuietly();
        return refreshed;
    }

    private String sessionAddress(String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + "/open/v1/sessions/auth"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("세션 주소 요청이 HTTP " + response.statusCode() + "로 거부되었습니다" + apiError(response.body()));
        }
        Map<String, Object> root = Json.object(Json.parse(response.body()));
        Map<String, Object> content = object(root.get("content"));
        if (content == null) content = object(root.get("data"));
        if (content == null) content = root;
        String url = Json.string(content.get("url"));
        if (url == null || url.isBlank()) throw new IOException("씨미가 세션 연결 주소를 반환하지 않았습니다. 후원 조회 권한을 확인해 주세요.");
        return url;
    }

    private void subscribeDonation(String channelId, String accessToken, String sessionKey) throws IOException, InterruptedException {
        String target = API_BASE + "/open/v1/sessions/events/subscribe/donation?sessionKey="
                + URLEncoder.encode(sessionKey, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken).header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("후원 이벤트 구독이 HTTP " + response.statusCode() + "로 거부되었습니다" + apiError(response.body()));
        }
    }

    /** CI.ME currently exposes followers as a paged REST list rather than a WebSocket event. */
    private void scheduleFollowerPolling(String channelId) {
        if (!plugin.getConfig().getBoolean("cime.follow.enabled", true)) return;
        Connection connection = connections.get(key(channelId));
        if (connection == null || connection.followTask != null) return;
        long interval = Math.max(30L, plugin.getConfig().getLong("cime.follow.poll-interval-seconds", 60L));
        connection.followTask = scheduler.scheduleWithFixedDelay(() -> workers.execute(() -> pollFollowers(channelId)),
                1L, interval, TimeUnit.SECONDS);
    }

    private void pollFollowers(String channelId) {
        try {
            Tokens tokens = currentTokens(channelId);
            List<Follower> followers = fetchFollowers(tokens.accessToken());
            Set<String> current = new LinkedHashSet<>();
            for (Follower follower : followers) current.add(follower.channelId());
            String channelKey = key(channelId);
            Set<String> currentSnapshot = Set.copyOf(current);
            Set<String> previous = knownFollowers.put(channelKey, currentSnapshot);
            boolean baseline = followerBaselines.add(channelKey);
            // Persist only when the baseline or follower list changed; polling itself must not cause disk I/O every minute.
            if (baseline || !currentSnapshot.equals(previous)) saveFollowersQuietly();
            // Existing followers must never all trigger when a streamer links the plugin for the first time.
            if (baseline) {
                plugin.getLogger().info("[씨미 공식] " + channelId + " 채널 팔로워 " + current.size()
                        + "명을 기준선으로 저장했습니다. 다음 새 팔로우부터 규칙을 실행합니다.");
                return;
            }
            Set<String> old = previous == null ? Set.of() : previous;
            for (Follower follower : followers) {
                if (old.contains(follower.channelId())) continue;
                dispatcher.submitFollow(StreamingPlatform.CIME, channelId, follower.channelName(), result -> {
                    eventFeed.publish("follow", channelId, Map.of("nickname", follower.channelName(), "result", result.message(),
                            "platform", StreamingPlatform.CIME.name(), "source", "CIME_OFFICIAL"));
                    if (result.accepted()) plugin.getLogger().info("[씨미 공식] " + follower.channelName() + " 팔로우: " + result.message());
                });
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("[씨미 공식] " + channelId + " 채널 팔로워 목록을 읽지 못했습니다: " + safeMessage(rootMessage(exception))
                    + " (씨미 앱에 READ:CHANNEL 권한을 추가한 뒤 다시 로그인하세요.)");
        }
    }

    private List<Follower> fetchFollowers(String accessToken) throws IOException, InterruptedException {
        List<Follower> result = new ArrayList<>();
        // The documented list is oldest-first. Read every page so a newly appended follower
        // cannot be missed on channels with more than 100 followers.
        for (int page = 0; page < 1_000; page++) {
            URI uri = URI.create(API_BASE + "/open/v1/channels/followers?page=" + page + "&size=100");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + accessToken).header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("팔로워 목록 요청이 HTTP " + response.statusCode() + "로 거부되었습니다" + apiError(response.body()));
            }
            Map<String, Object> root = Json.object(Json.parse(response.body()));
            Map<String, Object> content = object(root.get("content"));
            if (content == null) content = object(root.get("data"));
            Object data = content == null ? null : content.get("data");
            if (!(data instanceof List<?>)) break;
            List<Object> rows = Json.array(data);
            for (Object raw : rows) {
                Map<String, Object> row = object(raw);
                if (row == null) continue;
                String id = Json.string(row.get("channelId"));
                String name = Json.string(row.get("channelName"));
                if (id != null && !id.isBlank()) result.add(new Follower(id, safe(name, "익명")));
            }
            if (rows.size() < 100) return result;
        }
        throw new IOException("팔로워 목록 페이지가 너무 많아 안전하게 중단했습니다.");
    }

    private void handleEvent(String channelId, String text) {
        try {
            Map<String, Object> root = Json.object(Json.parse(text));
            if (!"DONATION".equalsIgnoreCase(Json.string(root.get("event")))) return;
            Map<String, Object> data = object(root.get("data"));
            if (data == null) return;
            String payloadChannelId = Json.string(data.get("channelId"));
            if (payloadChannelId != null && !payloadChannelId.isBlank() && !payloadChannelId.equals(channelId)) return;
            Long amount = money(data.get("payAmountNumber"));
            if (amount == null) amount = money(data.get("payAmount"));
            if (amount == null || amount <= 0) return;
            DonationType type = DonationType.parse(Json.string(data.get("donationType")));
            if (type == null) type = DonationType.CHAT;
            String donor = safe(Json.string(data.get("donatorNickname")), "익명");
            String message = safe(Json.string(data.get("donationText")), "");
            String eventId = safe(Json.string(data.get("donationId")), channelId + ':' + type + ':' + amount + ':' + donor + ':' + message);
            if (!recentDonations.remember(eventId)) return;
            long resolvedAmount = amount;
            DonationType resolvedType = type;
            dispatcher.submitReceive(StreamingPlatform.CIME, channelId, resolvedAmount, donor, resolvedType, result -> {
                eventFeed.publish(resolvedType == DonationType.PARTY ? "party" : "donation", channelId, Map.of(
                        "nickname", donor, "amount", resolvedAmount, "donationType", resolvedType.name(), "message", message,
                        "platform", StreamingPlatform.CIME.name(), "source", "CIME_OFFICIAL", "result", result.message()));
                if (result.status() != 404) {
                    plugin.getLogger().info("[씨미 공식] " + resolvedType.koreanName() + " " + donor + " " + resolvedAmount + "빔 수신: " + result.message());
                }
            });
        } catch (Exception exception) {
            plugin.getLogger().warning("[씨미 공식] 후원 이벤트를 해석하지 못했습니다: " + safeMessage(exception.getMessage()));
        }
    }

    private void scheduleReconnect(String channelId) {
        if (running && accounts.containsKey(key(channelId))) scheduler.schedule(() -> open(channelId), 5, TimeUnit.SECONDS);
    }

    private final class Connection {
        private final String channelId;
        private final AtomicBoolean connecting = new AtomicBoolean();
        private volatile WebSocket socket;
        private volatile String address;
        private volatile long createdAtMillis;
        private volatile ScheduledFuture<?> pingTask;
        private volatile ScheduledFuture<?> followTask;

        Connection(String channelId) { this.channelId = channelId; }

        void replaceSocket(String newAddress) {
            closeSocketOnly();
            address = newAddress;
            createdAtMillis = System.currentTimeMillis();
            socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(newAddress), new SocketListener(this)).join();
        }

        boolean reconnectable() {
            return address != null && !address.isBlank()
                    && System.currentTimeMillis() - createdAtMillis < TimeUnit.HOURS.toMillis(12);
        }

        void invalidateSession() {
            address = null;
            createdAtMillis = 0L;
            closeSocketOnly();
        }

        String sessionKey(String fallbackAddress) throws IOException {
            String value = query(URI.create(fallbackAddress).getRawQuery(), "sessionKey");
            if (value == null || value.isBlank()) throw new IOException("씨미 세션 URL에 sessionKey가 없습니다.");
            return value;
        }

        void closeSocketOnly() {
            ScheduledFuture<?> previousPing = pingTask;
            pingTask = null;
            if (previousPing != null) previousPing.cancel(false);
            WebSocket previous = socket;
            socket = null;
            if (previous != null) previous.sendClose(WebSocket.NORMAL_CLOSURE, "연결 종료");
        }

        void close() {
            ScheduledFuture<?> follow = followTask;
            followTask = null;
            if (follow != null) follow.cancel(false);
            closeSocketOnly();
        }
    }

    private final class SocketListener implements WebSocket.Listener {
        private final Connection connection;
        private final StringBuilder buffer = new StringBuilder();
        SocketListener(Connection connection) { this.connection = connection; }
        @Override public void onOpen(WebSocket socket) {
            WebSocket.Listener.super.onOpen(socket);
            socket.request(1);
            connection.pingTask = scheduler.scheduleAtFixedRate(() -> {
                if (connection.socket == socket) socket.sendText("{\"type\":\"PING\"}", true);
            }, 1, 1, TimeUnit.MINUTES);
        }
        @Override public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) { String event = buffer.toString(); buffer.setLength(0); handleEvent(connection.channelId, event); }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket socket, Throwable error) {
            if (connection.socket == socket) {
                plugin.getLogger().warning("[씨미 공식] 세션 연결 오류: " + safeMessage(error.getMessage()));
                scheduleReconnect(connection.channelId);
            }
        }
        @Override public CompletableFuture<?> onClose(WebSocket socket, int code, String reason) {
            if (connection.socket == socket && running) {
                connection.socket = null;
                ScheduledFuture<?> ping = connection.pingTask;
                connection.pingTask = null;
                if (ping != null) ping.cancel(false);
                scheduleReconnect(connection.channelId);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    static Tokens parseTokens(String responseBody, String fallbackRefreshToken) throws IOException {
        Map<String, Object> root = Json.object(Json.parse(responseBody));
        Map<String, Object> content = object(root.get("content"));
        if (content == null) content = object(root.get("data"));
        if (content == null) content = root;
        String access = Json.string(content.get("accessToken"));
        String refresh = Json.string(content.get("refreshToken"));
        Long expiresIn = Json.longValue(content.get("expiresIn"));
        if (access == null || access.isBlank()) throw new IOException("씨미가 액세스 토큰을 반환하지 않았습니다.");
        if (refresh == null || refresh.isBlank()) refresh = fallbackRefreshToken;
        long expiresAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(expiresIn == null || expiresIn <= 0 ? 86_400L : expiresIn);
        return new Tokens(access, refresh == null ? "" : refresh, expiresAt);
    }

    record Tokens(String accessToken, String refreshToken, long expiresAtMillis) { }
    private record Account(String channelId, Tokens tokens) { }
    private record Follower(String channelId, String channelName) { }

    private void loadAccounts() {
        if (Files.notExists(tokenFile)) return;
        try {
            Map<String, Object> root = Json.object(Json.parse(Files.readString(tokenFile, StandardCharsets.UTF_8)));
            Object rawAccounts = root.get("accounts");
            if (!(rawAccounts instanceof List<?>)) return;
            for (Object raw : Json.array(rawAccounts)) {
                Map<String, Object> item = Json.object(raw);
                String channelId = Json.string(item.get("channelId"));
                String access = Json.string(item.get("accessToken"));
                String refresh = Json.string(item.get("refreshToken"));
                Long expires = Json.longValue(item.get("expiresAtMillis"));
                if (channelId != null && !channelId.isBlank() && access != null && !access.isBlank()) {
                    accounts.put(key(channelId), new Account(channelId, new Tokens(access, refresh == null ? "" : refresh,
                            expires == null ? 0L : expires)));
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("씨미 공식 로그인 토큰을 읽지 못했습니다: " + safeMessage(exception.getMessage()));
        }
    }

    private void loadFollowers() {
        if (Files.notExists(followerFile)) return;
        try {
            Map<String, Object> root = Json.object(Json.parse(Files.readString(followerFile, StandardCharsets.UTF_8)));
            Map<String, Object> channels = object(root.get("channels"));
            if (channels == null) return;
            for (Map.Entry<String, Object> entry : channels.entrySet()) {
                if (!(entry.getValue() instanceof List<?>)) continue;
                Set<String> followers = Json.array(entry.getValue()).stream().map(Json::string)
                        .filter(value -> value != null && !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
                knownFollowers.put(key(entry.getKey()), followers);
                followerBaselines.add(key(entry.getKey()));
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("씨미 팔로워 기록을 읽지 못했습니다: " + safeMessage(exception.getMessage()));
        }
    }

    private synchronized void saveAccountsQuietly() {
        try {
            List<Object> values = new ArrayList<>();
            for (Account account : accounts.values()) {
                values.add(Map.of("channelId", account.channelId(), "accessToken", account.tokens().accessToken(),
                        "refreshToken", account.tokens().refreshToken(), "expiresAtMillis", account.tokens().expiresAtMillis()));
            }
            Files.createDirectories(tokenFile.getParent());
            Path temporary = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp");
            Files.writeString(temporary, Json.writePretty(Map.of("accounts", values)), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, tokenFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("씨미 공식 로그인 토큰을 저장하지 못했습니다: " + safeMessage(exception.getMessage()));
        }
    }

    private synchronized void saveFollowersQuietly() {
        try {
            Map<String, Object> channels = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : knownFollowers.entrySet()) channels.put(entry.getKey(), List.copyOf(entry.getValue()));
            Files.createDirectories(followerFile.getParent());
            Path temporary = followerFile.resolveSibling(followerFile.getFileName() + ".tmp");
            Files.writeString(temporary, Json.writePretty(Map.of("channels", channels)), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, followerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, followerFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("씨미 팔로워 기록을 저장하지 못했습니다: " + safeMessage(exception.getMessage()));
        }
    }

    private static String key(String channelId) { return channelId.toLowerCase(java.util.Locale.ROOT); }
    private static String safe(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String safeMessage(String value) { return value == null || value.isBlank() ? "알 수 없는 오류" : value; }
    private static String rootMessage(Exception exception) { return exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage(); }
    private static Map<String, Object> object(Object value) { try { return value == null ? null : Json.object(value); } catch (IllegalArgumentException ignored) { return null; } }
    private static Long money(Object value) { return Json.longValue(value); }
    private static String query(String query, String name) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int split = part.indexOf('=');
            if (split > 0 && name.equals(part.substring(0, split))) return java.net.URLDecoder.decode(part.substring(split + 1), StandardCharsets.UTF_8);
        }
        return null;
    }
    private static String apiError(String body) {
        try {
            Map<String, Object> root = Json.object(Json.parse(body));
            String message = Json.string(root.get("message"));
            if (message == null) message = Json.string(root.get("errorMessage"));
            return message == null || message.isBlank() ? "" : " (" + message + ')';
        } catch (Exception ignored) { return ""; }
    }
}
