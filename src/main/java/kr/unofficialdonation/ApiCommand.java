package kr.unofficialdonation;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.util.StringUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** In-game equivalent of the private HTTP API, usable by the console and server operators. */
final class ApiCommand implements CommandExecutor, TabCompleter {
    private final BindingRepository bindings;
    private final DonationDispatcher dispatcher;
    private final ChzzkLiveClient chzzk;
    private final MultiPlatformLiveClient multiPlatform;
    private final CimeOfficialApiClient cimeOfficialApiClient;
    private final ApiServer apiServer;
    private final JavaPlugin plugin;

    ApiCommand(JavaPlugin plugin, BindingRepository bindings, DonationDispatcher dispatcher, ChzzkLiveClient chzzk,
               MultiPlatformLiveClient multiPlatform, CimeOfficialApiClient cimeOfficialApiClient, ApiServer apiServer) {
        this.plugin = plugin;
        this.bindings = bindings;
        this.dispatcher = dispatcher;
        this.chzzk = chzzk;
        this.multiPlatform = multiPlatform;
        this.cimeOfficialApiClient = cimeOfficialApiClient;
        this.apiServer = apiServer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && (args[0].equalsIgnoreCase("대시보드") || args[0].equalsIgnoreCase("dashboard"))) {
            if (!sender.hasPermission("chzzkdonation.admin")) {
                sender.sendMessage("§cOP만 대시보드를 열 수 있습니다.");
                return true;
            }
            dashboard(sender);
            return true;
        }
        String requestedAction = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        boolean accountAction = requestedAction.equals("login") || requestedAction.equals("로그인")
                || requestedAction.equals("logout") || requestedAction.equals("로그아웃")
                || requestedAction.equals("official-login") || requestedAction.equals("공식로그인")
                || requestedAction.equals("official-logout") || requestedAction.equals("공식로그아웃");
        if (!sender.hasPermission("chzzkdonation.admin") && !(sender instanceof Player && accountAction)) {
            sender.sendMessage("§c이 명령을 사용할 권한이 없습니다.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String action = args[0].toLowerCase(java.util.Locale.ROOT);
        try {
            switch (action) {
                case "login", "로그인" -> login(sender, args);
                case "logout", "로그아웃" -> logout(sender, args);
                case "official-login", "공식로그인" -> officialLogin(sender, args);
                case "official-logout", "공식로그아웃" -> officialLogout(sender, args);
                case "donation-test", "후원테스트", "test" -> test(sender, args);
                case "party-test", "파티후원테스트", "partytest" -> partyTest(sender, args);
                case "donation-setting", "후원설정", "setting" -> setting(sender, args);
                default -> help(sender);
            }
        } catch (IOException exception) {
            sender.sendMessage("§c저장하지 못했습니다: " + exception.getMessage());
        }
        return true;
    }

    private void login(CommandSender sender, String[] args) throws IOException {
        // Once an official app is registered, the ordinary login command intentionally becomes
        // the player-specific OAuth shortcut. This keeps channel IDs out of in-game chat.
        if (apiServer.hasOfficialApiClientId()) {
            officialLogin(sender, args);
            return;
        }
        if (args.length != 4 || !validMinecraftName(args[1])) {
            sender.sendMessage("§e사용법: /api 로그인 <마크닉네임> <치지직|씨미|숲> <채널ID>");
            return;
        }
        if (!mayManagePlayer(sender, args[1])) {
            sender.sendMessage("§c일반 플레이어는 자신의 계정만 연결할 수 있습니다.");
            return;
        }
        StreamingPlatform platform = StreamingPlatform.parse(args[2]);
        String channelId = args[3];
        if (platform == null || !validChannelId(channelId)) {
            sender.sendMessage("§e사용법: /api 로그인 <마크닉네임> <치지직|씨미|숲> <채널ID>");
            return;
        }
        BindingRepository.Binding previous = bindings.findPlayer(args[1]);
        bindings.bind(args[1], platform, channelId);
        disconnectUnused(previous);
        if (platform == StreamingPlatform.CHZZK) chzzk.connect(channelId); else multiPlatform.connect(bindings.findPlayer(args[1]));
        sender.sendMessage("§a" + args[1] + " 님을 " + platform.koreanName() + " 채널 " + channelId + "에 연결했습니다.");
    }

    private void logout(CommandSender sender, String[] args) throws IOException {
        String minecraftName;
        if (sender instanceof Player player && !sender.hasPermission("chzzkdonation.admin") && args.length == 1) {
            minecraftName = player.getName();
        } else if (args.length == 2 && validMinecraftName(args[1])) {
            minecraftName = args[1];
        } else {
            sender.sendMessage(sender instanceof Player ? "§e사용법: /api 로그아웃 §7(내 연결 해제)" : "§e사용법: /api 로그아웃 <마크닉네임>");
            return;
        }
        if (!mayManagePlayer(sender, minecraftName)) {
            sender.sendMessage("§c일반 플레이어는 자신의 계정만 연결 해제할 수 있습니다.");
            return;
        }
        BindingRepository.Binding previous = bindings.findPlayer(minecraftName);
        boolean removed = bindings.unbind(minecraftName);
        if (removed) disconnectUnused(previous);
        sender.sendMessage(removed ? "§a연결을 해제했습니다." : "§e해당 닉네임에는 연결된 채널이 없습니다.");
    }

    /** Opens the provider login page; the callback performs the player binding automatically. */
    private void officialLogin(CommandSender sender, String[] args) throws IOException {
        String minecraftName;
        if (sender instanceof Player player) {
            if (args.length != 1) {
                sender.sendMessage("§e사용법: /api 공식로그인");
                return;
            }
            minecraftName = player.getName();
        } else {
            if (args.length != 2 || !validMinecraftName(args[1])) {
                sender.sendMessage("§e콘솔 사용법: /api 공식로그인 <마크닉네임>");
                return;
            }
            minecraftName = args[1];
        }
        if (!mayManagePlayer(sender, minecraftName)) {
            sender.sendMessage("§c일반 플레이어는 자신의 계정만 공식 로그인할 수 있습니다.");
            return;
        }
        ApiServer.OfficialLoginPortal portal = apiServer.startOfficialLoginPortalFromCommand(minecraftName);
        sender.sendMessage("§a" + minecraftName + " 님의 공식 API 연결 페이지를 만들었습니다.");
        sender.sendMessage("§7페이지에서 치지직·숲·씨미 중 연결할 플랫폼을 선택하세요.");
        if (sender instanceof Player player) {
            player.sendMessage(Component.text("[공식 API] 내 플레이어 연결 페이지 열기", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.openUrl(portal.url()))
                    .hoverEvent(Component.text("내 계정에 연결할 플랫폼을 선택")));
        } else {
            sender.sendMessage("공식 API 연결 페이지: " + portal.url());
        }
    }

    private void officialLogout(CommandSender sender, String[] args) throws IOException {
        String minecraftName;
        if (sender instanceof Player player) {
            if (args.length != 1) {
                sender.sendMessage("§e사용법: /api 공식로그아웃");
                return;
            }
            minecraftName = player.getName();
        } else {
            if (args.length != 2 || !validMinecraftName(args[1])) {
                sender.sendMessage("§e콘솔 사용법: /api 공식로그아웃 <마크닉네임>");
                return;
            }
            minecraftName = args[1];
        }
        if (!mayManagePlayer(sender, minecraftName)) {
            sender.sendMessage("§c일반 플레이어는 자신의 계정만 연결 해제할 수 있습니다.");
            return;
        }
        BindingRepository.Binding previous = bindings.findPlayer(minecraftName);
        boolean removed = bindings.unbind(minecraftName);
        if (removed) {
            disconnectUnused(previous);
            apiServer.cancelOfficialLogin();
        }
        sender.sendMessage(removed ? "§a공식 API 연결과 채널 연결을 해제했습니다." : "§e이 플레이어에는 연결된 채널이 없습니다.");
    }

    private void test(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3 || !validMinecraftName(args[1])) {
            sender.sendMessage("§e사용법: /api 후원테스트 <마크닉네임> [금액]");
            return;
        }
        Long amount = null;
        if (args.length == 3) {
            try { amount = Long.parseLong(args[2]); } catch (NumberFormatException ignored) { }
            if (amount == null || amount <= 0) {
                sender.sendMessage("§c금액은 양의 정수여야 합니다.");
                return;
            }
        }
        dispatcher.submitTest(args[1], amount, result -> plugin.getServer().getScheduler().runTask(plugin,
                () -> sender.sendMessage(result.accepted() ? "§a후원 명령을 실행 대기열에 넣었습니다." : "§c" + result.message())));
        sender.sendMessage("§7후원 테스트를 처리 중입니다.");
    }

    private void partyTest(CommandSender sender, String[] args) {
        if (args.length != 3 || !validMinecraftName(args[1])) {
            sender.sendMessage("§e사용법: /api 파티후원테스트 <마크닉네임> <금액>");
            return;
        }
        Long amount;
        try { amount = Long.parseLong(args[2]); } catch (NumberFormatException exception) { amount = null; }
        if (amount == null || amount <= 0) {
            sender.sendMessage("§c금액은 양의 정수여야 합니다.");
            return;
        }
        BindingRepository.Binding binding = bindings.findPlayer(args[1]);
        if (binding == null) {
            sender.sendMessage("§c먼저 /api 로그인으로 이 플레이어를 연결하세요.");
            return;
        }
        boolean self = dispatcher.partyUsesSelf(binding.platform(), amount);
        final long testAmount = amount;
        final String testPlayer = args[1];
        dispatcher.submit(() -> {
            DonationDispatcher.DispatchResult result = self
                    ? dispatcher.receiveForPlayer(testPlayer, testAmount, "TEST", DonationType.PARTY)
                    : dispatcher.receive(binding.platform(), binding.channelId(), testAmount, "TEST", DonationType.PARTY, false);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(result.accepted() ? "§a파티후원 테스트 명령을 실행 대기열에 넣었습니다." : "§c" + result.message());
            });
        });
        sender.sendMessage("§7파티후원 테스트를 처리 중입니다.");
    }

    private void setting(CommandSender sender, String[] args) throws IOException {
        if (args.length != 4 || !validMinecraftName(args[1])) {
            sender.sendMessage("§e사용법: /api 후원설정 <마크닉네임> <채팅후원|영상후원|미션후원|구독후원|파티후원> <true|false>");
            return;
        }
        DonationType type = DonationType.parse(args[2]);
        if (type == null || !(args[3].equalsIgnoreCase("true") || args[3].equalsIgnoreCase("false"))) {
            sender.sendMessage("§c후원 유형 또는 true|false 값이 올바르지 않습니다.");
            return;
        }
        boolean enabled = Boolean.parseBoolean(args[3]);
        if (!bindings.setDonationType(args[1], type, enabled)) {
            sender.sendMessage("§c먼저 /api 로그인으로 이 플레이어를 연결하세요.");
            return;
        }
        sender.sendMessage("§a" + args[1] + "의 " + type.koreanName() + " 수신을 " + (enabled ? "켰습니다." : "껐습니다."));
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§e/api 대시보드");
        if (apiServer.hasOfficialApiClientId()) sender.sendMessage("§e/api 로그인 §7(인게임 본인용 공식 플랫폼 연결 링크)");
        else sender.sendMessage("§e/api 로그인 <마크닉네임> <치지직|씨미|숲> <채널ID>");
        sender.sendMessage("§e/api 공식로그인 §7(인게임 본인용 플랫폼 선택 페이지)");
        sender.sendMessage("§e/api 공식로그아웃 §7(인게임 본인 연결 해제)");
        sender.sendMessage("§e/api 후원테스트 <마크닉네임> [금액]");
        sender.sendMessage("§e/api 파티후원테스트 <마크닉네임> <금액>");
        sender.sendMessage("§e/api 후원설정 <마크닉네임> <후원유형> <true|false>");
        sender.sendMessage("§e/api 로그아웃 <마크닉네임>");
    }

    private void dashboard(CommandSender sender) {
        String url = dashboardUrl();
        if (url == null) {
            sender.sendMessage("§e관리자가 config.yml의 dashboard-url에 외부 접속 주소를 설정해야 합니다.");
            return;
        }
        if (sender instanceof Player player) {
            player.sendMessage(Component.text("[CHZZK] 웹 대시보드 열기", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(Component.text(url)));
            player.sendMessage(Component.text("위 메시지를 클릭하면 브라우저에서 열립니다.", NamedTextColor.GRAY));
        } else {
            sender.sendMessage("CHZZK Dashboard: " + url);
        }
    }

    private String dashboardUrl() {
        String configured = plugin.getConfig().getString("dashboard-url", "").strip();
        if (!configured.isBlank()) return configured;
        String address = plugin.getConfig().getString("bind-address", "127.0.0.1");
        if (address.equals("127.0.0.1") || address.equals("localhost") || address.equals("0.0.0.0")) return null;
        return "http://" + address + ":" + plugin.getConfig().getInt("port", 8765) + "/dashboard";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return StringUtil.copyPartialMatches(args[0], List.of("대시보드", "공식로그인", "공식로그아웃", "로그인", "후원테스트", "파티후원테스트", "후원설정", "로그아웃"), new ArrayList<>());
        if (args[0].equalsIgnoreCase("로그인") || args[0].equalsIgnoreCase("login")) {
            // With official credentials, /api 로그인 has no manual channel-ID arguments.
            if (apiServer.hasOfficialApiClientId()) return List.of();
            if (args.length == 2) {
                List<String> names = sender instanceof Player player && !sender.hasPermission("chzzkdonation.admin")
                        ? List.of(player.getName()) : Bukkit.getOnlinePlayers().stream().map(player -> player.getName()).sorted().toList();
                return StringUtil.copyPartialMatches(args[1], names, new ArrayList<>());
            }
            if (args.length == 3) return StringUtil.copyPartialMatches(args[2], List.of("치지직", "씨미", "숲"), new ArrayList<>());
            return List.of();
        }
        if (args.length == 2 && isPlayerNamePosition(args[0])) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(player -> player.getName()).sorted().toList();
            return StringUtil.copyPartialMatches(args[1], names, new ArrayList<>());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("후원설정") || args[0].equalsIgnoreCase("donation-setting"))) return StringUtil.copyPartialMatches(args[2], List.of("채팅후원", "영상후원", "미션후원", "구독후원", "파티후원"), new ArrayList<>());
        if (args.length == 4 && (args[0].equalsIgnoreCase("후원설정") || args[0].equalsIgnoreCase("donation-setting"))) return StringUtil.copyPartialMatches(args[3], List.of("true", "false"), new ArrayList<>());
        return List.of();
    }

    private static boolean isPlayerNamePosition(String action) {
        return action.equalsIgnoreCase("로그인") || action.equalsIgnoreCase("login")
                || action.equalsIgnoreCase("공식로그인") || action.equalsIgnoreCase("official-login")
                || action.equalsIgnoreCase("로그아웃") || action.equalsIgnoreCase("logout")
                || action.equalsIgnoreCase("공식로그아웃") || action.equalsIgnoreCase("official-logout")
                || action.equalsIgnoreCase("후원테스트") || action.equalsIgnoreCase("donation-test") || action.equalsIgnoreCase("test")
                || action.equalsIgnoreCase("파티후원테스트") || action.equalsIgnoreCase("party-test") || action.equalsIgnoreCase("partytest")
                || action.equalsIgnoreCase("후원설정") || action.equalsIgnoreCase("donation-setting") || action.equalsIgnoreCase("setting");
    }

    private static boolean validMinecraftName(String value) { return value.matches("[A-Za-z0-9_]{3,16}"); }
    private static boolean validChannelId(String value) { return value.matches("[@A-Za-z0-9_-]{2,128}"); }

    /** Non-operators may only create or remove the binding belonging to their own player. */
    private static boolean mayManagePlayer(CommandSender sender, String minecraftName) {
        return sender.hasPermission("chzzkdonation.admin")
                || sender instanceof Player player && player.getName().equalsIgnoreCase(minecraftName);
    }

    private void disconnectUnused(BindingRepository.Binding previous) {
        if (previous == null || !bindings.findChannel(previous.platform(), previous.channelId()).isEmpty()) return;
        if (previous.platform() == StreamingPlatform.CHZZK) chzzk.disconnect(previous.channelId());
        else if (previous.platform() == StreamingPlatform.CIME) {
            cimeOfficialApiClient.disconnectUnused(previous.channelId());
            multiPlatform.disconnect(previous.platform(), previous.channelId());
        } else multiPlatform.disconnect(previous.platform(), previous.channelId());
    }
}
