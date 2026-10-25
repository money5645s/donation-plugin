package kr.unofficialdonation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Persists the player-to-Chzzk-channel mapping made through /api/login. */
final class BindingRepository {
    private final Path file;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Map<String, PartyGroup> partyGroups = new LinkedHashMap<>();
    /** Dashboard entries for players who have not joined this server yet. */
    private final Map<String, String> manualPlayers = new LinkedHashMap<>();
    private final Set<String> hiddenPlayers = new java.util.LinkedHashSet<>();

    BindingRepository(Path file) {
        this.file = file;
        load();
    }

    synchronized void bind(String minecraftName, String channelId) throws IOException {
        bind(minecraftName, StreamingPlatform.CHZZK, channelId);
    }

    synchronized void bind(String minecraftName, StreamingPlatform platform, String channelId) throws IOException {
        bind(minecraftName, platform, channelId, null);
    }

    synchronized void bind(String minecraftName, StreamingPlatform platform, String channelId, String channelName) throws IOException {
        Binding old = bindings.get(key(minecraftName));
        Set<DonationType> types = old == null ? Set.of(DonationType.CHAT, DonationType.VIDEO) : old.enabledDonationTypes();
        String resolvedName = channelName == null || channelName.isBlank() ? old == null ? "" : old.channelName() : channelName.strip();
        bindings.put(key(minecraftName), new Binding(minecraftName, platform, channelId, types, resolvedName));
        manualPlayers.remove(key(minecraftName));
        hiddenPlayers.remove(key(minecraftName));
        save();
    }

    synchronized boolean setDonationType(String minecraftName, DonationType type, boolean enabled) throws IOException {
        Binding old = bindings.get(key(minecraftName));
        if (old == null) return false;
        Set<DonationType> types = new java.util.LinkedHashSet<>(old.enabledDonationTypes());
        if (enabled) types.add(type); else types.remove(type);
        bindings.put(key(minecraftName), new Binding(old.minecraftName(), old.platform(), old.channelId(), Set.copyOf(types), old.channelName()));
        save();
        return true;
    }

    synchronized boolean unbind(String minecraftName) throws IOException {
        boolean removed = bindings.remove(key(minecraftName)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    /** Removes dashboard-managed data for a player and hides an old offline profile from the dashboard. */
    synchronized void forgetPlayer(String minecraftName) throws IOException {
        String playerKey = key(minecraftName);
        bindings.remove(playerKey);
        manualPlayers.remove(playerKey);
        for (Map.Entry<String, PartyGroup> entry : new ArrayList<>(partyGroups.entrySet())) {
            PartyGroup group = entry.getValue();
            List<String> members = group.members().stream().filter(member -> !key(member).equals(playerKey)).toList();
            if (members.size() != group.members().size()) {
                partyGroups.put(entry.getKey(), new PartyGroup(group.name(), members));
            }
        }
        hiddenPlayers.add(playerKey);
        save();
    }

    synchronized boolean isHidden(String minecraftName) {
        return hiddenPlayers.contains(key(minecraftName));
    }

    synchronized Binding findPlayer(String minecraftName) {
        return bindings.get(key(minecraftName));
    }

    synchronized List<Binding> findChannel(String channelId) {
        return findChannel(StreamingPlatform.CHZZK, channelId);
    }

    synchronized List<Binding> findChannel(StreamingPlatform platform, String channelId) {
        return bindings.values().stream()
                .filter(binding -> binding.platform() == platform && binding.channelId().equals(channelId))
                .toList();
    }

    synchronized Set<String> channelIds() {
        return channelIds(StreamingPlatform.CHZZK);
    }

    synchronized Set<String> channelIds(StreamingPlatform platform) {
        return bindings.values().stream().filter(binding -> binding.platform() == platform)
                .map(Binding::channelId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    synchronized List<Binding> all() {
        return List.copyOf(bindings.values());
    }

    synchronized void addManualPlayer(String minecraftName) throws IOException {
        String playerKey = key(minecraftName);
        if (bindings.containsKey(playerKey) || manualPlayers.containsKey(playerKey)) {
            throw new IllegalArgumentException("이미 목록에 있는 플레이어입니다.");
        }
        manualPlayers.put(playerKey, minecraftName);
        hiddenPlayers.remove(playerKey);
        save();
    }

    synchronized List<String> manualPlayers() {
        return List.copyOf(manualPlayers.values());
    }

    /** Returns the linked players that should receive a party donation for this channel.
     * A player added to a party group belongs to that group automatically. */
    synchronized List<Binding> partyTargets(String channelId) {
        return partyTargets(StreamingPlatform.CHZZK, channelId);
    }

    synchronized List<Binding> partyTargets(StreamingPlatform platform, String channelId) {
        List<Binding> sources = findChannel(platform, channelId);
        if (sources.isEmpty()) return List.of();
        Map<String, Binding> targets = new LinkedHashMap<>();
        for (Binding source : sources) {
            PartyGroup group = partyGroupFor(source.minecraftName());
            if (group == null) {
                targets.putIfAbsent(key(source.minecraftName()), source);
                continue;
            }
            for (String member : group.members()) {
                Binding target = bindings.get(key(member));
                if (target != null) targets.putIfAbsent(key(target.minecraftName()), target);
            }
        }
        return List.copyOf(targets.values());
    }

    synchronized List<PartyGroup> partyGroups() {
        return List.copyOf(partyGroups.values());
    }

    synchronized void createPartyGroup(String name) throws IOException {
        if (partyGroups.containsKey(key(name))) throw new IllegalArgumentException("같은 이름의 파티후원 그룹이 이미 있습니다.");
        if (partyGroups.size() >= 50) throw new IllegalArgumentException("파티후원 그룹은 최대 50개까지 만들 수 있습니다.");
        partyGroups.put(key(name), new PartyGroup(name, List.of()));
        save();
    }

    synchronized boolean deletePartyGroup(String name) throws IOException {
        boolean removed = partyGroups.remove(key(name)) != null;
        if (removed) save();
        return removed;
    }

    synchronized void addPartyMember(String groupName, String minecraftName) throws IOException {
        PartyGroup group = partyGroups.get(key(groupName));
        if (group == null) throw new IllegalArgumentException("파티후원 그룹을 찾을 수 없습니다.");
        List<String> members = new ArrayList<>(group.members());
        for (Map.Entry<String, PartyGroup> entry : new ArrayList<>(partyGroups.entrySet())) {
            PartyGroup existing = entry.getValue();
            List<String> withoutPlayer = existing.members().stream()
                    .filter(member -> !key(member).equals(key(minecraftName))).toList();
            if (withoutPlayer.size() != existing.members().size()) {
                partyGroups.put(entry.getKey(), new PartyGroup(existing.name(), withoutPlayer));
            }
        }
        if (members.stream().noneMatch(member -> key(member).equals(key(minecraftName)))) members.add(minecraftName);
        partyGroups.put(key(groupName), new PartyGroup(group.name(), List.copyOf(members)));
        save();
    }

    synchronized boolean removePartyMember(String groupName, String minecraftName) throws IOException {
        PartyGroup group = partyGroups.get(key(groupName));
        if (group == null) return false;
        List<String> members = group.members().stream().filter(member -> !key(member).equals(key(minecraftName))).toList();
        if (members.size() == group.members().size()) return false;
        partyGroups.put(key(groupName), new PartyGroup(group.name(), members));
        save();
        return true;
    }

    private void load() {
        if (Files.notExists(file)) {
            return;
        }
        try {
            Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, Object> rootObject = Json.object(root);
            Object streamers = rootObject.containsKey("streamers") ? rootObject.get("streamers") : rootObject.get("bindings");
            List<Object> entries = Json.array(streamers);
            for (Object entry : entries) {
                Map<String, Object> object = Json.object(entry);
                String minecraftName = Json.string(object.get("minecraftName"));
                String channelId = Json.string(object.get("channelId"));
                Set<DonationType> types = parseTypes(object.get("enabledDonationTypes"));
                if (minecraftName != null && channelId != null) {
                    StreamingPlatform platform = StreamingPlatform.parse(Json.string(object.get("platform")));
                    bindings.put(key(minecraftName), new Binding(minecraftName, platform == null ? StreamingPlatform.CHZZK : platform, channelId, types, Json.string(object.get("channelName")) == null ? "" : Json.string(object.get("channelName"))));
                }
            }
            for (Object entry : Json.array(rootObject.get("partyGroups"))) {
                Map<String, Object> object = Json.object(entry);
                String name = Json.string(object.get("name"));
                if (name == null || name.isBlank()) continue;
                List<String> members = Json.array(object.get("members")).stream().map(Json::string)
                        .filter(member -> member != null && !member.isBlank()).distinct().toList();
                partyGroups.put(key(name), new PartyGroup(name, members));
            }
            for (Object entry : Json.array(rootObject.get("manualPlayers"))) {
                String minecraftName = Json.string(entry);
                if (minecraftName != null && !minecraftName.isBlank() && !bindings.containsKey(key(minecraftName))) {
                    manualPlayers.put(key(minecraftName), minecraftName);
                }
            }
        } catch (Exception exception) {
            Logger.getLogger(BindingRepository.class.getName())
                    .warning("bindings.json을 불러오지 못해 연결 정보 없이 시작합니다: " + exception.getMessage());
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        List<Object> entries = new ArrayList<>();
        for (Binding binding : bindings.values()) {
            entries.add(Map.of("minecraftName", binding.minecraftName(), "platform", binding.platform().name(), "channelId", binding.channelId(), "channelName", binding.channelName(),
                    "enabledDonationTypes", binding.enabledDonationTypes().stream().map(Enum::name).sorted().toList()));
        }
        List<Object> groups = partyGroups.values().stream().map(group -> (Object) Map.of(
                "name", group.name(), "members", group.members())).toList();
        String content = Json.write(Map.of("streamers", entries, "partyGroups", groups,
                "manualPlayers", new ArrayList<>(manualPlayers.values())));
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String key(String minecraftName) {
        return minecraftName.toLowerCase(Locale.ROOT);
    }

    private static Set<DonationType> parseTypes(Object rawTypes) {
        if (!(rawTypes instanceof List<?>)) return Set.of(DonationType.CHAT, DonationType.VIDEO);
        Set<DonationType> result = new java.util.LinkedHashSet<>();
        for (Object raw : Json.array(rawTypes)) {
            String name = Json.string(raw);
            if (name != null) {
                try { result.add(DonationType.valueOf(name)); } catch (IllegalArgumentException ignored) { }
            }
        }
        return Set.copyOf(result);
    }

    private PartyGroup partyGroupFor(String minecraftName) {
        String playerKey = key(minecraftName);
        return partyGroups.values().stream()
                .filter(group -> group.members().stream().anyMatch(member -> key(member).equals(playerKey)))
                .findFirst().orElse(null);
    }

    record Binding(String minecraftName, StreamingPlatform platform, String channelId, Set<DonationType> enabledDonationTypes, String channelName) {
        Binding(String minecraftName, StreamingPlatform platform, String channelId, Set<DonationType> enabledDonationTypes) {
            this(minecraftName, platform, channelId, enabledDonationTypes, "");
        }
        boolean receives(DonationType type) { return enabledDonationTypes.contains(type); }
    }

    record PartyGroup(String name, List<String> members) { }
}
