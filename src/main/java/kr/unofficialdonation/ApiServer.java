package kr.unofficialdonation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.InputStream;
import java.io.OutputStream;

/** Private HTTP API called by an unofficial Chzzk event collector or relay. */
final class ApiServer {
    private static final String DEFAULT_TOKEN = "CHANGE_THIS_TO_A_LONG_RANDOM_SECRET";
    private static final String DEFAULT_OAUTH_PUBLIC_ORIGIN = "http://apicallback.chorokstudio.net:8766";
    private final JavaPlugin plugin;
    private final BindingRepository bindings;
    private final DonationDispatcher dispatcher;
    private final ChzzkLiveClient chzzkLiveClient;
    private final ChzzkOpenApiClient chzzkOpenApiClient;
    private final MultiPlatformLiveClient multiPlatformLiveClient;
    private final CimeOfficialApiClient cimeOfficialApiClient;
    private final EventFeed eventFeed;
    /** Latest dashboard announcements for the server-settings history panel. */
    private final List<Map<String, Object>> announcementHistory = new ArrayList<>();
    private final String token;
    private final EventIdCache seenEvents;
    /* Keep the dashboard cache in ordinary fields so a stale plugin reload can never look for an inner cache class. */
    private volatile long dashboardCacheCreatedAtMillis;
    private volatile Map<String, Object> dashboardCacheData = Map.of();
    /** Holds dashboard-entered OAuth credentials only until the browser returns from CHZZK login. */
    private volatile OfficialApiTestAttempt officialApiTestAttempt;
    /** One-time, player-specific portal links created from the Minecraft command. */
    private final Map<String, OfficialLoginPortal> officialLoginPortals = new ConcurrentHashMap<>();
    private HttpServer server;
    private HttpServer oauthCallbackServer;
    private ExecutorService executor;

    ApiServer(JavaPlugin plugin, BindingRepository bindings, DonationDispatcher dispatcher, ChzzkLiveClient chzzkLiveClient,
              ChzzkOpenApiClient chzzkOpenApiClient, MultiPlatformLiveClient multiPlatformLiveClient,
              CimeOfficialApiClient cimeOfficialApiClient, EventFeed eventFeed) {
        this.plugin = plugin;
        this.bindings = bindings;
        this.dispatcher = dispatcher;
        this.chzzkLiveClient = chzzkLiveClient;
        this.chzzkOpenApiClient = chzzkOpenApiClient;
        this.multiPlatformLiveClient = multiPlatformLiveClient;
        this.cimeOfficialApiClient = cimeOfficialApiClient;
        this.eventFeed = eventFeed;
        this.token = plugin.getConfig().getString("api-token", DEFAULT_TOKEN);
        this.seenEvents = new EventIdCache(plugin.getConfig().getInt("deduplication-cache-size", 5000));
    }

    void start() throws IOException {
        String address = plugin.getConfig().getString("bind-address", "127.0.0.1");
        int port = plugin.getConfig().getInt("port", 8765);
        int callbackPort = plugin.getConfig().getInt("oauth-callback.port", 8766);
        if (token == null || token.isBlank()) {
            throw new IOException("config.yml must contain a non-empty api-token.");
        }
        if (DEFAULT_TOKEN.equals(token)) {
            plugin.getLogger().warning("api-token이 예시 값으로 설정되어 있습니다. API를 외부에 공개하기 전에 변경하세요.");
        }
        server = HttpServer.create(new InetSocketAddress(address, port), 0);
        int workerThreads = Math.max(2, Math.min(16, plugin.getConfig().getInt("api-worker-threads", 4)));
        executor = Executors.newFixedThreadPool(workerThreads, runnable -> {
            Thread thread = new Thread(runnable, "DonationApiWorker");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/api", this::handle);
        server.createContext("/", this::dashboard);
        server.start();
        plugin.getLogger().info("Donation API listening on http://" + address + ":" + port + "/api (dashboard: /dashboard)");
        if (callbackPort != port) {
            oauthCallbackServer = HttpServer.create(new InetSocketAddress(address, callbackPort), 0);
            oauthCallbackServer.setExecutor(executor);
            // Do not expose the regular dashboard API on the public callback port.
            oauthCallbackServer.createContext("/api", this::oauthCallback);
            oauthCallbackServer.createContext("/", this::oauthPortal);
            oauthCallbackServer.start();
            plugin.getLogger().info("Official API OAuth callback listening on http://" + address + ':' + callbackPort + "/api");
        }
    }

    void stop() {
        if (oauthCallbackServer != null) {
            oauthCallbackServer.stop(0);
        }
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            List<String> parts = routeParts(path);
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("official-api", "test", "callback"))) {
                officialApiLoginCallback(exchange, StreamingPlatform.CHZZK);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("official-api", "oauth", "chzzk", "callback"))) {
                officialApiLoginCallback(exchange, StreamingPlatform.CHZZK);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("official-api", "oauth", "soop", "callback"))) {
                officialApiLoginCallback(exchange, StreamingPlatform.SOOP);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("official-api", "oauth", "cime", "callback"))) {
                officialApiLoginCallback(exchange, StreamingPlatform.CIME);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("health"))) {
                respond(exchange, 200, Map.of("ok", true));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("events"))) {
                respond(exchange, 200, Map.of("events", eventFeed.snapshot()));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("dashboard"))) {
                respond(exchange, 200, dashboardData());
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("streamers"))) {
                List<Object> streamers = bindings.all().stream().map(binding -> Map.of(
                        "minecraftName", binding.minecraftName(), "platform", binding.platform().name(), "channelId", binding.channelId(), "channelName", binding.channelName(),
                        "enabledDonationTypes", binding.enabledDonationTypes().stream().map(Enum::name).sorted().toList())).map(value -> (Object) value).toList();
                respond(exchange, 200, Map.of("streamers", streamers));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("status"))) {
                List<String> online = Bukkit.getOnlinePlayers().stream().map(player -> player.getName()).sorted().toList();
                respond(exchange, 200, Map.of("onlinePlayers", online, "dashboard", "/dashboard"));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("players"))) {
                respond(exchange, 200, Map.of("players", playerList()));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("server-settings"))) {
                respond(exchange, 200, serverSettings());
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("donation-rules"))) {
                respond(exchange, 200, Map.of("rules", dispatcher.dashboardRules()));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("party-groups"))) {
                List<Object> groups = bindings.partyGroups().stream().map(group -> (Object) Map.of(
                        "name", group.name(), "members", group.members())).toList();
                respond(exchange, 200, Map.of("groups", groups));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("official-api", "test", "status"))) {
                respond(exchange, 200, officialApiTestStatus());
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("official-api", "app"))) {
                respond(exchange, 200, officialApiAppStatus());
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && parts.equals(List.of("official-api", "apps"))) {
                respond(exchange, 200, officialApiAppsStatus());
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("ok", false, "message", "이 API는 POST 방식으로 요청해야 합니다."));
                return;
            }
            Map<String, Object> body = requestBody(exchange);
            if (parts.equals(List.of("login")) || parts.size() == 3 && parts.get(0).equals("login")) {
                login(exchange, body, parts);
            } else if (parts.equals(List.of("logout")) || parts.size() == 2 && parts.get(0).equals("logout")) {
                logout(exchange, body, parts);
            } else if (parts.equals(List.of("donation-test")) || parts.equals(List.of("후원테스트"))
                    || parts.size() == 2 && (parts.get(0).equals("donation-test") || parts.get(0).equals("후원테스트"))) {
                donationTest(exchange, body, parts);
            } else if (parts.equals(List.of("party-test")) || parts.equals(List.of("파티후원테스트"))) {
                dashboardPartyTest(exchange, body);
            } else if (parts.equals(List.of("donation-event-test"))) {
                dashboardDonationEventTest(exchange, body);
            } else if (parts.equals(List.of("party-groups"))) {
                partyGroups(exchange, body);
            } else if (parts.equals(List.of("chzzk", "donation"))) {
                if (!authorized(exchange)) {
                    respond(exchange, 401, Map.of("ok", false, "message", "Bearer 토큰이 없거나 올바르지 않습니다."));
                    return;
                }
                donation(exchange, body);
            } else if (parts.equals(List.of("chzzk", "party-test"))) {
                if (!authorized(exchange)) {
                    respond(exchange, 401, Map.of("ok", false, "message", "Bearer 토큰이 없거나 올바르지 않습니다."));
                    return;
                }
                partyTest(exchange, body);
            } else if (parts.equals(List.of("donation-setting")) || parts.equals(List.of("후원설정"))) {
                donationSetting(exchange, body);
            } else if (parts.equals(List.of("player-action"))) {
                playerAction(exchange, body);
            } else if (parts.equals(List.of("server-settings"))) {
                updateServerSettings(exchange, body);
            } else if (parts.equals(List.of("player-add"))) {
                addManualPlayer(exchange, body);
            } else if (parts.equals(List.of("player-delete"))) {
                deletePlayerData(exchange, body);
            } else if (parts.equals(List.of("console-action"))) {
                consoleAction(exchange, body);
            } else if (parts.equals(List.of("donation-rules"))) {
                saveDonationRules(exchange, body);
            } else if (parts.equals(List.of("donation-rules", "reload"))) {
                dispatcher.reloadDashboardRules();
                respond(exchange, 200, Map.of("ok", true, "message", "저장된 후원 규칙을 불러왔습니다."));
            } else if (parts.equals(List.of("official-api", "test", "start"))) {
                startOfficialApiTest(exchange, body);
            } else if (parts.equals(List.of("official-api", "app"))) {
                saveOfficialApiApp(exchange, body);
            } else if (parts.equals(List.of("official-api", "apps"))) {
                saveOfficialApiPlatformApp(exchange, body);
            } else if (parts.equals(List.of("official-api", "test", "stop"))) {
                officialApiTestAttempt = null;
                chzzkOpenApiClient.stopTemporaryTest();
                respond(exchange, 200, Map.of("ok", true, "message", "공식 API 테스트 연결을 종료했습니다."));
            } else {
                respond(exchange, 404, Map.of("ok", false, "message", "알 수 없는 API 경로입니다."));
            }
        } catch (BadRequestException exception) {
            respond(exchange, 400, Map.of("ok", false, "message", exception.getMessage()));
        } catch (Exception exception) {
            plugin.getLogger().warning("API 요청 처리에 실패했습니다: " + exception.getMessage());
            respond(exchange, 500, Map.of("ok", false, "message", "서버 내부 오류가 발생했습니다."));
        } finally {
            exchange.close();
        }
    }

    /** The optional public listener accepts only OAuth GET callbacks, never the dashboard API. */
    private void oauthCallback(HttpExchange exchange) throws IOException {
        List<String> parts = routeParts(exchange.getRequestURI().getPath());
        boolean callback = parts.equals(List.of("official-api", "oauth", "chzzk", "callback"))
                || parts.equals(List.of("official-api", "oauth", "soop", "callback"))
                || parts.equals(List.of("official-api", "oauth", "cime", "callback"));
        if ("GET".equals(exchange.getRequestMethod()) && callback) {
            handle(exchange);
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    /** Public, player-specific platform picker served only on the dedicated OAuth port. */
    private void oauthPortal(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (path.equals("/") || path.isBlank()) {
            sendPortalPage(exchange, null, "인게임에서 /api 공식로그인을 실행해 플레이어별 연결 링크를 받으세요.");
            return;
        }
        if (parts.length == 3 && "login".equals(parts[1])) {
            OfficialLoginPortal portal = validPortal(parts[2]);
            if (portal == null) {
                sendPortalPage(exchange, null, "연결 링크가 만료되었거나 이미 사용되었습니다. 마인크래프트에서 다시 발급해 주세요.");
                return;
            }
            sendPortalPage(exchange, portal, null);
            return;
        }
        if (parts.length == 4 && "login".equals(parts[1])) {
            OfficialLoginPortal portal = validPortal(parts[2]);
            StreamingPlatform platform = StreamingPlatform.parse(parts[3]);
            if (portal == null || platform == null) {
                sendPortalPage(exchange, null, "연결 링크가 만료되었거나 플랫폼이 올바르지 않습니다.");
                return;
            }
            try {
                OfficialLoginStart start = createOfficialLoginStart(portal.minecraftName(), platform, commandCallbackUri(platform));
                officialLoginPortals.remove(parts[2], portal);
                exchange.getResponseHeaders().set("Location", start.authorizationUrl());
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(302, -1);
            } catch (BadRequestException exception) {
                sendPortalPage(exchange, portal, exception.getMessage());
            }
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private OfficialLoginPortal validPortal(String ticket) {
        OfficialLoginPortal portal = officialLoginPortals.get(ticket);
        if (portal != null && portal.expiresAtMillis() <= System.currentTimeMillis()) {
            officialLoginPortals.remove(ticket, portal);
            return null;
        }
        return portal;
    }

    private void sendPortalPage(HttpExchange exchange, OfficialLoginPortal portal, String message) throws IOException {
        String body;
        if (portal == null) {
            body = "<h1>CHOROK STUDIO</h1><p>" + htmlEscape(message == null ? "공식 API 플레이어 연결" : message) + "</p>";
        } else {
            String root = "/login/" + encodePath(portal.ticket());
            body = "<h1>공식 API 연결</h1><p><b>" + htmlEscape(portal.minecraftName())
                    + "</b> 플레이어에 연결할 플랫폼을 선택하세요.</p><div class=buttons>"
                    + "<a class=chzzk href='" + root + "/chzzk'>치지직 연결</a>"
                    + "<a class=soop href='" + root + "/soop'>숲 연결</a>"
                    + "<a class=cime href='" + root + "/cime'>씨미 연결</a></div>"
                    + "<p class=hint>로그인 완료 후 선택한 마인크래프트 플레이어에 자동 등록됩니다.</p>"
                    + (message == null ? "" : "<p class=error>" + htmlEscape(message) + "</p>");
        }
        String html = "<!doctype html><html lang=ko><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>CHOROK 공식 API 연결</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f4f7f5;color:#18251e;font:15px 'Malgun Gothic',sans-serif}.card{width:min(430px,calc(100% - 36px));padding:30px;border:1px solid #dce7df;border-radius:18px;background:#fff;box-shadow:0 18px 45px #18331a18}h1{margin:0 0 12px;color:#1f9a55;font-size:22px}.buttons{display:grid;gap:9px;margin:22px 0}a{display:block;padding:12px 14px;border-radius:9px;color:#fff;text-align:center;text-decoration:none;font-weight:700;transition:filter .16s,transform .16s}a:hover{filter:brightness(1.08);transform:translateY(-1px)}.chzzk{background:#00c875}.soop{background:#3158e8}.cime{background:#7448e8}.hint{color:#61756a;font-size:13px}.error{color:#b74752}</style><main class=card>" + body + "</main></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void dashboard(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean dashboard = path.equals("/") || path.equals("/dashboard") || path.equals("/dashboard/")
                || path.equals("/dashboard/admin") || path.equals("/dashboard/admin/")
                || path.equals("/dashboard/admin/panel") || path.equals("/dashboard/admin/panel/");
        if (!"GET".equals(exchange.getRequestMethod()) || !dashboard) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        try (InputStream input = plugin.getResource("dashboard-admin.html")) {
            if (input == null) { exchange.sendResponseHeaders(500, -1); return; }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }

    private synchronized Map<String, Object> dashboardData() {
        long now = System.currentTimeMillis();
        long cacheMillis = Math.max(100, plugin.getConfig().getLong("dashboard.cache-millis", 250));
        if (dashboardCacheCreatedAtMillis > 0 && now - dashboardCacheCreatedAtMillis < cacheMillis) {
            return dashboardCacheData;
        }
        List<String> online = Bukkit.getOnlinePlayers().stream().map(player -> player.getName()).sorted().toList();
        List<Object> streamers = bindings.all().stream().map(binding -> Map.of(
                "minecraftName", binding.minecraftName(), "platform", binding.platform().name(), "channelId", binding.channelId(),
                "enabledDonationTypes", binding.enabledDonationTypes().stream().map(Enum::name).sorted().toList()))
                .map(value -> (Object) value).toList();
        int eventLimit = Math.max(200, plugin.getConfig().getInt("dashboard.event-limit", 200));
        Map<String, Object> data = Map.of("onlinePlayers", online, "streamers", streamers, "events", eventFeed.snapshot(eventLimit));
        dashboardCacheData = data;
        dashboardCacheCreatedAtMillis = now;
        return data;
    }

    /** Starts a one-time OAuth flow and assigns the authenticated platform channel to the selected player. */
    private void startOfficialApiTest(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        String minecraftName = Json.string(body.get("minecraftName"));
        StreamingPlatform platform = StreamingPlatform.parse(Json.string(body.get("platform")));
        if (platform == null) throw new BadRequestException("지원하지 않는 플랫폼입니다.");
        String redirectUri = Json.string(body.get("redirectUri"));
        OfficialLoginStart start = createOfficialLoginStart(minecraftName, platform, redirectUri);
        respond(exchange, 200, Map.of("ok", true, "authorizationUrl", start.authorizationUrl(), "message", start.message()));
    }

    /** Creates a clickable OAuth login URL for an in-game command and binds on callback. */
    OfficialLoginStart startOfficialLoginFromCommand(String minecraftName, StreamingPlatform platform) throws IOException {
        String redirectUri = commandCallbackUri(platform);
        if (redirectUri == null) {
            throw new IOException("config.yml의 dashboard-url에 외부에서 접속 가능한 대시보드 주소를 설정해 주세요.");
        }
        try {
            return createOfficialLoginStart(minecraftName, platform, redirectUri);
        } catch (BadRequestException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    /** Creates a short-lived player-specific picker page with all supported platform buttons. */
    OfficialLoginPortal startOfficialLoginPortalFromCommand(String minecraftName) throws IOException {
        try {
            validateMinecraftName(minecraftName);
        } catch (BadRequestException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        String baseUrl = callbackPortalBaseUrl();
        if (baseUrl == null) {
            throw new IOException("config.yml의 oauth-callback.public-url에 외부에서 접속 가능한 콜백 주소를 설정해 주세요.");
        }
        String ticket = UUID.randomUUID().toString();
        OfficialLoginPortal portal = new OfficialLoginPortal(ticket, minecraftName,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10), baseUrl + "/login/" + ticket);
        officialLoginPortals.put(ticket, portal);
        return portal;
    }

    /** A client ID is enough to choose the in-game OAuth flow; its matching Secret is validated after a platform is chosen. */
    boolean hasOfficialApiClientId() {
        for (StreamingPlatform platform : StreamingPlatform.values()) {
            if (safeCredential(officialClientId(platform), 128)) return true;
        }
        return false;
    }

    void cancelOfficialLogin() {
        officialApiTestAttempt = null;
        chzzkOpenApiClient.stopTemporaryTest();
    }

    private OfficialLoginStart createOfficialLoginStart(String minecraftName, StreamingPlatform platform, String redirectUri)
            throws BadRequestException {
        validateMinecraftName(minecraftName);
        String clientId = officialClientId(platform);
        String clientSecret = officialClientSecret(platform);
        if (!safeCredential(clientId, 128) || !safeCredential(clientSecret, 256)) {
            throw new BadRequestException("먼저 " + platform.koreanName() + " 공식 API의 앱 정보를 등록해 주세요.");
        }
        // The dashboard or command supplies this server's callback URL. The callback exchanges
        // the code here, so the selected Minecraft player is linked without manual channel input.
        if (!validRedirectUri(redirectUri)) {
            throw new BadRequestException("대시보드의 " + platform.koreanName() + " 콜백 주소를 확인해 주세요.");
        }
        String state = UUID.randomUUID().toString();
        officialApiTestAttempt = new OfficialApiTestAttempt(platform, clientId, clientSecret, redirectUri, minecraftName, state,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
        String authorizationUrl = switch (platform) {
            case CHZZK -> "https://chzzk.naver.com/account-interlock?clientId=" + encode(clientId)
                    + "&redirectUri=" + encode(redirectUri) + "&state=" + encode(state);
            // SOOP uses the redirect URI registered for the application in its developer console.
            case SOOP -> "https://openapi.sooplive.com/auth/code?client_id=" + encode(clientId) + "&response_type=code";
            case CIME -> "https://ci.me/auth/openapi/account-interlock?clientId=" + encode(clientId)
                    // A previous CI.ME login may not include newly enabled READ:CHANNEL.
                    // Force the consent page so the refreshed token can read followers.
                    + "&redirectUri=" + encode(redirectUri) + "&state=" + encode(state) + "&prompt=consent";
        };
        return new OfficialLoginStart(platform, authorizationUrl,
                minecraftName + " 플레이어에 연결할 " + platform.koreanName() + " 계정으로 로그인해 주세요.", redirectUri);
    }

    private void saveOfficialApiApp(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        String clientId = Json.string(body.get("clientId"));
        String clientSecret = Json.string(body.get("clientSecret"));
        if (!safeCredential(clientId, 128)) throw new BadRequestException("클라이언트 ID를 확인해 주세요.");
        if (!safeCredential(clientSecret, 256)) throw new BadRequestException("클라이언트 Secret을 확인해 주세요.");
        try {
            verifyOfficialApiApp(clientId.trim(), clientSecret.trim());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("치지직 앱 정보 확인이 중단되었습니다.", exception);
        }
        plugin.getConfig().set("chzzk.open-api.client-id", clientId.trim());
        plugin.getConfig().set("chzzk.open-api.client-secret", clientSecret.trim());
        plugin.saveConfig();
        respond(exchange, 200, Map.of("ok", true, "message", "공식 API 앱 정보를 등록했습니다."));
    }

    /** Stores an OAuth application pair for a dashboard-selected supported platform. */
    private void saveOfficialApiPlatformApp(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        StreamingPlatform platform = StreamingPlatform.parse(Json.string(body.get("platform")));
        if (platform == null) throw new BadRequestException("지원하지 않는 플랫폼입니다.");
        String clientId = Json.string(body.get("clientId"));
        String clientSecret = Json.string(body.get("clientSecret"));
        if (!safeCredential(clientId, 128)) throw new BadRequestException("클라이언트 ID를 확인해 주세요.");
        if (!safeCredential(clientSecret, 256)) throw new BadRequestException("클라이언트 Secret을 확인해 주세요.");
        if (platform == StreamingPlatform.CHZZK) {
            try {
                verifyOfficialApiApp(clientId.trim(), clientSecret.trim());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("치지직 앱 정보 확인이 중단되었습니다.", exception);
            }
        }
        plugin.getConfig().set(officialConfigPath(platform, "client-id"), clientId.trim());
        plugin.getConfig().set(officialConfigPath(platform, "client-secret"), clientSecret.trim());
        plugin.saveConfig();
        String suffix = platform == StreamingPlatform.CHZZK ? " 확인 및 등록했습니다." : " 등록했습니다. OAuth 로그인·이벤트 수신 연결은 다음 단계에서 사용할 수 있습니다.";
        respond(exchange, 200, Map.of("ok", true, "message", platform.koreanName() + " 공식 API 앱 정보를" + suffix));
    }

    /** Verifies the pair before saving it, so a mistyped secret never reaches the login step. */
    private void verifyOfficialApiApp(String clientId, String clientSecret) throws IOException, InterruptedException, BadRequestException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("https://openapi.chzzk.naver.com/open/v1/sessions/auth/client"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Client-Id", clientId).header("Client-Secret", clientSecret).GET().build();
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BadRequestException("앱 정보를 확인하지 못했습니다 (HTTP " + response.statusCode() + "): "
                    + koreanChzzkError(response.body()));
        }
    }

    private void officialApiLoginCallback(HttpExchange exchange, StreamingPlatform platform) throws IOException {
        OfficialApiTestAttempt attempt = officialApiTestAttempt;
        Map<String, String> query = queryParameters(exchange.getRequestURI().getRawQuery());
        String code = query.get("code");
        String state = query.get("state");
        if (attempt == null || attempt.platform() != platform || System.currentTimeMillis() > attempt.expiresAtMillis()) {
            officialApiTestAttempt = null;
            sendCallbackPage(exchange, platform, false, "인증 시간이 만료되었습니다. 대시보드에서 다시 시작해 주세요.");
            return;
        }
        // SOOP's documented authorization endpoint returns only the code. CI.ME and CHZZK return state.
        if (code == null || (platform != StreamingPlatform.SOOP && (state == null || !constantTimeEquals(attempt.state(), state)))) {
            sendCallbackPage(exchange, platform, false, platform.koreanName() + " 인증 상태를 확인하지 못했습니다. 다시 시도해 주세요.");
            return;
        }
        try {
            CimeOfficialApiClient.Tokens cimeTokens = platform == StreamingPlatform.CIME
                    ? exchangeCimeAuthorizationCode(attempt, code) : null;
            String accessToken = cimeTokens == null ? exchangeAuthorizationCode(attempt, code, state) : cimeTokens.accessToken();
            AuthenticatedChannel channel = authenticatedChannel(platform, accessToken);
            BindingRepository.Binding previous = bindings.findPlayer(attempt.minecraftName());
            bindings.bind(attempt.minecraftName(), platform, channel.channelId(), channel.channelName());
            disconnectUnused(previous);
            if (platform == StreamingPlatform.CHZZK) {
                chzzkLiveClient.connect(channel.channelId());
                chzzkOpenApiClient.connect(channel.channelId());
            } else if (platform == StreamingPlatform.CIME) {
                cimeOfficialApiClient.connect(channel.channelId(), cimeTokens);
            } else {
                multiPlatformLiveClient.connect(bindings.findPlayer(attempt.minecraftName()));
            }
            officialApiTestAttempt = null;
            if (platform == StreamingPlatform.CHZZK) {
                chzzkOpenApiClient.startTemporaryTest(accessToken);
                plugin.getLogger().info("[CHZZK 공식] " + attempt.minecraftName() + " 플레이어를 " + channel.channelId() + " 채널에 자동 연결하고 후원 테스트 세션을 여는 중입니다.");
                notifyOfficialLoginSuccess(attempt.minecraftName(), platform, channel.channelName());
                sendCallbackPage(exchange, platform, true, channel.channelName() + " 채널을 " + attempt.minecraftName() + " 플레이어에 연결했습니다. 이 창을 닫고 치지직 스튜디오에서 테스트 후원을 보내세요.");
            } else {
                plugin.getLogger().info("[" + platform.koreanName() + " 공식] " + attempt.minecraftName() + " 플레이어를 " + channel.channelId() + " 채널에 자동 연결했습니다.");
                notifyOfficialLoginSuccess(attempt.minecraftName(), platform, channel.channelName());
                sendCallbackPage(exchange, platform, true, channel.channelName() + " 채널을 " + attempt.minecraftName() + " 플레이어에 연결했습니다. 이 창을 닫아도 됩니다.");
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("[" + platform.koreanName() + " 공식] 대시보드 로그인 연결 실패: " + exception.getMessage());
            sendCallbackPage(exchange, platform, false, platform.koreanName() + " 계정을 연결하지 못했습니다. 앱 정보, 리디렉션 URL과 필요한 조회 권한을 확인해 주세요.");
        }
    }

    /** OAuth callbacks run on HTTP threads, so deliver the Minecraft notification on the server thread. */
    private void notifyOfficialLoginSuccess(String minecraftName, StreamingPlatform platform, String channelName) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(minecraftName);
            if (player == null || !player.isOnline()) return;
            player.sendMessage("§a[CHOROK] " + platform.koreanName() + " 공식 API 연결이 완료되었습니다. §f(" + channelName + ")");
        });
    }

    private String exchangeAuthorizationCode(OfficialApiTestAttempt attempt, String code, String state) throws IOException, InterruptedException {
        if (attempt.platform() == StreamingPlatform.SOOP) {
            Map<String, Object> requestBody = Map.of("grant_type", "authorization_code", "client_id", attempt.clientId(),
                    "client_secret", attempt.clientSecret(), "code", code);
            return exchangePlatformToken("https://openapi.sooplive.com/auth/token", requestBody, "access_token", "숲");
        }
        Map<String, Object> requestBody = Map.of("grantType", "authorization_code", "clientId", attempt.clientId(),
                "clientSecret", attempt.clientSecret(), "code", code, "state", state);
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create("https://openapi.chzzk.naver.com/auth/v1/token"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(Json.write(requestBody), StandardCharsets.UTF_8)).build();
        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("치지직 토큰 발급이 거부되었습니다 (HTTP " + response.statusCode() + "): "
                    + koreanChzzkError(response.body()));
        }
        Map<String, Object> root = Json.object(Json.parse(response.body()));
        Map<String, Object> content = optionalObject(root.get("content"));
        String accessToken = content == null ? null : Json.string(content.get("accessToken"));
        if (accessToken == null || accessToken.isBlank()) throw new IOException("치지직이 액세스 토큰을 반환하지 않았습니다.");
        return accessToken;
    }

    /** CI.ME rotates refresh tokens, so retain the complete response for the live official listener. */
    private CimeOfficialApiClient.Tokens exchangeCimeAuthorizationCode(OfficialApiTestAttempt attempt, String code)
            throws IOException, InterruptedException {
        Map<String, Object> requestBody = Map.of("grantType", "authorization_code", "clientId", attempt.clientId(),
                "clientSecret", attempt.clientSecret(), "code", code);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create("https://ci.me/api/openapi/auth/v1/token"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(Json.write(requestBody), StandardCharsets.UTF_8)).build();
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("씨미 토큰 발급이 거부되었습니다 (HTTP " + response.statusCode() + ").");
        }
        return CimeOfficialApiClient.parseTokens(response.body(), "");
    }

    private String exchangePlatformToken(String endpoint, Map<String, Object> requestBody, String field, String platformName) throws IOException, InterruptedException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(Json.write(requestBody), StandardCharsets.UTF_8)).build();
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(platformName + " 토큰 발급이 거부되었습니다 (HTTP " + response.statusCode() + ").");
        }
        Map<String, Object> root = Json.object(Json.parse(response.body()));
        Map<String, Object> content = optionalObject(root.get("content"));
        String accessToken = Json.string((content == null ? root : content).get(field));
        if (accessToken == null || accessToken.isBlank()) throw new IOException(platformName + "이 액세스 토큰을 반환하지 않았습니다.");
        return accessToken;
    }

    private AuthenticatedChannel authenticatedChannel(StreamingPlatform platform, String accessToken) throws IOException, InterruptedException {
        if (platform == StreamingPlatform.SOOP) return soopAuthenticatedChannel(accessToken);
        if (platform == StreamingPlatform.CIME) return cimeAuthenticatedChannel(accessToken);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create("https://openapi.chzzk.naver.com/open/v1/users/me"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Authorization", "Bearer " + accessToken).GET().build();
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("로그인한 치지직 채널을 확인하지 못했습니다 (HTTP " + response.statusCode() + ").");
        }
        Map<String, Object> root = Json.object(Json.parse(response.body()));
        Map<String, Object> content = optionalObject(root.get("content"));
        String channelId = content == null ? null : Json.string(content.get("channelId"));
        String channelName = content == null ? null : Json.string(content.get("channelName"));
        if (channelId == null || channelId.isBlank()) throw new IOException("치지직이 로그인한 채널 ID를 반환하지 않았습니다.");
        return new AuthenticatedChannel(channelId, channelName == null || channelName.isBlank() ? "치지직" : channelName);
    }

    private AuthenticatedChannel cimeAuthenticatedChannel(String accessToken) throws IOException, InterruptedException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create("https://ci.me/api/openapi/open/v1/users/me"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Authorization", "Bearer " + accessToken).GET().build();
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("로그인한 씨미 채널을 확인하지 못했습니다 (HTTP " + response.statusCode() + ").");
        }
        Map<String, Object> root = Json.object(Json.parse(response.body()));
        Map<String, Object> content = optionalObject(root.get("content"));
        String channelId = content == null ? null : Json.string(content.get("channelId"));
        String channelName = content == null ? null : Json.string(content.get("channelName"));
        if (channelId == null || channelId.isBlank()) throw new IOException("씨미가 로그인한 채널 ID를 반환하지 않았습니다.");
        return new AuthenticatedChannel(channelId, channelName == null || channelName.isBlank() ? "씨미" : channelName);
    }

    private AuthenticatedChannel soopAuthenticatedChannel(String accessToken) throws IOException, InterruptedException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create("https://openapi.sooplive.com/user/stationinfo"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("access_token=" + encode(accessToken), StandardCharsets.UTF_8)).build();
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("로그인한 숲 방송국을 확인하지 못했습니다 (HTTP " + response.statusCode() + ").");
        }
        Map<String, Object> root = Json.object(Json.parse(response.body()));
        String profileImage = Json.string(root.get("profile_image"));
        String channelId = soopChannelId(profileImage);
        String stationName = Json.string(root.get("station_name"));
        if (channelId == null || channelId.isBlank()) throw new IOException("숲이 로그인한 방송국 ID를 반환하지 않았습니다.");
        return new AuthenticatedChannel(channelId, stationName == null || stationName.isBlank() ? channelId : stationName);
    }

    private static String soopChannelId(String profileImage) {
        if (profileImage == null || profileImage.isBlank()) return null;
        int slash = profileImage.lastIndexOf('/');
        String filename = slash >= 0 ? profileImage.substring(slash + 1) : profileImage;
        int dot = filename.lastIndexOf('.');
        String value = dot > 0 ? filename.substring(0, dot) : filename;
        return value.isBlank() ? null : value;
    }

    private Map<String, Object> officialApiAppStatus() {
        String clientId = officialClientId();
        return Map.of("ok", true, "configured", safeCredential(clientId, 128) && safeCredential(officialClientSecret(), 256),
                "clientId", clientId == null ? "" : clientId, "redirectUri", officialRedirectUri());
    }

    /** Never sends secrets to the browser; only the identifier and configured state are exposed. */
    private Map<String, Object> officialApiAppsStatus() {
        List<Object> apps = new ArrayList<>();
        for (StreamingPlatform platform : List.of(StreamingPlatform.CHZZK, StreamingPlatform.SOOP, StreamingPlatform.CIME)) {
            String clientId = plugin.getConfig().getString(officialConfigPath(platform, "client-id"), "");
            String clientSecret = plugin.getConfig().getString(officialConfigPath(platform, "client-secret"), "");
            String callback = switch (platform) {
                case CHZZK -> officialRedirectUri();
                case SOOP -> "http://localhost:3000/auth/soop/callback";
                case CIME -> "http://localhost:3000/auth/cime/callback";
            };
            apps.add(Map.of("platform", platform.name(), "name", platform.koreanName(),
                    "configured", safeCredential(clientId, 128) && safeCredential(clientSecret, 256),
                    "clientId", clientId == null ? "" : clientId, "callback", callback));
        }
        return Map.of("ok", true, "apps", apps, "callbackPort", oauthCallbackPort(),
                "callbackBaseUrl", callbackPublicBaseUrl());
    }

    private static String officialConfigPath(StreamingPlatform platform, String key) {
        String section = switch (platform) {
            case CHZZK -> "chzzk.open-api";
            case SOOP -> "soop.open-api";
            case CIME -> "cime.open-api";
        };
        return section + '.' + key;
    }

    private String officialClientId() { return plugin.getConfig().getString("chzzk.open-api.client-id", ""); }
    private String officialClientSecret() { return plugin.getConfig().getString("chzzk.open-api.client-secret", ""); }
    private String officialClientId(StreamingPlatform platform) { return plugin.getConfig().getString(officialConfigPath(platform, "client-id"), ""); }
    private String officialClientSecret(StreamingPlatform platform) { return plugin.getConfig().getString(officialConfigPath(platform, "client-secret"), ""); }
    private static String officialRedirectUri() { return "http://localhost:3000/auth/chzzk/callback"; }

    private int oauthCallbackPort() { return plugin.getConfig().getInt("oauth-callback.port", 8766); }

    /** Optional public origin for a proxy/NAT setup, for example https://example.com:8766. */
    private String callbackPublicBaseUrl() {
        String value = plugin.getConfig().getString("oauth-callback.public-url", DEFAULT_OAUTH_PUBLIC_ORIGIN);
        if (value == null || value.isBlank()) value = DEFAULT_OAUTH_PUBLIC_ORIGIN;
        value = value.strip();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return validRedirectUri(value + "/callback") ? value : "";
    }

    private String commandCallbackUri(StreamingPlatform platform) {
        String publicBase = callbackPortalBaseUrl();
        if (publicBase != null) return publicBase + "/api/official-api/oauth/" + platform.name().toLowerCase(java.util.Locale.ROOT) + "/callback";
        String configured = plugin.getConfig().getString("dashboard-url", "");
        if (configured == null || configured.isBlank()) {
            String address = plugin.getConfig().getString("bind-address", "127.0.0.1");
            if (address.equals("127.0.0.1") || address.equals("localhost") || address.equals("0.0.0.0")) return null;
            configured = "http://" + address + ':' + plugin.getConfig().getInt("port", 8765) + "/dashboard";
        }
        try {
            URI dashboardUri = URI.create(configured.strip());
            if (dashboardUri.getScheme() == null || dashboardUri.getHost() == null) return null;
            return dashboardUri.getScheme() + "://" + dashboardUri.getHost() + ':' + oauthCallbackPort()
                    + "/api/official-api/oauth/" + platform.name().toLowerCase(java.util.Locale.ROOT) + "/callback";
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String callbackPortalBaseUrl() {
        String publicBase = callbackPublicBaseUrl();
        if (!publicBase.isBlank()) return publicBase;
        String configured = plugin.getConfig().getString("dashboard-url", "");
        if (configured == null || configured.isBlank()) return null;
        try {
            URI dashboardUri = URI.create(configured.strip());
            if (dashboardUri.getScheme() == null || dashboardUri.getHost() == null) return null;
            return dashboardUri.getScheme() + "://" + dashboardUri.getHost() + ':' + oauthCallbackPort();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String koreanChzzkError(String responseBody) {
        try {
            Map<String, Object> body = Json.object(Json.parse(responseBody));
            String code = Json.string(body.get("code"));
            String message = Json.string(body.get("message"));
            if ("INVALID_CLIENT".equalsIgnoreCase(code)) return "클라이언트 ID 또는 Client Secret이 등록된 앱 정보와 다릅니다.";
            if ("INVALID_TOKEN".equalsIgnoreCase(code)) return "인증 코드가 만료되었거나 이미 사용되었습니다. 새로 로그인해 주세요.";
            if ("UNAUTHORIZED".equalsIgnoreCase(code)) return "앱 인증 정보가 없거나 올바르지 않습니다.";
            if (message != null && !message.isBlank()) return "치지직 요청 오류(" + message + ")";
        } catch (Exception ignored) { }
        return "클라이언트 ID·Secret과 로그인 리디렉션 URL을 다시 확인해 주세요.";
    }

    private Map<String, Object> officialApiTestStatus() {
        OfficialApiTestAttempt attempt = officialApiTestAttempt;
        boolean waitingForLogin = attempt != null && attempt.expiresAtMillis() > System.currentTimeMillis();
        if (attempt != null && !waitingForLogin) officialApiTestAttempt = null;
        String platformName = attempt == null ? "" : attempt.platform().koreanName();
        String message = chzzkOpenApiClient.connected() ? "공식 API 테스트 수신이 준비되었습니다. 스튜디오에서 테스트 알림을 보내세요."
                : waitingForLogin ? platformName + " 로그인·권한 동의를 기다리고 있습니다."
                : chzzkOpenApiClient.temporaryTestActive() ? "공식 API 세션에 연결하는 중입니다."
                : "아직 공식 API 테스트를 시작하지 않았습니다.";
        return Map.of("ok", true, "waitingForLogin", waitingForLogin, "active", chzzkOpenApiClient.temporaryTestActive(),
                "connected", chzzkOpenApiClient.connected(), "platform", attempt == null ? "" : attempt.platform().name(), "message", message);
    }

    private static boolean safeCredential(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static boolean validRedirectUri(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && host != null && !host.isBlank() && uri.getUserInfo() == null && value.length() <= 512;
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static Map<String, String> queryParameters(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return values;
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String encodePath(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendCallbackPage(HttpExchange exchange, StreamingPlatform platform, boolean success, String message) throws IOException {
        String title = platform.koreanName() + (success ? " 공식 API 연결 완료" : " 공식 API 연결 실패");
        String color = success ? "#238f52" : "#bd4b55";
        String html = "<!doctype html><meta charset=\"utf-8\"><title>" + title + "</title><body style=\"margin:0;min-height:100vh;display:grid;place-items:center;background:#f5f7f6;font:15px 'Malgun Gothic',sans-serif;color:#202522\"><main style=\"max-width:440px;padding:28px;border:1px solid #dfe5e1;border-radius:14px;background:#fff;box-shadow:0 12px 35px #1224\"><h1 style=\"margin:0 0 12px;color:" + color + "\">" + title + "</h1><p style=\"line-height:1.7\">" + htmlEscape(message) + "</p></main></body>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(success ? 200 : 400, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Map<String, Object> optionalObject(Object value) {
        try { return value == null ? null : Json.object(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private record OfficialApiTestAttempt(StreamingPlatform platform, String clientId, String clientSecret, String redirectUri,
                                          String minecraftName, String state, long expiresAtMillis) { }
    record OfficialLoginStart(StreamingPlatform platform, String authorizationUrl, String message, String callbackUrl) { }
    record OfficialLoginPortal(String ticket, String minecraftName, long expiresAtMillis, String url) { }
    private record AuthenticatedChannel(String channelId, String channelName) { }

    private void login(HttpExchange exchange, Map<String, Object> body, List<String> parts) throws IOException, BadRequestException {
        String minecraftName = value(body, "minecraftName", parts, 1);
        String channelId = value(body, "channelId", parts, 2);
        StreamingPlatform platform = StreamingPlatform.parse(Json.string(body.get("platform")));
        if (platform == null) throw new BadRequestException("플랫폼은 치지직, 씨미 또는 숲 중 하나여야 합니다.");
        validateMinecraftName(minecraftName);
        validateChannelId(channelId);
        BindingRepository.Binding previous = bindings.findPlayer(minecraftName);
        bindings.bind(minecraftName, platform, channelId, Json.string(body.get("channelName")));
        disconnectUnused(previous);
        if (platform == StreamingPlatform.CHZZK) {
            chzzkLiveClient.connect(channelId);
            chzzkOpenApiClient.connect(channelId);
        } else {
            multiPlatformLiveClient.connect(bindings.findPlayer(minecraftName));
        }
        respond(exchange, 200, Map.of("ok", true, "minecraftName", minecraftName, "platform", platform.name(), "channelId", channelId,
                "message", platform.koreanName() + " 채널 연결을 저장했습니다."));
    }

    private void logout(HttpExchange exchange, Map<String, Object> body, List<String> parts) throws IOException, BadRequestException {
        String minecraftName = value(body, "minecraftName", parts, 1);
        validateMinecraftName(minecraftName);
        BindingRepository.Binding previous = bindings.findPlayer(minecraftName);
        boolean removed = bindings.unbind(minecraftName);
        if (removed) disconnectUnused(previous);
        respond(exchange, 200, Map.of("ok", true, "removed", removed, "minecraftName", minecraftName));
    }

    private void donationTest(HttpExchange exchange, Map<String, Object> body, List<String> parts) throws IOException, BadRequestException {
        String minecraftName = value(body, "minecraftName", parts, 1);
        validateMinecraftName(minecraftName);
        Long amount = Json.longValue(body.get("amount"));
        if (amount != null && amount <= 0) {
            throw new BadRequestException("후원 금액은 양의 정수여야 합니다.");
        }
        DonationDispatcher.DispatchResult result = dispatcher.test(minecraftName, amount);
        respond(exchange, result.status(), resultBody(result));
    }

    private void donation(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        String channelId = Json.string(body.get("channelId"));
        Long amount = Json.longValue(body.get("amount"));
        String donor = Json.string(body.containsKey("donorName") ? body.get("donorName") : body.get("donor"));
        String eventId = Json.string(body.get("eventId"));
        validateChannelId(channelId);
        if (amount == null || amount <= 0) {
            throw new BadRequestException("후원 금액은 양의 정수여야 합니다.");
        }
        if (eventId != null && !eventId.isBlank() && !seenEvents.remember(eventId)) {
            respond(exchange, 200, Map.of("ok", true, "duplicate", true, "message", "이미 처리한 이벤트입니다."));
            return;
        }
        DonationDispatcher.DispatchResult result = dispatcher.receive(channelId, amount, donor);
        eventFeed.publish("donation", channelId, Map.of("nickname", donor == null ? "익명" : donor, "amount", amount, "donationType", "EXTERNAL", "result", result.message()));
        respond(exchange, result.status(), resultBody(result));
    }

    /** Token-protected local fake receiver for testing a CHZZK party donation. */
    private void partyTest(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        String channelId = Json.string(body.get("channelId"));
        Long amount = Json.longValue(body.get("amount"));
        String donor = Json.string(body.containsKey("donorName") ? body.get("donorName") : body.get("donor"));
        String eventId = Json.string(body.get("eventId"));
        validateChannelId(channelId);
        if (amount == null || amount <= 0) throw new BadRequestException("후원 금액은 양의 정수여야 합니다.");
        if (eventId != null && !eventId.isBlank() && !seenEvents.remember(eventId)) {
            respond(exchange, 200, Map.of("ok", true, "duplicate", true, "message", "이미 처리한 이벤트입니다."));
            return;
        }
        DonationDispatcher.DispatchResult result = dispatcher.receive(channelId, amount, donor, DonationType.PARTY, false);
        if (dispatcher.hasOnlinePartyReceiver(channelId)) {
            eventFeed.publish("party", channelId, Map.of("nickname", donor == null ? "테스트" : donor, "amount", amount,
                    "donationType", "PARTY", "result", result.message()));
        }
        respond(exchange, result.status(), resultBody(result));
    }

    private void dashboardPartyTest(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        String minecraftName = Json.string(body.get("minecraftName"));
        Long amount = Json.longValue(body.get("amount"));
        if (minecraftName == null) throw new BadRequestException("마인크래프트 닉네임이 필요합니다.");
        validateMinecraftName(minecraftName);
        if (amount == null || amount <= 0) throw new BadRequestException("후원 금액은 양의 정수여야 합니다.");
        BindingRepository.Binding binding = bindings.findPlayer(minecraftName);
        if (binding == null) throw new BadRequestException("이 마인크래프트 닉네임에 연결된 채널이 없습니다.");
        String donor = Json.string(body.get("donorName"));
        DonationDispatcher.DispatchResult result = dispatcher.receive(binding.platform(), binding.channelId(), amount,
                donor == null || donor.isBlank() ? "TEST" : donor, DonationType.PARTY, false);
        if (dispatcher.hasOnlinePartyReceiver(binding.platform(), binding.channelId())) {
            eventFeed.publish("party", binding.channelId(), Map.of("nickname", "TEST", "amount", amount,
                    "donationType", "PARTY", "platform", binding.platform().name(), "result", result.message()));
        }
        respond(exchange, result.status(), resultBody(result));
    }

    private void dashboardDonationEventTest(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        String minecraftName = Json.string(body.get("minecraftName"));
        String typeName = Json.string(body.get("type"));
        Long amount = Json.longValue(body.get("amount"));
        if (minecraftName == null) throw new BadRequestException("마인크래프트 닉네임이 필요합니다.");
        validateMinecraftName(minecraftName);
        DonationType type = DonationType.parse(typeName == null ? "" : typeName);
        if (type == null) throw new BadRequestException("올바른 후원 유형이 필요합니다.");
        BindingRepository.Binding binding = bindings.findPlayer(minecraftName);
        if (binding == null) throw new BadRequestException("이 마인크래프트 닉네임에 연결된 채널이 없습니다.");
        String donor = Json.string(body.get("donorName"));
        if (donor == null || donor.isBlank()) donor = "TEST";
        DonationDispatcher.DispatchResult result;
        if (type == DonationType.SUBSCRIPTION) {
            result = dispatcher.receiveEventForPlayer(minecraftName, donor, type);
        } else if (type == DonationType.FOLLOW) {
            result = dispatcher.receiveFollowForPlayer(minecraftName, donor);
        } else {
            if (amount == null || amount <= 0) throw new BadRequestException("후원 금액은 양의 정수여야 합니다.");
            if (type != DonationType.PARTY || dispatcher.partyUsesSelf(binding.platform(), amount)) {
                result = dispatcher.receiveForPlayer(minecraftName, amount, donor, type);
            } else {
                result = dispatcher.receive(binding.platform(), binding.channelId(), amount, donor, type, false);
            }
        }
        if (type == DonationType.PARTY ? dispatcher.hasOnlinePartyReceiver(binding.platform(), binding.channelId()) : dispatcher.hasOnlineReceiver(binding.platform(), binding.channelId())) {
            String eventType = type == DonationType.PARTY ? "party" : type == DonationType.SUBSCRIPTION ? "subscription" : type == DonationType.FOLLOW ? "follow" : "donation";
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("nickname", donor); event.put("donationType", type.name()); event.put("result", result.message());
            event.put("platform", binding.platform().name());
            if (amount != null && amount > 0) event.put("amount", amount);
            eventFeed.publish(eventType, binding.channelId(), event);
        }
        respond(exchange, result.status(), resultBody(result));
    }

    private void donationSetting(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        String minecraftName = Json.string(body.get("minecraftName"));
        String typeName = Json.string(body.get("type"));
        if (minecraftName == null) throw new BadRequestException("마인크래프트 닉네임이 필요합니다.");
        validateMinecraftName(minecraftName);
        DonationType type = DonationType.parse(typeName == null ? "" : typeName);
        if (type == null || !(body.get("enabled") instanceof Boolean enabled)) throw new BadRequestException("후원 유형과 활성화 여부가 필요합니다.");
        if (!bindings.setDonationType(minecraftName, type, enabled)) {
            respond(exchange, 404, Map.of("ok", false, "message", "이 마인크래프트 닉네임에 연결된 채널이 없습니다."));
            return;
        }
        respond(exchange, 200, Map.of("ok", true, "minecraftName", minecraftName, "type", type.name(), "enabled", enabled));
    }

    private void partyGroups(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        String action = Json.string(body.get("action"));
        String groupName = Json.string(body.get("groupName"));
        if (action == null || groupName == null) throw new BadRequestException("작업 종류와 그룹 이름이 필요합니다.");
        validatePartyGroupName(groupName);
        String minecraftName = Json.string(body.get("minecraftName"));
        String message;
        try {
            switch (action) {
                case "create" -> {
                    bindings.createPartyGroup(groupName.strip());
                    message = "파티후원 그룹을 만들었습니다.";
                }
                case "delete" -> {
                    if (!bindings.deletePartyGroup(groupName)) throw new BadRequestException("파티후원 그룹을 찾을 수 없습니다.");
                    message = "파티후원 그룹을 삭제했습니다.";
                }
                case "add" -> {
                    if (minecraftName == null) throw new BadRequestException("마인크래프트 닉네임이 필요합니다.");
                    validateMinecraftName(minecraftName);
                    bindings.addPartyMember(groupName, minecraftName);
                    message = minecraftName + " 플레이어를 그룹에 넣었습니다. 다른 그룹에 있었다면 자동으로 이동했습니다.";
                }
                case "remove" -> {
                    if (minecraftName == null) throw new BadRequestException("마인크래프트 닉네임이 필요합니다.");
                    validateMinecraftName(minecraftName);
                    if (!bindings.removePartyMember(groupName, minecraftName)) throw new BadRequestException("그룹에 이 플레이어가 없습니다.");
                    message = minecraftName + " 플레이어를 그룹에서 뺐습니다.";
                }
                default -> throw new BadRequestException("지원하지 않는 그룹 작업입니다.");
            }
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage());
        }
        respond(exchange, 200, Map.of("ok", true, "message", message));
    }

    private void saveDonationRules(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        Object rawRules = body.get("rules");
        if (!(rawRules instanceof List<?>)) throw new BadRequestException("rules 배열이 필요합니다.");
        try {
            dispatcher.replaceDashboardRules(Json.array(rawRules));
            respond(exchange, 200, Map.of("ok", true, "message", "후원 가격 설정을 저장하고 즉시 적용했습니다."));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage());
        }
    }

    private List<Object> playerList() throws Exception {
        Map<String, BindingRepository.Binding> linked = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (BindingRepository.Binding binding : bindings.all()) {
            linked.put(binding.minecraftName(), binding);
        }
        return callOnServerThread(() -> {
            Map<String, Map<String, Object>> players = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                String name = player.getName();
                if (name == null || name.isBlank()) continue;
                if (!player.isOnline() && bindings.isHidden(name)) continue;
                BindingRepository.Binding binding = linked.get(name);
                players.put(name, playerEntry(name, player.getUniqueId().toString(), player.isOnline(), player.isOp(), player.isBanned(), player.isWhitelisted(), binding));
            }
            for (BindingRepository.Binding binding : linked.values()) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(binding.minecraftName());
                players.putIfAbsent(binding.minecraftName(), playerEntry(binding.minecraftName(), null, false, player.isOp(), player.isBanned(), player.isWhitelisted(), binding));
            }
            for (String minecraftName : bindings.manualPlayers()) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(minecraftName);
                players.putIfAbsent(minecraftName, playerEntry(minecraftName, null, false, player.isOp(), player.isBanned(), player.isWhitelisted(), null));
            }
            // Whitelisted players may have no player-data file yet, so they are not guaranteed to be
            // included in Bukkit.getOfflinePlayers(). Include the authoritative whitelist separately.
            for (OfflinePlayer player : Bukkit.getWhitelistedPlayers()) {
                String name = player.getName();
                if (name == null || name.isBlank()) continue;
                BindingRepository.Binding binding = linked.get(name);
                players.putIfAbsent(name, playerEntry(name, player.getUniqueId().toString(), player.isOnline(), player.isOp(), player.isBanned(), true, binding));
            }
            return players.values().stream().map(value -> (Object) value).toList();
        });
    }

    /** Adds an offline name to the dashboard before the player has ever joined the Minecraft server. */
    private void addManualPlayer(HttpExchange exchange, Map<String, Object> body) throws IOException, BadRequestException {
        String minecraftName = Json.string(body.get("minecraftName"));
        if (minecraftName == null) throw new BadRequestException("마인크래프트 닉네임을 입력하세요.");
        validateMinecraftName(minecraftName);
        try {
            bindings.addManualPlayer(minecraftName);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage());
        }
        respond(exchange, 200, Map.of("ok", true, "message", minecraftName + " 플레이어를 미리 목록에 추가했습니다."));
    }

    private void playerAction(HttpExchange exchange, Map<String, Object> body) throws Exception {
        String minecraftName = Json.string(body.get("minecraftName"));
        String action = Json.string(body.get("action"));
        if (minecraftName == null || action == null) throw new BadRequestException("마인크래프트 닉네임과 작업 종류가 필요합니다.");
        validateMinecraftName(minecraftName);
        String result = callOnServerThread(() -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(minecraftName);
            Player online = target.getPlayer();
            return switch (action.toLowerCase(java.util.Locale.ROOT)) {
                case "kick" -> {
                    if (online == null) yield "현재 접속 중인 플레이어만 킥할 수 있습니다.";
                    online.kickPlayer("관리자 대시보드에서 킥되었습니다.");
                    yield minecraftName + " 플레이어를 킥했습니다.";
                }
                case "ban" -> {
                    target.banPlayer("관리자 대시보드에서 밴되었습니다.");
                    if (online != null) online.kickPlayer("관리자 대시보드에서 밴되었습니다.");
                    yield minecraftName + " 플레이어를 밴했습니다.";
                }
                case "op" -> {
                    target.setOp(true);
                    yield minecraftName + " 플레이어에게 OP 권한을 부여했습니다.";
                }
                case "deop" -> {
                    target.setOp(false);
                    yield minecraftName + " 플레이어의 OP 권한을 제거했습니다.";
                }
                case "whitelist-add" -> {
                    // Use the server command as the canonical path: it persists entries even for a
                    // name which has never joined and updates the server's whitelist cache.
                    if (!target.isWhitelisted()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist add " + minecraftName);
                    }
                    yield minecraftName + " 플레이어를 화이트리스트에 추가했습니다.";
                }
                case "whitelist-remove" -> {
                    if (target.isWhitelisted()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist remove " + minecraftName);
                    }
                    yield minecraftName + " 플레이어를 화이트리스트에서 삭제했습니다.";
                }
                default -> throw new BadRequestException("지원하지 않는 플레이어 관리 작업입니다.");
            };
        });
        respond(exchange, 200, Map.of("ok", true, "message", result));
    }

    /** Deliberately exposes only a small, named set of server controls; the dashboard never accepts arbitrary console commands. */
    private Map<String, Object> serverSettings() throws Exception {
        return callOnServerThread(() -> {
            World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            Map<String, Object> rules = new LinkedHashMap<>();
            if (world != null) for (GameRule<?> rule : GameRule.values()) {
                // Paper can expose compatibility GameRule constants which are not registered by this server version.
                // Only return rules that this actual world accepts.
                try {
                    Object value = gameRuleValue(world, rule);
                    rules.put(rule.getName(), Map.of("value", value, "type", value instanceof Boolean ? "boolean" : "integer"));
                } catch (IllegalArgumentException ignored) { }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("motd", Bukkit.getMotd());
            result.put("maxPlayers", Bukkit.getMaxPlayers());
            result.put("whitelistEnabled", Bukkit.hasWhitelist());
            result.put("gamerules", rules);
            synchronized (announcementHistory) { result.put("announcements", List.copyOf(announcementHistory)); }
            return result;
        });
    }

    private void updateServerSettings(HttpExchange exchange, Map<String, Object> body) throws Exception {
        String action = Json.string(body.get("action"));
        if (action == null) throw new BadRequestException("서버 설정 작업을 선택하세요.");
        String message = switch (action) {
            case "whitelist-toggle" -> {
                Boolean enabled = Json.bool(body.get("enabled"));
                if (enabled == null) throw new BadRequestException("화이트리스트 사용 여부가 필요합니다.");
                callOnServerThread(() -> {
                    // Bukkit.setWhitelist is the API equivalent of /whitelist on|off. Calling both
                    // caused the command to run twice and log a misleading "already turned" message.
                    if (Bukkit.hasWhitelist() != enabled) Bukkit.setWhitelist(enabled);
                    return null;
                });
                yield "화이트리스트를 " + (enabled ? "켰습니다." : "껐습니다.");
            }
            case "motd" -> {
                String motd = Json.string(body.get("motd"));
                if (motd == null || motd.isBlank() || motd.length() > 120 || motd.indexOf('\n') >= 0 || motd.indexOf('\r') >= 0) {
                    throw new BadRequestException("MOTD는 줄바꿈 없이 1~120자로 입력하세요.");
                }
                callOnServerThread(() -> { Bukkit.setMotd(motd); return null; });
                persistServerProperty("motd", motd);
                yield "서버 MOTD를 저장했습니다.";
            }
            case "max-players" -> {
                Long value = Json.longValue(body.get("maxPlayers"));
                if (value == null || value < 1 || value > 500) throw new BadRequestException("최대 인원은 1~500으로 설정하세요.");
                int maxPlayers = value.intValue();
                callOnServerThread(() -> { Bukkit.setMaxPlayers(maxPlayers); return null; });
                persistServerProperty("max-players", String.valueOf(maxPlayers));
                yield "최대 접속 인원을 " + maxPlayers + "명으로 변경했습니다.";
            }
            case "gamerule" -> updateGameRule(body);
            case "announcement" -> sendServerAnnouncement(body);
            case "restart" -> {
                boolean accepted = callOnServerThread(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart"));
                if (!accepted) throw new BadRequestException("이 서버에는 /restart 명령이 없습니다. 서버 관리 패널의 재시작 기능을 사용하세요.");
                yield "서버 재시작 명령을 전달했습니다.";
            }
            default -> throw new BadRequestException("지원하지 않는 서버 설정 작업입니다.");
        };
        respond(exchange, 200, Map.of("ok", true, "message", message));
    }

    private String sendServerAnnouncement(Map<String, Object> body) throws Exception {
        String content = Json.string(body.get("content"));
        if (!safeConsoleText(content, 240)) throw new BadRequestException("공지 내용은 줄바꿈 없이 1~240자로 입력하세요.");
        String message = content.strip().replace('§', ' ');
        int recipients = callOnServerThread(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage("");
                player.sendMessage("§b[서버 공지] §f" + message);
                player.sendMessage("");
            }
            return Bukkit.getOnlinePlayers().size();
        });
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("content", message);
        item.put("sentAt", System.currentTimeMillis());
        item.put("recipients", recipients);
        synchronized (announcementHistory) {
            announcementHistory.add(0, item);
            if (announcementHistory.size() > 30) announcementHistory.remove(announcementHistory.size() - 1);
        }
        return recipients + "명에게 서버 공지를 보냈습니다.";
    }

    private String updateGameRule(Map<String, Object> body) throws Exception {
        String name = Json.string(body.get("name"));
        if (name == null) throw new BadRequestException("게임룰을 선택하세요.");
        GameRule<?> rule = GameRule.getByName(name);
        if (rule == null) throw new BadRequestException("존재하지 않는 게임룰입니다.");
        Object rawValue = body.containsKey("value") ? body.get("value") : body.get("enabled");
        World sampleWorld = callOnServerThread(() -> Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
        if (sampleWorld == null) throw new BadRequestException("설정할 월드를 찾을 수 없습니다.");
        Object current;
        try {
            current = gameRuleValue(sampleWorld, rule);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("이 서버 버전에서는 지원하지 않는 게임룰입니다.");
        }
        Object value;
        if (current instanceof Boolean) {
            value = Json.bool(rawValue);
            if (value == null) throw new BadRequestException("게임룰 값은 켜기 또는 끄기여야 합니다.");
        } else if (current instanceof Integer) {
            Long number = Json.longValue(rawValue);
            if (number == null || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new BadRequestException("게임룰 숫자 값이 올바르지 않습니다.");
            }
            value = number.intValue();
        } else throw new BadRequestException("이 게임룰 값 형식은 지원하지 않습니다.");
        callOnServerThread(() -> {
            for (World world : Bukkit.getWorlds()) setGameRuleValue(world, rule, value);
            return null;
        });
        return "게임룰 " + name + "을 " + value + "으로 변경했습니다.";
    }

    private static <T> T gameRuleValue(World world, GameRule<T> rule) {
        return world.getGameRuleValue(rule);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setGameRuleValue(World world, GameRule<?> rule, Object value) {
        world.setGameRule((GameRule) rule, value);
    }

    /** Keeps MOTD and player-limit changes after a normal server restart when server.properties is available. */
    private void persistServerProperty(String key, String value) throws IOException {
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath();
        Path propertiesFile = container.resolve("server.properties");
        if (Files.notExists(propertiesFile) && container.getParent() != null) propertiesFile = container.getParent().resolve("server.properties");
        if (Files.notExists(propertiesFile)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesFile)) { properties.load(input); }
        properties.setProperty(key, value);
        try (OutputStream output = Files.newOutputStream(propertiesFile)) { properties.store(output, "Updated by ChzzkDonationBridge dashboard"); }
    }

    private void deletePlayerData(HttpExchange exchange, Map<String, Object> body) throws Exception {
        String minecraftName = Json.string(body.get("minecraftName"));
        if (minecraftName == null) throw new BadRequestException("마인크래프트 닉네임이 필요합니다.");
        validateMinecraftName(minecraftName);
        BindingRepository.Binding previous = bindings.findPlayer(minecraftName);
        bindings.forgetPlayer(minecraftName);
        disconnectUnused(previous);
        respond(exchange, 200, Map.of("ok", true, "message", minecraftName + " 플레이어의 후원 연결·수신 설정·파티 그룹 정보를 삭제했습니다."));
    }

    /** Runs only the dashboard's fixed punishment/timer command set; arbitrary console commands are never accepted. */
    private void consoleAction(HttpExchange exchange, Map<String, Object> body) throws Exception {
        String action = Json.string(body.get("action"));
        String minecraftName = Json.string(body.get("minecraftName"));
        if (action == null || minecraftName == null) throw new BadRequestException("작업 종류와 마인크래프트 닉네임이 필요합니다.");
        validateMinecraftName(minecraftName);
        String command;
        switch (action) {
            case "punishment" -> {
                String content = Json.string(body.get("content"));
                if (!safeConsoleText(content, 240)) throw new BadRequestException("벌칙 내용은 줄바꿈 없이 입력하세요.");
                command = "벌칙 " + minecraftName + " " + content.strip();
            }
            case "timer-start" -> {
                Long hours = Json.longValue(body.get("hours"));
                Long minutes = Json.longValue(body.get("minutes"));
                Long seconds = Json.longValue(body.get("seconds"));
                if (hours == null || hours < 0 || hours > 9_999_999 || minutes == null || minutes < 0 || minutes > 59
                        || seconds == null || seconds < 0 || seconds > 59) {
                    throw new BadRequestException("타이머는 시간(0 이상), 분·초(0~59)로 설정하세요.");
                }
                command = "타이머 시작 " + minecraftName + " " + hours + " " + minutes + " " + seconds;
            }
            case "timer-pause" -> command = "타이머 일시중지 " + minecraftName;
            case "timer-reset" -> command = "타이머 초기화 " + minecraftName;
            case "timer-reload" -> command = "타이머 리로드 " + minecraftName;
            default -> throw new BadRequestException("알 수 없는 콘솔 작업입니다.");
        }
        boolean dispatched = callOnServerThread(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        if (!dispatched) throw new BadRequestException("콘솔 명령을 찾지 못했습니다: /" + command);
        respond(exchange, 200, Map.of("ok", true, "message", "콘솔에서 실행했습니다: /" + command));
    }

    private static Map<String, Object> playerEntry(String name, String uuid, boolean online, boolean op, boolean banned, boolean whitelisted,
                                                    BindingRepository.Binding binding) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("minecraftName", name);
        entry.put("uuid", uuid);
        entry.put("online", online);
        entry.put("op", op);
        entry.put("banned", banned);
        entry.put("whitelisted", whitelisted);
        entry.put("linked", binding != null);
        if (binding != null) {
            entry.put("platform", binding.platform().name());
            entry.put("channelId", binding.channelId());
            entry.put("channelName", binding.channelName());
            entry.put("enabledDonationTypes", binding.enabledDonationTypes().stream().map(Enum::name).sorted().toList());
        }
        return entry;
    }

    private <T> T callOnServerThread(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) return task.call();
        return Bukkit.getScheduler().callSyncMethod(plugin, task).get();
    }

    private static Map<String, Object> resultBody(DonationDispatcher.DispatchResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.accepted());
        body.put("message", result.message());
        body.put("players", result.players());
        return body;
    }

    private static String value(Map<String, Object> body, String field, List<String> parts, int pathIndex) throws BadRequestException {
        String value = Json.string(body.get(field));
        if ((value == null || value.isBlank()) && parts.size() > pathIndex) {
            value = parts.get(pathIndex);
        }
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " 항목이 필요합니다.");
        }
        return value;
    }

    private static void validateMinecraftName(String name) throws BadRequestException {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            throw new BadRequestException("마인크래프트 닉네임은 올바른 자바 에디션 플레이어 이름이어야 합니다.");
        }
    }

    private static void validateChannelId(String channelId) throws BadRequestException {
        if (!channelId.matches("[@A-Za-z0-9_-]{2,128}")) {
            throw new BadRequestException("채널 ID에는 영문, 숫자, @, 밑줄(_) 및 하이픈(-)만 사용할 수 있습니다.");
        }
    }

    private void disconnectUnused(BindingRepository.Binding previous) {
        if (previous == null || !bindings.findChannel(previous.platform(), previous.channelId()).isEmpty()) return;
        if (previous.platform() == StreamingPlatform.CHZZK) {
            chzzkLiveClient.disconnect(previous.channelId());
            chzzkOpenApiClient.disconnect(previous.channelId());
        } else if (previous.platform() == StreamingPlatform.CIME) {
            cimeOfficialApiClient.disconnectUnused(previous.channelId());
            multiPlatformLiveClient.disconnect(previous.platform(), previous.channelId());
        } else {
            multiPlatformLiveClient.disconnect(previous.platform(), previous.channelId());
        }
    }

    private static void validatePartyGroupName(String name) throws BadRequestException {
        if (name.strip().isEmpty() || name.strip().length() > 48) {
            throw new BadRequestException("그룹 이름은 1~48자로 입력하세요.");
        }
    }

    private static boolean safeConsoleText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.strip().length() <= maxLength
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }

    private boolean authorized(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("Authorization");
        return provided != null && provided.startsWith("Bearer ") && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                provided.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> routeParts(String path) {
        if (!path.startsWith("/api")) {
            return List.of();
        }
        String remaining = path.substring(4);
        List<String> parts = new ArrayList<>();
        for (String part : remaining.split("/")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static Map<String, Object> requestBody(HttpExchange exchange) throws IOException, BadRequestException {
        byte[] bytes = exchange.getRequestBody().readNBytes(65_537);
        if (bytes.length > 65_536) {
            throw new BadRequestException("요청 본문이 너무 큽니다.");
        }
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            return Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("요청 본문은 JSON 객체여야 합니다.");
        }
    }

    private static void respond(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class BadRequestException extends Exception {
        BadRequestException(String message) {
            super(message);
        }
    }

    private static final class EventIdCache {
        private final int capacity;
        private final LinkedHashMap<String, Boolean> values = new LinkedHashMap<>(16, 0.75f, true);

        EventIdCache(int capacity) { this.capacity = Math.max(1, capacity); }

        synchronized boolean remember(String eventId) {
            if (values.containsKey(eventId)) {
                return false;
            }
            values.put(eventId, Boolean.TRUE);
            while (values.size() > capacity) {
                values.remove(values.keySet().iterator().next());
            }
            return true;
        }
    }
}
