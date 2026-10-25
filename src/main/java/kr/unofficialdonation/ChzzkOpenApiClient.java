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
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authenticated CHZZK Open API session listener.
 *
 * Unlike public live-chat traffic, the official DONATION subscription also receives the
 * "테스트 알림 보내기" event from CHZZK Studio. It is deliberately optional: an access token
 * with the "후원 조회" scope must be configured locally before any request is made.
 */
final class ChzzkOpenApiClient {
    private static final String API_BASE = "https://openapi.chzzk.naver.com";
    private static final String USER_AGENT = "ChzzkDonationBridge/1.0";
    private final JavaPlugin plugin;
    private final DonationDispatcher dispatcher;
    private final EventFeed eventFeed;
    private final HttpClient http;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Set<String> channelIds = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingSubscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile String sessionKey;
    /** An OAuth token issued from the dashboard test flow; never written to disk. */
    private volatile String temporaryAccessToken;
    private volatile boolean running;

    ChzzkOpenApiClient(JavaPlugin plugin, DonationDispatcher dispatcher, EventFeed eventFeed) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
        this.eventFeed = eventFeed;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).executor(workers).build();
    }

    void start(Set<String> initialChannels) {
        channelIds.addAll(initialChannels);
        running = true;
    }

    void connect(String channelId) {
        channelIds.add(channelId);
        if (!running || !configured()) return;
        String currentSessionKey = sessionKey;
        if (currentSessionKey == null) openSession();
        else subscribeDonation(channelId, currentSessionKey);
    }

    void disconnect(String channelId) {
        channelIds.remove(channelId);
        pendingSubscriptions.remove(channelId);
        if (channelIds.isEmpty()) closeSocket();
    }

    void stop() {
        running = false;
        channelIds.clear();
        closeSocket();
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    /** Starts a test-only official session with a freshly authorized token held only in memory. */
    void startTemporaryTest(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) throw new IllegalArgumentException("치지직 액세스 토큰이 비어 있습니다.");
        temporaryAccessToken = accessToken.trim();
        closeSocket();
        openSession();
    }

    void stopTemporaryTest() {
        temporaryAccessToken = null;
        closeSocket();
    }

    boolean temporaryTestActive() { return temporaryAccessToken != null && !temporaryAccessToken.isBlank(); }
    boolean connected() { return socket != null && sessionKey != null; }

    private boolean configured() {
        return temporaryAccessToken != null && !temporaryAccessToken.isBlank();
    }

    private String accessToken() {
        if (temporaryAccessToken != null && !temporaryAccessToken.isBlank()) return temporaryAccessToken;
        return "";
    }

    private void openSession() {
        if (!running || !configured() || channelIds.isEmpty() || !connecting.compareAndSet(false, true)) return;
        workers.execute(() -> {
            try {
                String address = sessionAddress();
                URI socketUri = socketUri(address);
                WebSocket previous = socket;
                if (previous != null) previous.sendClose(WebSocket.NORMAL_CLOSURE, "새 세션으로 교체");
                sessionKey = null;
                pendingSubscriptions.clear();
                socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(socketUri, new SocketListener()).join();
                plugin.getLogger().info("[CHZZK 공식] 후원 이벤트 세션에 연결하는 중입니다.");
            } catch (Exception exception) {
                String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                plugin.getLogger().warning("[CHZZK 공식] 후원 이벤트 세션을 열지 못했습니다: " + safeMessage(message));
                scheduleReconnect();
            } finally {
                connecting.set(false);
            }
        });
    }

    private String sessionAddress() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + "/open/v1/sessions/auth"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + accessToken())
                .header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("세션 주소 요청이 HTTP " + response.statusCode() + "로 거부되었습니다"
                    + apiErrorDetail(response.body()) + ".");
        }
        Map<String, Object> root = Json.object(Json.parse(response.body()));
        Map<String, Object> payload = object(root.get("content"));
        if (payload == null) payload = object(root.get("data"));
        String address = payload == null ? null : Json.string(payload.get("url"));
        if (address == null || address.isBlank()) throw new IOException("치지직이 세션 연결 주소를 반환하지 않았습니다. 후원 조회 권한을 확인해 주세요.");
        return address;
    }

    /** The returned URL carries auth; Engine.IO v3 websocket transport is used by CHZZK's Socket.IO v2 server. */
    private static URI socketUri(String sessionAddress) {
        URI source = URI.create(sessionAddress);
        String query = source.getRawQuery();
        String separator = query == null || query.isBlank() ? "" : query + "&";
        return URI.create("wss://" + source.getRawAuthority() + "/socket.io/?" + separator + "EIO=3&transport=websocket");
    }

    private void subscribeDonation(String channelId, String key) {
        if (!channelIds.contains(channelId) || !pendingSubscriptions.add(channelId)) return;
        workers.execute(() -> {
            try {
                String target = API_BASE + "/open/v1/sessions/events/subscribe/donation?sessionKey="
                        + URLEncoder.encode(key, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + accessToken()).header("User-Agent", USER_AGENT)
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("후원 이벤트 구독이 HTTP " + response.statusCode() + "로 거부되었습니다.");
                }
            } catch (Exception exception) {
                pendingSubscriptions.remove(channelId);
                plugin.getLogger().warning("[CHZZK 공식] " + channelId + " 채널 후원 이벤트를 구독하지 못했습니다: "
                        + safeMessage(exception.getMessage()));
            }
        });
    }

    private void handlePacket(String packet) {
        try {
            if ("2".equals(packet)) { send("3"); return; }
            if (!packet.startsWith("42")) return;
            List<Object> arguments = Json.array(Json.parse(packet.substring(2)));
            if (arguments.size() < 2) return;
            String eventName = Json.string(arguments.get(0));
            String bodyText = Json.string(arguments.get(1));
            if (eventName == null || bodyText == null) return;
            Map<String, Object> body = Json.object(Json.parse(bodyText));
            if ("SYSTEM".equals(eventName)) handleSystem(body);
            else if ("DONATION".equals(eventName)) handleDonation(body);
        } catch (Exception exception) {
            plugin.getLogger().warning("[CHZZK 공식] 세션 이벤트를 해석하지 못했습니다: " + safeMessage(exception.getMessage()));
        }
    }

    private void handleSystem(Map<String, Object> body) {
        String type = Json.string(body.get("type"));
        Map<String, Object> data = object(body.get("data"));
        if ("connected".equals(type) && data != null) {
            String key = Json.string(data.get("sessionKey"));
            if (key == null || key.isBlank()) return;
            sessionKey = key;
            channelIds.forEach(channelId -> subscribeDonation(channelId, key));
            return;
        }
        if (data == null || !"DONATION".equals(Json.string(data.get("eventType")))) return;
        String channelId = Json.string(data.get("channelId"));
        if (channelId == null || !channelIds.contains(channelId)) return;
        if ("subscribed".equals(type)) {
            plugin.getLogger().info("[CHZZK 공식] " + channelId + " 채널의 스튜디오 테스트 후원 수신이 준비되었습니다. 일반 후원은 기존 수신기를 사용합니다.");
        } else if ("unsubscribed".equals(type) || "revoked".equals(type)) {
            pendingSubscriptions.remove(channelId);
            plugin.getLogger().warning("[CHZZK 공식] " + channelId + " 채널의 후원 이벤트 권한이 해제되었습니다.");
        }
    }

    private void handleDonation(Map<String, Object> body) {
        String channelId = Json.string(body.get("channelId"));
        Long amount = moneyValue(body.get("payAmount"));
        if (channelId == null || amount == null || amount <= 0) return;
        if (!channelIds.contains(channelId)) {
            plugin.getLogger().warning("[CHZZK 공식] 후원 이벤트를 받았지만 연결된 플레이어가 없는 채널입니다: " + channelId);
            return;
        }
        // CHZZK's official donation payload has no documented test-event marker. This client is
        // only started from the temporary dashboard test flow, so every event it receives here
        // must be delivered instead of silently discarding Studio test messages by sender name.
        DonationType type = DonationType.parse(Json.string(body.get("donationType")));
        if (type == null) type = DonationType.CHAT;
        String donor = Json.string(body.get("donatorNickname"));
        if (donor == null || donor.isBlank()) donor = "익명";
        String message = Json.string(body.get("donationText"));
        DonationType resolvedType = type;
        String resolvedDonor = donor;
        dispatcher.submitReceive(channelId, amount, resolvedDonor, resolvedType, result -> {
            if (result.status() == 404) return;
            eventFeed.publish("donation", channelId, Map.of("nickname", resolvedDonor, "amount", amount,
                    "donationType", resolvedType.name(), "message", message == null ? "" : message,
                    "source", "CHZZK_STUDIO", "result", result.message()));
            if (dispatcher.hasOnlineEnabledReceiver(channelId, resolvedType)) {
                plugin.getLogger().info("[CHZZK 공식] " + resolvedType.koreanName() + " " + resolvedDonor + " " + amount
                        + "원 수신: " + result.message());
            }
        });
    }

    private static Long moneyValue(Object value) {
        Long number = Json.longValue(value);
        if (number != null) return number;
        if (!(value instanceof String text)) return null;
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return null;
        try { return Long.parseLong(digits); } catch (NumberFormatException ignored) { return null; }
    }

    private void closeSocket() {
        WebSocket previous = socket;
        socket = null;
        sessionKey = null;
        pendingSubscriptions.clear();
        if (previous != null) previous.sendClose(WebSocket.NORMAL_CLOSURE, "연결 종료");
    }

    private void scheduleReconnect() {
        if (!running || !configured() || channelIds.isEmpty()) return;
        scheduler.schedule(this::openSession, 5, TimeUnit.SECONDS);
    }

    private void send(String packet) {
        WebSocket current = socket;
        if (current != null) current.sendText(packet, true);
    }

    private static Map<String, Object> object(Object value) {
        try { return value == null ? null : Json.object(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private static String safeMessage(String value) {
        return value == null || value.isBlank() ? "알 수 없는 오류" : value;
    }

    private static String apiErrorDetail(String body) {
        try {
            String message = Json.string(Json.object(Json.parse(body)).get("message"));
            return message == null || message.isBlank() ? "" : " (" + message + ")";
        } catch (Exception ignored) { return ""; }
    }

    private final class SocketListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket openedSocket) {
            WebSocket.Listener.super.onOpen(openedSocket);
            openedSocket.request(1);
        }
        @Override public CompletableFuture<?> onText(WebSocket openedSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) { String packet = buffer.toString(); buffer.setLength(0); handlePacket(packet); }
            openedSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket failedSocket, Throwable error) {
            if (socket == failedSocket) {
                plugin.getLogger().warning("[CHZZK 공식] 세션 연결 오류: " + safeMessage(error.getMessage()));
                scheduleReconnect();
            }
        }
        @Override public CompletableFuture<?> onClose(WebSocket closedSocket, int statusCode, String reason) {
            if (socket == closedSocket) {
                socket = null;
                sessionKey = null;
                pendingSubscriptions.clear();
                if (running) {
                    plugin.getLogger().warning("[CHZZK 공식] 세션 연결이 종료되어 새 세션을 준비합니다.");
                    scheduleReconnect();
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
