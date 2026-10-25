package kr.unofficialdonation;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Matches donation amounts to donation.json and always executes commands on the server thread. */
final class DonationDispatcher {
    private final JavaPlugin plugin;
    private final BindingRepository bindings;
    private final Path rulesFile;
    private final ExecutorService donationWorkers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ChzzkDonationWorker");
        thread.setDaemon(true);
        return thread;
    });
    /** A single FIFO keeps Minecraft command effects ordered even when event decoding runs in parallel. */
    private final Object commandQueueLock = new Object();
    private final ArrayDeque<QueuedCommandBatch> commandQueue = new ArrayDeque<>();
    private boolean commandQueueRunning;
    /** Parsed rule data is immutable and reused until donation.json is changed externally or through the dashboard. */
    private final Object ruleCacheLock = new Object();
    private volatile RuleSnapshot cachedRuleSnapshot;
    private volatile RuleFileStamp cachedRuleStamp;

    DonationDispatcher(JavaPlugin plugin, BindingRepository bindings, Path rulesFile) {
        this.plugin = plugin;
        this.bindings = bindings;
        this.rulesFile = rulesFile;
    }

    DispatchResult test(String minecraftName, Long suppliedAmount) {
        BindingRepository.Binding binding = bindings.findPlayer(minecraftName);
        if (binding == null) {
            return DispatchResult.notFound("이 마인크래프트 닉네임에 연결된 채널이 없습니다.");
        }
        long amount = suppliedAmount == null ? plugin.getConfig().getLong("test-amount", 1000) : suppliedAmount;
        return dispatchToPlayer(binding, binding.platform(), amount, "TEST", DonationType.CHAT);
    }

    /** Queues parsing, rule lookup and recipient selection away from the Minecraft server thread. */
    void submitTest(String minecraftName, Long suppliedAmount, Consumer<DispatchResult> callback) {
        submit(() -> callback.accept(test(minecraftName, suppliedAmount)));
    }

    void submitReceive(String channelId, long amount, String donorName, DonationType type, Consumer<DispatchResult> callback) {
        submit(() -> callback.accept(receive(channelId, amount, donorName, type, false)));
    }

    void submitReceive(StreamingPlatform platform, String channelId, long amount, String donorName, DonationType type, Consumer<DispatchResult> callback) {
        submit(() -> callback.accept(receive(platform, channelId, amount, donorName, type, false)));
    }

    void submitReceiveEvent(String channelId, String donorName, DonationType type, Consumer<DispatchResult> callback) {
        submit(() -> callback.accept(receiveEvent(channelId, donorName, type, false)));
    }

    void submit(Runnable task) {
        if (!plugin.isEnabled()) return;
        donationWorkers.execute(() -> {
            try {
                task.run();
            } catch (Exception exception) {
                plugin.getLogger().warning("대기 중인 후원 이벤트를 처리하지 못했습니다: " + exception.getMessage());
            }
        });
    }

    void close() {
        donationWorkers.shutdownNow();
        synchronized (commandQueueLock) {
            commandQueue.clear();
            commandQueueRunning = false;
        }
    }

    DispatchResult receive(String channelId, long amount, String donorName) {
        return receive(channelId, amount, donorName, DonationType.CHAT, true);
    }

    DispatchResult receive(String channelId, long amount, String donorName, DonationType type, boolean ignoredLegacyShareSetting) {
        return receive(StreamingPlatform.CHZZK, channelId, amount, donorName, type, ignoredLegacyShareSetting);
    }

    DispatchResult receive(StreamingPlatform platform, String channelId, long amount, String donorName, DonationType type, boolean ignoredLegacyShareSetting) {
        List<BindingRepository.Binding> targets = type == DonationType.PARTY ? onlinePartyTargets(platform, channelId) : onlineTargets(platform, channelId);
        if (type == DonationType.PARTY) {
            try {
                DonationRule rule = rules().stream().filter(candidate -> candidate.matches(platform, amount, DonationType.PARTY)).findFirst().orElse(null);
                if (rule != null && "SELF".equals(rule.partyTargetMode())) {
                    List<BindingRepository.Binding> directTargets = onlineTargets(platform, channelId);
                    // SELF means exactly one receiver even when an administrator linked several names to one channel.
                    targets = directTargets.isEmpty() ? List.of() : List.of(directTargets.get(0));
                }
                targets = selectPartyTargets(targets, rule);
            } catch (Exception exception) {
                return DispatchResult.error("donation.json을 읽지 못했습니다.");
            }
        }
        if (targets.isEmpty()) {
            return DispatchResult.notFound("이 채널에 연결된 마인크래프트 플레이어가 없습니다.");
        }

        List<String> queued = new ArrayList<>();
        for (BindingRepository.Binding target : targets) {
            DispatchResult result = dispatchToPlayer(target, platform, amount, donorName, type);
            if (result.accepted()) {
                queued.add(target.minecraftName());
            }
        }
        if (queued.isEmpty()) {
            return DispatchResult.unmatched("이 후원 이벤트와 일치하는 활성 플레이어 또는 규칙이 없습니다.");
        }
        return DispatchResult.accepted(queued);
    }

    /** Used by the dashboard/command test path so SELF always means the selected Minecraft player. */
    DispatchResult receiveForPlayer(String minecraftName, long amount, String donorName, DonationType type) {
        BindingRepository.Binding target = bindings.findPlayer(minecraftName);
        if (target == null) return DispatchResult.notFound("이 마인크래프트 닉네임에 연결된 채널이 없습니다.");
        if (!onlineOnly(List.of(target)).contains(target)) return DispatchResult.notFound("선택한 마인크래프트 플레이어가 접속 중이 아닙니다.");
        return dispatchToPlayer(target, target.platform(), amount, donorName, type);
    }

    DispatchResult receiveEventForPlayer(String minecraftName, String donorName, DonationType type) {
        BindingRepository.Binding target = bindings.findPlayer(minecraftName);
        if (target == null) return DispatchResult.notFound("이 마인크래프트 닉네임에 연결된 채널이 없습니다.");
        if (!onlineOnly(List.of(target)).contains(target)) return DispatchResult.notFound("선택한 마인크래프트 플레이어가 접속 중이 아닙니다.");
        EventRule rule;
        try { rule = eventRules().stream().filter(candidate -> candidate.type() == type).findFirst().orElse(null); }
        catch (Exception exception) { return DispatchResult.error("donation.json을 읽지 못했습니다."); }
        if (!target.receives(type)) return DispatchResult.unmatched("이 플레이어는 해당 후원 유형 수신이 꺼져 있습니다.");
        if (rule != null) queueCommands(target, 0, donorName, type, rule.commands(), 0);
        return DispatchResult.accepted(List.of(target.minecraftName()), rule == null
                ? "이벤트를 수신했지만 설정된 이벤트 규칙이 없습니다." : "후원 명령을 실행 대기열에 넣었습니다.");
    }

    boolean partyUsesSelf(long amount) {
        return partyUsesSelf(StreamingPlatform.CHZZK, amount);
    }

    boolean partyUsesSelf(StreamingPlatform platform, long amount) {
        try {
            return rules().stream().filter(rule -> rule.matches(platform, amount, DonationType.PARTY))
                    .anyMatch(rule -> "SELF".equals(rule.partyTargetMode()));
        } catch (IOException ignored) {
            return false;
        }
    }

    DispatchResult receiveEvent(String channelId, String donorName, DonationType type, boolean ignoredLegacyShareSetting) {
        return receiveEvent(StreamingPlatform.CHZZK, channelId, donorName, type, ignoredLegacyShareSetting);
    }

    DispatchResult receiveEvent(StreamingPlatform platform, String channelId, String donorName, DonationType type, boolean ignoredLegacyShareSetting) {
        List<BindingRepository.Binding> targets = type == DonationType.PARTY ? onlinePartyTargets(platform, channelId) : onlineTargets(platform, channelId);
        EventRule rule;
        try { rule = eventRules().stream().filter(candidate -> candidate.type() == type).findFirst().orElse(null); }
        catch (Exception exception) { return DispatchResult.error("donation.json을 읽지 못했습니다."); }
        List<String> accepted = targets.stream().filter(target -> target.receives(type)).map(BindingRepository.Binding::minecraftName).toList();
        if (accepted.isEmpty()) return DispatchResult.unmatched("연결된 플레이어 중 이 후원 유형 수신이 켜진 플레이어가 없습니다.");
        if (rule == null) return DispatchResult.accepted(accepted, "이벤트를 수신했지만 설정된 이벤트 규칙이 없습니다.");
        for (BindingRepository.Binding target : targets) {
            if (target.receives(type)) queueCommands(target, 0, donorName, type, rule.commands(), 0);
        }
        return DispatchResult.accepted(accepted);
    }

    /** A follow uses the one price-free follow rule, rather than a donation amount rule. */
    void submitFollow(String channelId, String followerName, Consumer<DispatchResult> callback) {
        submitFollow(StreamingPlatform.CHZZK, channelId, followerName, callback);
    }

    void submitFollow(StreamingPlatform platform, String channelId, String followerName, Consumer<DispatchResult> callback) {
        submit(() -> callback.accept(receiveFollow(platform, channelId, followerName)));
    }

    private DispatchResult receiveFollow(StreamingPlatform platform, String channelId, String followerName) {
        List<BindingRepository.Binding> targets = onlineTargets(platform, channelId).stream()
                .filter(binding -> binding.receives(DonationType.FOLLOW)).toList();
        if (targets.isEmpty()) return DispatchResult.notFound("이 채널에 연결된 마인크래프트 플레이어가 없습니다.");
        FollowRule rule;
        try { rule = followRule(); }
        catch (Exception exception) { return DispatchResult.error("donation.json을 읽지 못했습니다."); }
        if (rule == null) return DispatchResult.unmatched("설정된 팔로우 규칙이 없습니다.");
        for (BindingRepository.Binding target : targets) {
            queueCommands(target, 0, followerName, DonationType.FOLLOW, rule.commands(), rule.durationMs());
        }
        return DispatchResult.accepted(targets.stream().map(BindingRepository.Binding::minecraftName).toList(), "팔로우 명령을 실행 대기열에 넣었습니다.");
    }

    DispatchResult receiveFollowForPlayer(String minecraftName, String followerName) {
        BindingRepository.Binding target = bindings.findPlayer(minecraftName);
        if (target == null) return DispatchResult.notFound("이 마인크래프트 닉네임에 연결된 채널이 없습니다.");
        if (!onlineOnly(List.of(target)).contains(target)) return DispatchResult.notFound("선택한 마인크래프트 플레이어가 접속 중이 아닙니다.");
        if (!target.receives(DonationType.FOLLOW)) return DispatchResult.unmatched("이 플레이어는 팔로우 수신이 꺼져 있습니다.");
        FollowRule rule;
        try { rule = followRule(); }
        catch (Exception exception) { return DispatchResult.error("donation.json을 읽지 못했습니다."); }
        if (rule == null) return DispatchResult.unmatched("설정된 팔로우 규칙이 없습니다.");
        queueCommands(target, 0, followerName, DonationType.FOLLOW, rule.commands(), rule.durationMs());
        return DispatchResult.accepted(List.of(target.minecraftName()), "팔로우 명령을 실행 대기열에 넣었습니다.");
    }

    boolean hasOnlineReceiver(String channelId) {
        return hasOnlineReceiver(StreamingPlatform.CHZZK, channelId);
    }

    boolean hasOnlineReceiver(StreamingPlatform platform, String channelId) {
        return !onlineTargets(platform, channelId).isEmpty();
    }

    boolean hasOnlineEnabledReceiver(String channelId, DonationType type) {
        return hasOnlineEnabledReceiver(StreamingPlatform.CHZZK, channelId, type);
    }

    boolean hasOnlineEnabledReceiver(StreamingPlatform platform, String channelId, DonationType type) {
        List<BindingRepository.Binding> targets = type == DonationType.PARTY ? onlinePartyTargets(platform, channelId) : onlineTargets(platform, channelId);
        return targets.stream().anyMatch(binding -> binding.receives(type));
    }

    boolean hasOnlinePartyReceiver(String channelId) {
        return hasOnlinePartyReceiver(StreamingPlatform.CHZZK, channelId);
    }

    boolean hasOnlinePartyReceiver(StreamingPlatform platform, String channelId) {
        return !onlinePartyTargets(platform, channelId).isEmpty();
    }

    private List<BindingRepository.Binding> onlineTargets(String channelId) {
        return onlineTargets(StreamingPlatform.CHZZK, channelId);
    }

    private List<BindingRepository.Binding> onlineTargets(StreamingPlatform platform, String channelId) {
        return onlineOnly(bindings.findChannel(platform, channelId));
    }

    private List<BindingRepository.Binding> onlinePartyTargets(String channelId) {
        return onlinePartyTargets(StreamingPlatform.CHZZK, channelId);
    }

    private List<BindingRepository.Binding> onlinePartyTargets(StreamingPlatform platform, String channelId) {
        return onlineOnly(bindings.partyTargets(platform, channelId));
    }

    private List<BindingRepository.Binding> onlineOnly(List<BindingRepository.Binding> candidates) {
        if (candidates.isEmpty()) return List.of();
        try {
            java.util.concurrent.Callable<List<BindingRepository.Binding>> lookup = () -> candidates.stream()
                    .filter(binding -> Bukkit.getPlayerExact(binding.minecraftName()) != null).toList();
            // Commands run on the primary thread. Scheduling another primary-thread task and waiting for it here deadlocks the server.
            if (Bukkit.isPrimaryThread()) return lookup.call();
            return Bukkit.getScheduler().callSyncMethod(plugin, lookup).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** Reads donation.json for the dashboard. Rules are read at dispatch time, so saving is immediately live. */
    synchronized List<Map<String, Object>> dashboardRules() throws IOException {
        Map<String, Object> root = Json.object(Json.parse(Files.readString(rulesFile, StandardCharsets.UTF_8)));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object rawRule : Json.array(root.get("rules"))) {
            Map<String, Object> rule = Json.object(rawRule);
            Map<StreamingPlatform, Long> platformAmounts = platformAmounts(rule);
            String ruleType = ruleType(rule);
            boolean follow = "FOLLOW".equals(ruleType);
            if (!follow && platformAmounts.isEmpty()) continue;
            long chzzkAmount = platformAmounts.isEmpty() ? 0L : platformAmounts.getOrDefault(StreamingPlatform.CHZZK, platformAmounts.values().iterator().next());
            List<String> commands = Json.array(rule.get("commands")).stream().map(Json::string)
                    .filter(command -> command != null && !command.isBlank()).toList();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", follow ? defaultFollowName(Json.string(rule.get("name"))) : defaultName(Json.string(rule.get("name")), chzzkAmount));
            // amount is retained for older dashboards; new dashboards use platformAmounts.
            if (!follow) {
                entry.put("amount", chzzkAmount);
                entry.put("platformAmounts", platformAmountsForJson(platformAmounts));
            }
            entry.put("follow", follow); // compatibility with older dashboard clients
            entry.put("ruleType", ruleType);
            entry.put("commands", commands);
            entry.put("durationMs", ruleDurationMs(rule));
            entry.put("partyTargetMode", partyTargetMode(rule));
            entry.put("partyRandomCount", partyRandomCount(rule));
            result.add(entry);
        }
        return result;
    }

    synchronized void replaceDashboardRules(List<Object> rawRules) throws IOException {
        if (rawRules.size() > 100) throw new IllegalArgumentException("규칙은 최대 100개까지 저장할 수 있습니다.");
        List<Object> rules = new ArrayList<>();
        Map<String, Map<StreamingPlatform, Set<Long>>> amountsByType = new LinkedHashMap<>();
        boolean followRuleExists = false;
        for (Object rawRule : rawRules) {
            Map<String, Object> rule = Json.object(rawRule);
            Map<StreamingPlatform, Long> platformAmounts = platformAmounts(rule);
            String ruleType = ruleType(rule);
            boolean follow = "FOLLOW".equals(ruleType);
            String name = Json.string(rule.get("name"));
            List<String> commands = Json.array(rule.get("commands")).stream().map(Json::string)
                    .filter(command -> command != null && !command.isBlank()).map(String::strip).toList();
            Long durationMs = Json.longValue(rule.get("durationMs"));
            if (durationMs == null) durationMs = 0L;
            String partyTargetMode = Json.string(rule.get("partyTargetMode"));
            if (partyTargetMode == null || partyTargetMode.isBlank()) partyTargetMode = "ALL";
            partyTargetMode = partyTargetMode.strip().toUpperCase(java.util.Locale.ROOT);
            Long partyRandomCount = Json.longValue(rule.get("partyRandomCount"));
            if (!follow && !"SUBSCRIPTION".equals(ruleType) && platformAmounts.size() != StreamingPlatform.values().length) {
                throw new IllegalArgumentException("치지직(치즈), 숲(별풍선), 씨미(빔) 가격을 모두 1 이상으로 입력하세요.");
            }
            if (name == null || name.isBlank() || name.strip().length() > 80) throw new IllegalArgumentException("규칙 이름은 1~80자로 입력하세요.");
            if (commands.isEmpty()) throw new IllegalArgumentException("'" + name + "' 규칙에 실행 명령어를 한 개 이상 입력하세요.");
            if (durationMs < 0 || durationMs > 86_400_000L) throw new IllegalArgumentException("다음 후원 전 대기시간은 0~86,400,000ms로 설정하세요.");
            if (follow) {
                if (followRuleExists) throw new IllegalArgumentException("팔로우 규칙은 한 개만 등록할 수 있습니다.");
                followRuleExists = true;
            } else if (!"SUBSCRIPTION".equals(ruleType)) {
                Map<StreamingPlatform, Set<Long>> amounts = amountsByType.computeIfAbsent(ruleType, ignored -> {
                    Map<StreamingPlatform, Set<Long>> value = new LinkedHashMap<>();
                    for (StreamingPlatform platform : StreamingPlatform.values()) value.put(platform, new LinkedHashSet<>());
                    return value;
                });
                for (StreamingPlatform platform : StreamingPlatform.values()) {
                    if (!amounts.get(platform).add(platformAmounts.get(platform))) {
                        throw new IllegalArgumentException(platform.koreanName() + "에서 같은 후원 금액은 한 번만 등록할 수 있습니다.");
                    }
                }
            }
            if (!partyTargetMode.equals("ALL") && !partyTargetMode.equals("RANDOM") && !partyTargetMode.equals("SELF")) throw new IllegalArgumentException("파티후원 실행 방식은 ALL, RANDOM 또는 SELF여야 합니다.");
            if (partyRandomCount == null) partyRandomCount = 1L;
            if (partyRandomCount < 1 || partyRandomCount > 100) throw new IllegalArgumentException("파티후원 랜덤 인원은 1~100명으로 설정하세요.");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name.strip());
            entry.put("ruleType", ruleType);
            if (follow) entry.put("follow", true);
            else if (!"SUBSCRIPTION".equals(ruleType)) {
                entry.put("platformAmounts", platformAmountsForJson(platformAmounts));
                // Keep a simple Chzzk value for old dashboard clients and third-party readers.
                entry.put("amount", platformAmounts.get(StreamingPlatform.CHZZK));
            }
            entry.put("commands", commands);
            entry.put("durationMs", durationMs);
            entry.put("partyTargetMode", partyTargetMode);
            entry.put("partyRandomCount", partyRandomCount);
            rules.add(entry);
        }
        Map<String, Object> root = Json.object(Json.parse(Files.readString(rulesFile, StandardCharsets.UTF_8)));
        root.put("rules", rules);
        Path temporary = rulesFile.resolveSibling(rulesFile.getFileName() + ".tmp");
        Files.writeString(temporary, Json.writePretty(root), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, rulesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, rulesFile, StandardCopyOption.REPLACE_EXISTING);
        }
        invalidateRuleCache();
        rules(); // Parse once now: saving is rejected if the new file cannot be applied.
    }

    synchronized void reloadDashboardRules() throws IOException {
        invalidateRuleCache();
        ruleSnapshot();
    }

    private DispatchResult dispatchToPlayer(BindingRepository.Binding binding, StreamingPlatform sourcePlatform, long amount, String donorName, DonationType type) {
        if (!binding.receives(type)) return DispatchResult.unmatched("이 플레이어는 해당 후원 유형 수신이 꺼져 있습니다.");
        String player = binding.minecraftName();
        String channelId = binding.channelId();
        DonationRule rule;
        try {
            rule = rules().stream().filter(candidate -> candidate.matches(sourcePlatform, amount, type)).findFirst().orElse(null);
        } catch (Exception exception) {
            plugin.getLogger().warning("donation.json을 읽지 못했습니다: " + exception.getMessage());
            return DispatchResult.error("donation.json을 읽지 못했습니다.");
        }
        if (rule == null) {
            return DispatchResult.unmatched("이 후원 금액과 일치하는 규칙이 없습니다.");
        }

        queueCommands(binding, amount, donorName, type, rule.commands(), rule.durationMs());
        return DispatchResult.accepted(List.of(player));
    }

    private void queueCommands(BindingRepository.Binding binding, long amount, String donorName, DonationType type, List<String> commands, long delayAfterMs) {
        String safeDonor = sanitizeDonor(donorName);
        List<String> expandedCommands = commands.stream().map(command -> command
                .replace("{player}", binding.minecraftName())
                .replace("{amount}", Long.toString(amount))
                .replace("{donor}", safeDonor)
                .replace("{channelId}", binding.channelId())
                .replace("{type}", type.name()).strip())
                .filter(command -> !command.isEmpty()).toList();
        if (expandedCommands.isEmpty()) return;
        boolean startQueue = false;
        synchronized (commandQueueLock) {
            commandQueue.addLast(new QueuedCommandBatch(expandedCommands, Math.max(0, delayAfterMs), 0));
            if (!commandQueueRunning) {
                commandQueueRunning = true;
                startQueue = true;
            }
        }
        if (startQueue) Bukkit.getScheduler().runTask(plugin, this::runNextQueuedCommandBatch);
    }

    /** Always called on the Minecraft server thread; one completed donation batch unlocks the next batch. */
    private void runNextQueuedCommandBatch() {
        QueuedCommandBatch batch;
        synchronized (commandQueueLock) {
            batch = commandQueue.pollFirst();
            if (batch == null) {
                commandQueueRunning = false;
                return;
            }
        }
        int end = Math.min(batch.commands().size(), batch.nextCommandIndex() + maxCommandsPerTick());
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (int index = batch.nextCommandIndex(); index < end; index++) {
            String command = batch.commands().get(index);
            Bukkit.dispatchCommand(console, command.startsWith("/") ? command.substring(1) : command);
        }
        // A large command list is continued on the following tick. It stays at the front of the
        // FIFO, so commands within a donation and the order between donations never change.
        if (end < batch.commands().size()) {
            synchronized (commandQueueLock) {
                commandQueue.addFirst(batch.withNextCommandIndex(end));
            }
            Bukkit.getScheduler().runTask(plugin, this::runNextQueuedCommandBatch);
            return;
        }
        long delayTicks = Math.max(1L, (batch.delayAfterMs() + 49L) / 50L);
        Bukkit.getScheduler().runTaskLater(plugin, this::runNextQueuedCommandBatch, delayTicks);
    }

    private int maxCommandsPerTick() {
        return Math.max(1, Math.min(100, plugin.getConfig().getInt("donation.max-commands-per-tick", 4)));
    }

    private static List<BindingRepository.Binding> selectPartyTargets(List<BindingRepository.Binding> targets, DonationRule rule) {
        if (rule == null || !"RANDOM".equals(rule.partyTargetMode())) return targets;
        List<BindingRepository.Binding> eligible = new ArrayList<>(targets.stream()
                .filter(target -> target.receives(DonationType.PARTY)).toList());
        if (eligible.size() <= rule.partyRandomCount()) return eligible;
        Collections.shuffle(eligible);
        return List.copyOf(eligible.subList(0, rule.partyRandomCount()));
    }

    private List<DonationRule> rules() throws IOException {
        return ruleSnapshot().donationRules();
    }

    private FollowRule followRule() throws IOException {
        return ruleSnapshot().followRule();
    }

    private List<EventRule> eventRules() throws IOException {
        return ruleSnapshot().eventRules();
    }

    private RuleSnapshot ruleSnapshot() throws IOException {
        RuleFileStamp stamp = ruleFileStamp();
        RuleSnapshot snapshot = cachedRuleSnapshot;
        if (snapshot != null && stamp.equals(cachedRuleStamp)) return snapshot;
        synchronized (ruleCacheLock) {
            stamp = ruleFileStamp();
            snapshot = cachedRuleSnapshot;
            if (snapshot != null && stamp.equals(cachedRuleStamp)) return snapshot;
            Map<String, Object> root = Json.object(Json.parse(Files.readString(rulesFile, StandardCharsets.UTF_8)));
            RuleSnapshot parsed = parseRuleSnapshot(root);
            cachedRuleStamp = stamp;
            cachedRuleSnapshot = parsed;
            return parsed;
        }
    }

    private RuleFileStamp ruleFileStamp() throws IOException {
        return new RuleFileStamp(Files.getLastModifiedTime(rulesFile).toMillis(), Files.size(rulesFile));
    }

    private void invalidateRuleCache() {
        synchronized (ruleCacheLock) {
            cachedRuleSnapshot = null;
            cachedRuleStamp = null;
        }
    }

    /** Builds all event-family views in one pass so a received event never re-reads donation.json. */
    private RuleSnapshot parseRuleSnapshot(Map<String, Object> root) {
        List<DonationRule> result = new ArrayList<>();
        FollowRule follow = null;
        List<EventRule> events = new ArrayList<>();
        boolean hasDedicatedSubscription = false;
        for (Object rawRule : Json.array(root.get("rules"))) {
            Map<String, Object> rule = Json.object(rawRule);
            Map<StreamingPlatform, Long> platformAmounts = platformAmounts(rule);
            String ruleType = ruleType(rule);
            List<String> commands = Json.array(rule.get("commands")).stream()
                    .map(Json::string).filter(command -> command != null && !command.isBlank()).toList();
            if (!"FOLLOW".equals(ruleType) && !"SUBSCRIPTION".equals(ruleType) && !platformAmounts.isEmpty() && !commands.isEmpty()) {
                result.add(new DonationRule(platformAmounts, commands, ruleDurationMs(rule), partyTargetMode(rule), partyRandomCount(rule), ruleType));
            }
            if ("FOLLOW".equals(ruleType) && follow == null && !commands.isEmpty()) {
                follow = new FollowRule(commands, ruleDurationMs(rule));
            }
            if ("SUBSCRIPTION".equals(ruleType) && !commands.isEmpty()) {
                events.add(new EventRule(DonationType.SUBSCRIPTION, commands));
                hasDedicatedSubscription = true;
            }
        }
        if (root.get("eventRules") instanceof List<?>) {
            for (Object rawRule : Json.array(root.get("eventRules"))) {
                Map<String, Object> rule = Json.object(rawRule);
                DonationType type = DonationType.parse(Json.string(rule.get("type")));
                if (type == DonationType.SUBSCRIPTION && hasDedicatedSubscription) continue;
                List<String> commands = Json.array(rule.get("commands")).stream()
                        .map(Json::string).filter(command -> command != null && !command.isBlank()).toList();
                if (type != null && !commands.isEmpty()) events.add(new EventRule(type, commands));
            }
        }
        return new RuleSnapshot(result, follow, events);
    }

    private static String sanitizeDonor(String donor) {
        if (donor == null || donor.isBlank()) {
            return "익명";
        }
        StringBuilder result = new StringBuilder();
        donor.codePoints().filter(codePoint -> Character.isLetterOrDigit(codePoint)
                        || Character.isWhitespace(codePoint) || codePoint == '_' || codePoint == '-' || codePoint == '.')
                .limit(64).forEach(result::appendCodePoint);
        return result.isEmpty() ? "익명" : result.toString().strip();
    }

    private static String defaultName(String name, long amount) {
        return name == null || name.isBlank() ? amount + "원 후원" : name;
    }
    private static String defaultFollowName(String name) { return name == null || name.isBlank() ? "팔로우" : name; }

    /** Donation rules are split by event family. Old files without a type remain ordinary donation rules. */
    private static String ruleType(Map<String, Object> rule) {
        String value = Json.string(rule.get("ruleType"));
        if (value == null || value.isBlank()) return Boolean.TRUE.equals(rule.get("follow")) ? "FOLLOW" : "DONATION";
        value = value.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (value) { case "DONATION", "PARTY", "SUBSCRIPTION", "FOLLOW" -> value; default -> "DONATION"; };
    }

    private static String partyTargetMode(Map<String, Object> rule) {
        String value = Json.string(rule.get("partyTargetMode"));
        if ("RANDOM".equalsIgnoreCase(value)) return "RANDOM";
        if ("SELF".equalsIgnoreCase(value)) return "SELF";
        return "ALL";
    }

    private static int partyRandomCount(Map<String, Object> rule) {
        Long value = Json.longValue(rule.get("partyRandomCount"));
        return value == null || value < 1 ? 1 : (int) Math.min(value, 100);
    }

    private static long ruleDurationMs(Map<String, Object> rule) {
        Long value = Json.longValue(rule.get("durationMs"));
        return value == null ? 0L : Math.max(0L, Math.min(value, 86_400_000L));
    }

    /** Accepts legacy amount rules by applying their price to every platform. */
    private static Map<StreamingPlatform, Long> platformAmounts(Map<String, Object> rule) {
        Map<StreamingPlatform, Long> result = new LinkedHashMap<>();
        Map<String, Object> rawAmounts = rule.get("platformAmounts") instanceof Map<?, ?>
                ? Json.object(rule.get("platformAmounts")) : Map.of();
        for (StreamingPlatform platform : StreamingPlatform.values()) {
            Long amount = Json.longValue(rawAmounts.get(platform.name()));
            if (amount != null && amount > 0) result.put(platform, amount);
        }
        if (!result.isEmpty()) return result;
        Long legacyAmount = Json.longValue(rule.get("amount"));
        if (legacyAmount != null && legacyAmount > 0) {
            for (StreamingPlatform platform : StreamingPlatform.values()) result.put(platform, legacyAmount);
        }
        return result;
    }

    private static Map<String, Object> platformAmountsForJson(Map<StreamingPlatform, Long> amounts) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (StreamingPlatform platform : StreamingPlatform.values()) result.put(platform.name(), amounts.get(platform));
        return result;
    }

    private record DonationRule(Map<StreamingPlatform, Long> platformAmounts, List<String> commands, long durationMs,
                                String partyTargetMode, int partyRandomCount, String ruleType) {
        private DonationRule { platformAmounts = Map.copyOf(platformAmounts); }
        boolean matches(StreamingPlatform platform, long amount, DonationType eventType) {
            String expected = eventType == DonationType.PARTY ? "PARTY" : "DONATION";
            return expected.equals(ruleType) && amount == platformAmounts.getOrDefault(platform, -1L);
        }
    }
    private record EventRule(DonationType type, List<String> commands) { }
    private record FollowRule(List<String> commands, long durationMs) { }
    private record RuleSnapshot(List<DonationRule> donationRules, FollowRule followRule, List<EventRule> eventRules) {
        private RuleSnapshot {
            donationRules = List.copyOf(donationRules);
            eventRules = List.copyOf(eventRules);
        }
    }
    private record RuleFileStamp(long modifiedMillis, long size) { }
    private record QueuedCommandBatch(List<String> commands, long delayAfterMs, int nextCommandIndex) {
        QueuedCommandBatch withNextCommandIndex(int nextCommandIndex) {
            return new QueuedCommandBatch(commands, delayAfterMs, nextCommandIndex);
        }
    }

    record DispatchResult(boolean accepted, int status, String message, List<String> players) {
        static DispatchResult accepted(List<String> players) { return accepted(players, "후원 명령을 실행 대기열에 넣었습니다."); }
        static DispatchResult accepted(List<String> players, String message) { return new DispatchResult(true, 202, message, players); }
        static DispatchResult notFound(String message) { return new DispatchResult(false, 404, message, List.of()); }
        static DispatchResult unmatched(String message) { return new DispatchResult(false, 422, message, List.of()); }
        static DispatchResult error(String message) { return new DispatchResult(false, 500, message, List.of()); }
    }
}
