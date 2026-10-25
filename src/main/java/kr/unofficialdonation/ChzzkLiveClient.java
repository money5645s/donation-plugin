package kr.unofficialdonation;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/**
 * Unofficial, read-only CHZZK live chat connection. It intentionally has no Naver cookie support:
 * it only listens to public live channels and never sends a chat message on the user's behalf.
 */
final class ChzzkLiveClient {
    private static final String API_BASE = "https://api.chzzk.naver.com";
    private static final String GAME_API_BASE = "https://comm-api.game.naver.com/nng_main";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private final JavaPlugin plugin;
    private final DonationDispatcher dispatcher;
    private final EventFeed eventFeed;
    private final HttpClient http;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, ChannelConnection> connections = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> recentSharedEvents = new LinkedHashSet<>();
    private final RecentEventCache recentFollowEvents = new RecentEventCache(10_000, 15_000L);
    private final int pollSeconds;
    private final boolean logChats;
    private volatile boolean running;

    ChzzkLiveClient(JavaPlugin plugin, DonationDispatcher dispatcher, EventFeed eventFeed) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
        this.eventFeed = eventFeed;
        this.pollSeconds = Math.max(10, plugin.getConfig().getInt("chzzk.poll-interval-seconds", 30));
        this.logChats = plugin.getConfig().getBoolean("chzzk.log-chat-events", false);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).executor(workers).build();
    }

    void start(Set<String> channelIds) {
        if (!plugin.getConfig().getBoolean("chzzk.auto-connect", true)) {
            plugin.getLogger().info("CHZZK direct listener is disabled in config.yml.");
            return;
        }
        running = true;
        channelIds.forEach(this::connect);
    }

    void connect(String channelId) {
        if (!running || !plugin.getConfig().getBoolean("chzzk.auto-connect", true)) return;
        ChannelConnection connection = connections.computeIfAbsent(channelId, ChannelConnection::new);
        synchronized (connection) {
            if (connection.refreshTask == null) {
                connection.refreshTask = scheduler.scheduleWithFixedDelay(
                        () -> workers.execute(() -> refresh(connection)), 0, pollSeconds, TimeUnit.SECONDS);
            }
        }
    }

    void disconnect(String channelId) {
        ChannelConnection connection = connections.remove(channelId);
        if (connection == null) return;
        synchronized (connection) {
            connection.closed = true;
            if (connection.refreshTask != null) connection.refreshTask.cancel(false);
            if (connection.pingTask != null) connection.pingTask.cancel(false);
            if (connection.socket != null) connection.socket.sendClose(WebSocket.NORMAL_CLOSURE, "logout");
            connection.socket = null;
        }
        plugin.getLogger().info("[CHZZK] Stopped listening to " + channelId + ".");
    }

    void stop() {
        running = false;
        new ArrayList<>(connections.keySet()).forEach(this::disconnect);
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    private void refresh(ChannelConnection connection) {
        if (!running || connection.closed) return;
        try {
            String chatChannelId = liveChatChannelId(connection.channelId);
            if (chatChannelId == null || chatChannelId.isBlank()) {
                synchronized (connection) {
                    if (connection.socket != null) connection.socket.sendClose(WebSocket.NORMAL_CLOSURE, "broadcast offline");
                    connection.socket = null;
                    connection.sid = null;
                }
                connection.lastStatus = "offline";
                return;
            }
            synchronized (connection) {
                if (connection.socket != null && chatChannelId.equals(connection.chatChannelId)) return;
                if (connection.socket != null) connection.socket.sendClose(WebSocket.NORMAL_CLOSURE, "channel changed");
                connection.socket = null;
                connection.chatChannelId = chatChannelId;
                connection.sid = null;
                String token = accessToken(chatChannelId);
                int server = Math.floorMod(chatChannelId.chars().sum(), 9) + 1;
                URI socketUri = URI.create("wss://kr-ss" + server + ".chat.naver.com/chat");
                connection.socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(socketUri, new SocketListener(connection, token)).join();
                connection.lastStatus = "connecting";
            }
        } catch (Exception exception) {
            connection.lastStatus = "error";
            String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
            plugin.getLogger().warning("[CHZZK] " + connection.channelId + " 채널 연결을 시도하지 못했습니다: " + message);
        }
    }

    private String liveChatChannelId(String channelId) throws IOException, InterruptedException {
        Map<String, Object> root = responseJson(API_BASE + "/polling/v2/channels/" + channelId + "/live-status");
        Map<String, Object> content = optionalObject(root.get("content"));
        return content == null ? null : Json.string(content.get("chatChannelId"));
    }

    private String accessToken(String chatChannelId) throws IOException, InterruptedException {
        Map<String, Object> root = responseJson(GAME_API_BASE + "/v1/chats/access-token?channelId=" + chatChannelId + "&chatType=STREAMING");
        Map<String, Object> content = optionalObject(root.get("content"));
        String token = content == null ? null : Json.string(content.get("accessToken"));
        if (token == null || token.isBlank()) throw new IOException("공개 채팅 접속 토큰을 받지 못했습니다. 방송이 비공개이거나 종료되었을 수 있습니다.");
        return token;
    }

    private Map<String, Object> responseJson(String address) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("HTTP " + response.statusCode());
        return Json.object(Json.parse(response.body()));
    }

    private void handleFrame(ChannelConnection connection, String frame) {
        try {
            Map<String, Object> root = Json.object(Json.parse(frame));
            Long command = Json.longValue(root.get("cmd"));
            Object rawBody = root.get("bdy");
            Map<String, Object> body = optionalObject(rawBody);
            if (command == null) return;
            if (command == 10100L && body != null) {
                connection.sid = Json.string(body.get("sid"));
                connection.lastStatus = "connected";
                if (dispatcher.hasOnlineReceiver(connection.channelId)) {
                    eventFeed.publish("system", connection.channelId, Map.of("message", "치지직 채팅 서버에 연결했습니다."));
                }
                synchronized (connection) {
                    if (connection.pingTask != null) connection.pingTask.cancel(false);
                    connection.pingTask = scheduler.scheduleAtFixedRate(
                            () -> send(connection, Map.of("cmd", 0, "ver", "2")), 20, 20, TimeUnit.SECONDS);
                }
                plugin.getLogger().info("[CHZZK] Connected to " + connection.channelId + ".");
            } else if (command == 0L) {
                send(connection, Map.of("cmd", 10000, "ver", "2"));
            } else if (command == 93101L || command == 15101L || command == 93102L) {
                handleChatPayload(connection, rawBody);
            } else if (command == 93006L) {
                logPartyOrRaw(connection, rawBody);
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("[CHZZK] 수신한 이벤트를 해석하지 못했습니다: " + exception.getMessage());
        }
    }

    private void handleChatPayload(ChannelConnection connection, Object rawBody) {
        Map<String, Object> body = optionalObject(rawBody);
        List<Object> messages;
        if (rawBody instanceof List<?>) messages = Json.array(rawBody);
        else if (body != null && body.get("messageList") instanceof List<?>) messages = Json.array(body.get("messageList"));
        else if (body != null) messages = List.of(body);
        else return;
        for (Object messageObject : messages) {
            Map<String, Object> message = optionalObject(messageObject);
            if (message == null) continue;
            Long type = Json.longValue(message.containsKey("msgTypeCode") ? message.get("msgTypeCode") : message.get("messageTypeCode"));
            if (type == null) continue;
            if (type == 10L) handleDonation(connection, message);
            else if (type == 1L) {
                if (!dispatcher.hasOnlineReceiver(connection.channelId)) continue;
                eventFeed.publish("chat", connection.channelId, Map.of("nickname", nickname(message), "message", messageText(message), "platform", "CHZZK"));
                if (logChats && dispatcher.hasOnlineEnabledReceiver(connection.channelId, DonationType.CHAT)) {
                    plugin.getLogger().info("[CHZZK CHAT] " + connection.channelId + " " + nickname(message) + ": " + messageText(message));
                }
            }
            else if (type == 11L && dispatcher.hasOnlineReceiver(connection.channelId)) handleSubscription(connection, message);
        }
    }

    private void handleDonation(ChannelConnection connection, Map<String, Object> message) {
        Map<String, Object> extras = nestedJson(message.get("extras"));
        String eventChannelId = eventChannelId(extras == null ? message : extras, connection.channelId);
        String eventId = donationEventId(extras == null ? message : extras);
        String rawId = eventId == null ? "donation-raw:" + eventChannelId + ":" + sha256(Json.write(message)) : "donation:" + eventChannelId + ":" + eventId;
        if (!rememberSharedEvent(rawId)) return;
        String donationType = extras == null ? null : Json.string(extras.get("donationType"));
        DonationType type = DonationType.parse(donationType == null ? "CHAT" : donationType);
        if (type == null) type = DonationType.CHAT;
        final DonationType resolvedType = type;
        Long amount = donationAmount(extras, message);
        if (amount == null || amount <= 0) {
            String fields = extras == null ? "없음" : String.join(", ", extras.keySet());
            String payAmount = describeAmount(extras == null ? null : extras.get("payAmount"));
            dispatcher.submit(() -> {
                boolean online = resolvedType == DonationType.PARTY ? dispatcher.hasOnlinePartyReceiver(eventChannelId) : dispatcher.hasOnlineReceiver(eventChannelId);
                if (!online) return;
                eventFeed.publish("donation-missing-amount", eventChannelId, Map.of(
                        "nickname", nickname(message), "donationType", resolvedType.name(), "message", messageText(message),
                        "result", "후원 금액을 읽지 못해 명령어를 실행하지 않았습니다.", "payAmount", payAmount));
                if (dispatcher.hasOnlineEnabledReceiver(eventChannelId, resolvedType)) {
                    plugin.getLogger().warning("[CHZZK] " + eventChannelId + " 채널에서 사용할 수 없는 후원 금액을 수신했습니다"
                            + ". payAmount=" + payAmount + "; 추가 필드: " + fields);
                }
            });
            return;
        }
        dispatcher.submitReceive(eventChannelId, amount, nickname(message), resolvedType, result -> {
            if (result.status() == 404) return;
            eventFeed.publish("donation", eventChannelId, Map.of("nickname", nickname(message), "amount", amount, "donationType", resolvedType.name(), "message", messageText(message), "platform", "CHZZK", "result", result.message()));
            if (dispatcher.hasOnlineEnabledReceiver(eventChannelId, resolvedType)) {
                plugin.getLogger().info("[CHZZK] " + resolvedType.koreanName() + " " + nickname(message) + " " + amount + " won to " + eventChannelId + ": " + result.message());
            }
        });
    }

    /** CHZZK has used several field names for a paid-chat amount across chat server revisions. */
    private static Long donationAmount(Map<String, Object> extras, Map<String, Object> message) {
        Long amount = amountFrom(extras);
        return amount != null ? amount : amountFrom(message);
    }

    private static Long amountFrom(Map<String, Object> values) {
        if (values == null) return null;
        for (String key : List.of("payAmount", "donationAmount", "paidAmount", "cashAmount", "totalAmount", "amount")) {
            Long amount = moneyValue(values.get(key));
            if (amount != null && amount > 0) return amount;
        }
        for (String key : List.of("donation", "donationInfo", "payment", "paymentInfo", "payInfo")) {
            Long amount = amountFrom(nestedJson(values.get(key)));
            if (amount != null && amount > 0) return amount;
        }
        return null;
    }

    private static Long moneyValue(Object value) {
        Long number = Json.longValue(value);
        if (number != null) return number;
        if (!(value instanceof String text)) return null;
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try { return Long.parseLong(digits); } catch (NumberFormatException ignored) { return null; }
    }

    private static String describeAmount(Object value) {
        if (value == null) return "null";
        String text = String.valueOf(value);
        if (text.length() > 80) text = text.substring(0, 80) + "…";
        return "[" + value.getClass().getSimpleName() + "] " + text;
    }

    private void handleSubscription(ChannelConnection connection, Map<String, Object> message) {
        dispatcher.submitReceiveEvent(connection.channelId, nickname(message), DonationType.SUBSCRIPTION, result -> {
            if (result.status() == 404) return;
            eventFeed.publish("subscription", connection.channelId, Map.of("nickname", nickname(message), "result", result.message()));
            if (dispatcher.hasOnlineEnabledReceiver(connection.channelId, DonationType.SUBSCRIPTION)) {
                plugin.getLogger().info("[CHZZK] Subscription event for " + connection.channelId + ": " + result.message());
            }
        });
    }

    private void logPartyOrRaw(ChannelConnection connection, Object rawBody) {
        String text = Json.write(rawBody);
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        String followKey = "CHZZK:" + connection.channelId + ':' + sha256(text);
        if (upper.contains("FOLLOW") && connection.remember(sha256(text)) && recentFollowEvents.remember(followKey)) {
            String follower = partyDonor(rawBody);
            dispatcher.submitFollow(connection.channelId, follower, result -> {
                if (result.status() == 404) return;
                eventFeed.publish("follow", connection.channelId, Map.of("nickname", follower, "result", result.message(), "platform", "CHZZK"));
                if (result.accepted()) plugin.getLogger().info("[CHZZK] " + follower + " 팔로우: " + result.message());
            });
            return;
        }
        String kind = upper.contains("MISSION") || upper.contains("PARTY") ? "mission/party" : "raw";
        if ("mission/party".equals(kind)) {
            String eventChannelId = eventChannelId(rawBody, connection.channelId);
            Long amount = partyAmount(rawBody);
            String donor = partyDonor(rawBody);
            String eventId = donationEventId(rawBody);
            // Some party frames omit donationId but are broadcast to every party channel.
            // The channel, amount and donor still identify the same simultaneous broadcast.
            String eventKey = eventId == null ? "party-fallback:" + eventChannelId + ":" + amount + ":" + donor : "party:" + eventChannelId + ":" + eventId;
            if (!rememberSharedEvent(eventKey)) return;
            if (amount != null && amount > 0) {
                dispatcher.submitReceive(eventChannelId, amount, donor, DonationType.PARTY, result -> {
                    if (result.status() == 404) return;
                    eventFeed.publish("party", eventChannelId, Map.of("nickname", donor, "amount", amount,
                            "donationType", "PARTY", "platform", "CHZZK", "result", result.message()));
                    if (dispatcher.hasOnlineEnabledReceiver(eventChannelId, DonationType.PARTY)) {
                        plugin.getLogger().info("[CHZZK] 파티후원 " + donor + " " + amount + " won to " + eventChannelId + ": " + result.message());
                    }
                });
            } else {
                dispatcher.submitReceiveEvent(eventChannelId, donor, DonationType.PARTY, result -> {
                    if (result.status() == 404) return;
                    eventFeed.publish("party", eventChannelId, Map.of("nickname", donor, "result", result.message(),
                            "message", "파티후원 금액이 없는 상태 이벤트입니다."));
                    if (dispatcher.hasOnlineEnabledReceiver(eventChannelId, DonationType.PARTY)) {
                        plugin.getLogger().info("[CHZZK] " + eventChannelId + " 채널에서 금액 없는 파티 이벤트를 수신했습니다: " + result.message());
                    }
                });
            }
        }
    }

    private static Long partyAmount(Object rawBody) { return partyAmount(rawBody, 0); }

    private static Long partyAmount(Object value, int depth) {
        if (depth > 4) return null;
        if (value instanceof List<?> values) {
            for (Object item : values) {
                Long amount = partyAmount(item, depth + 1);
                if (amount != null && amount > 0) return amount;
            }
            return null;
        }
        Map<String, Object> values = nestedJson(value);
        if (values == null) return null;
        for (String key : List.of("payAmount", "participationAmount", "fundingAmount", "donationAmount", "paidAmount", "cashAmount", "amount")) {
            Long amount = moneyValue(values.get(key));
            if (amount != null && amount > 0) return amount;
        }
        for (String key : List.of("data", "payload", "body", "extras", "partyDonation", "missionDonation", "participation", "donation", "donationInfo")) {
            Long amount = partyAmount(values.get(key), depth + 1);
            if (amount != null && amount > 0) return amount;
        }
        return null;
    }

    private static String partyDonor(Object rawBody) { return partyDonor(rawBody, 0); }

    /** A party event can be delivered to every broadcaster in the party. Prefer the channel encoded by CHZZK itself. */
    private static String eventChannelId(Object value, String fallback) {
        String channelId = nestedString(value, List.of("streamingChannelId", "channelId"), 0);
        return channelId == null || channelId.isBlank() ? fallback : channelId;
    }

    private static String donationEventId(Object value) {
        return nestedString(value, List.of("donationId", "missionDonationId", "participationId"), 0);
    }

    private static String nestedString(Object value, List<String> keys, int depth) {
        if (depth > 5) return null;
        if (value instanceof List<?>) {
            for (Object item : Json.array(value)) {
                String found = nestedString(item, keys, depth + 1);
                if (found != null && !found.isBlank()) return found;
            }
            return null;
        }
        Map<String, Object> values = nestedJson(value);
        if (values == null) return null;
        for (String key : keys) {
            String found = Json.string(values.get(key));
            if (found != null && !found.isBlank()) return found;
        }
        for (String key : List.of("data", "payload", "body", "extras", "partyDonation", "missionDonation", "participation", "donation", "donationInfo")) {
            String found = nestedString(values.get(key), keys, depth + 1);
            if (found != null && !found.isBlank()) return found;
        }
        return null;
    }

    private synchronized boolean rememberSharedEvent(String eventId) {
        if (!recentSharedEvents.add(eventId)) return false;
        while (recentSharedEvents.size() > 10_000) {
            java.util.Iterator<String> iterator = recentSharedEvents.iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    private static String partyDonor(Object value, int depth) {
        if (depth > 4) return "익명";
        if (value instanceof List<?> values) {
            for (Object item : values) {
                String donor = partyDonor(item, depth + 1);
                if (!"익명".equals(donor)) return donor;
            }
            return "익명";
        }
        Map<String, Object> values = nestedJson(value);
        if (values == null) return "익명";
        for (String key : List.of("donatorNickname", "donorNickname", "nickname", "participantNickname")) {
            String donor = Json.string(values.get(key));
            if (donor != null && !donor.isBlank()) return donor;
        }
        for (String key : List.of("profile", "donator", "donor", "participant", "data", "payload", "body", "extras")) {
            String donor = partyDonor(values.get(key), depth + 1);
            if (!"익명".equals(donor)) return donor;
        }
        return "익명";
    }

    private void send(ChannelConnection connection, Map<String, Object> payload) {
        WebSocket socket = connection.socket;
        if (socket != null) socket.sendText(Json.write(payload), true);
    }

    private String nickname(Map<String, Object> message) {
        Map<String, Object> profile = nestedJson(message.get("profile"));
        return profile == null || Json.string(profile.get("nickname")) == null ? "익명" : Json.string(profile.get("nickname"));
    }

    private String messageText(Map<String, Object> message) {
        String text = Json.string(message.get("msg"));
        return text == null ? String.valueOf(message.getOrDefault("content", "")) : text;
    }

    private static Map<String, Object> nestedJson(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            try { return Json.object(Json.parse(text)); } catch (IllegalArgumentException ignored) { return null; }
        }
        return optionalObject(value);
    }

    private static Map<String, Object> optionalObject(Object value) {
        try { return value == null ? null : Json.object(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append("%02x".formatted(b));
            return result.toString();
        } catch (Exception exception) { return value; }
    }

    private final class SocketListener implements WebSocket.Listener {
        private final ChannelConnection connection;
        private final String token;
        private final StringBuilder buffer = new StringBuilder();
        SocketListener(ChannelConnection connection, String token) { this.connection = connection; this.token = token; }
        @Override public void onOpen(WebSocket socket) {
            WebSocket.Listener.super.onOpen(socket);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accTkn", token); body.put("auth", "READ"); body.put("devType", 2001); body.put("uid", null);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("bdy", body); payload.put("cmd", 100); payload.put("tid", 1); payload.put("cid", connection.chatChannelId); payload.put("svcid", "game"); payload.put("ver", "2");
            socket.sendText(Json.write(payload), true);
            socket.request(1);
        }
        @Override public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) { String frame = buffer.toString(); buffer.setLength(0); handleFrame(connection, frame); }
            socket.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket socket, Throwable error) {
            connection.lastStatus = "error";
            plugin.getLogger().warning("[CHZZK] " + connection.channelId + " 채널의 WebSocket 오류: " + error.getMessage());
        }
        @Override public CompletableFuture<?> onClose(WebSocket socket, int statusCode, String reason) {
            synchronized (connection) { if (connection.socket == socket) connection.socket = null; connection.sid = null; if (!connection.closed) connection.lastStatus = "disconnected"; }
            synchronized (connection) { if (connection.pingTask != null) connection.pingTask.cancel(false); connection.pingTask = null; }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ChannelConnection {
        final String channelId;
        final LinkedHashSet<String> recentEvents = new LinkedHashSet<>();
        volatile WebSocket socket;
        volatile java.util.concurrent.ScheduledFuture<?> refreshTask;
        volatile java.util.concurrent.ScheduledFuture<?> pingTask;
        volatile String chatChannelId;
        volatile String sid;
        volatile String lastStatus = "new";
        volatile boolean closed;
        ChannelConnection(String channelId) { this.channelId = channelId; }
        synchronized boolean remember(String eventId) {
            if (!recentEvents.add(eventId)) return false;
            while (recentEvents.size() > 5_000) {
                java.util.Iterator<String> iterator = recentEvents.iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            return true;
        }
    }
}
