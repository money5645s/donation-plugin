package kr.unofficialdonation;

import io.github.bbobbogi.stream4j.common.Donation;
import io.github.bbobbogi.stream4j.common.DonationPlatform;
import io.github.bbobbogi.stream4j.common.StreamChat;
import io.github.bbobbogi.stream4j.common.StreamChatBuilder;
import io.github.bbobbogi.stream4j.common.StreamChatEventListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uses stream4j's public protocol clients for CI.ME and SOOP.  Each binding has its
 * own listener so an incoming donation can never be delivered to another player's ID.
 */
final class MultiPlatformLiveClient {
    private final JavaPlugin plugin;
    private final BindingRepository bindings;
    private final DonationDispatcher dispatcher;
    private final EventFeed eventFeed;
    private final Map<String, StreamChat> clients = new ConcurrentHashMap<>();
    /** CI.ME official sessions own these channels; never run both receivers for one donation. */
    private final Set<String> officialCimeChannels = ConcurrentHashMap.newKeySet();
    // stream4j also deduplicates some protocols, but keep a guard here so reconnect replays
    // from either CI.ME or SOOP cannot execute Minecraft commands twice.
    private final RecentEventCache recentDonations = new RecentEventCache(10_000, 8_000L);

    MultiPlatformLiveClient(JavaPlugin plugin, BindingRepository bindings, DonationDispatcher dispatcher, EventFeed eventFeed) {
        this.plugin = plugin;
        this.bindings = bindings;
        this.dispatcher = dispatcher;
        this.eventFeed = eventFeed;
    }

    void start() {
        bindings.all().stream().filter(this::supported).forEach(this::connect);
    }

    void connect(BindingRepository.Binding binding) {
        if (!supported(binding)) return;
        String key = key(binding.platform(), binding.channelId());
        clients.computeIfAbsent(key, ignored -> create(binding));
    }

    void disconnect(StreamingPlatform platform, String channelId) {
        StreamChat client = clients.remove(key(platform, channelId));
        if (client == null) return;
        try { client.closeAll(); } catch (Exception ignored) { }
    }

    /** Called only after the CI.ME official DONATION subscription has succeeded. */
    void setCimeOfficialActive(String channelId, boolean active) {
        String cimeKey = key(StreamingPlatform.CIME, channelId);
        if (active) {
            officialCimeChannels.add(cimeKey);
            disconnect(StreamingPlatform.CIME, channelId);
            return;
        }
        if (!officialCimeChannels.remove(cimeKey)) return;
        bindings.findChannel(StreamingPlatform.CIME, channelId).stream().findFirst().ifPresent(this::connect);
    }

    void stop() {
        clients.values().forEach(client -> {
            try { client.closeAll(); } catch (Exception ignored) { }
        });
        clients.clear();
    }

    private StreamChat create(BindingRepository.Binding binding) {
        DonationPlatform platform = binding.platform() == StreamingPlatform.CIME ? DonationPlatform.CIME : DonationPlatform.SOOP;
        StreamChat client = new StreamChatBuilder()
                .add(binding.channelId(), platform)
                .withAutoReconnect(true)
                .withListener(new StreamChatEventListener() {
                    @Override
                    public void onChat(DonationPlatform ignored, String channelId, String nickname, String message) {
                        if (!dispatcher.hasOnlineReceiver(binding.platform(), binding.channelId())) return;
                        eventFeed.publish("chat", binding.channelId(), Map.of(
                                "nickname", safe(nickname, "익명"), "message", safe(message, ""),
                                "platform", binding.platform().name()));
                    }

                    @Override
                    public void onDonation(Donation donation) {
                        // SOOP reports star balloons and CIME reports beams here.  Do not convert to KRW:
                        // donation.json matches the native unit configured by the server administrator.
                        long amount = donation.amount();
                        if (amount <= 0) return;
                        DonationType type = donationType(donation.type() == null ? "" : donation.type().name());
                        String nickname = safe(donation.nickname(), "익명");
                        String fallbackId = binding.platform().name() + ':' + binding.channelId() + ':' + type.name()
                                + ':' + amount + ':' + nickname + ':' + safe(donation.message(), "");
                        if (!recentDonations.remember(fallbackId)) {
                            plugin.getLogger().fine("[" + binding.platform().name() + "] 중복 후원 이벤트를 건너뛰었습니다.");
                            return;
                        }
                        dispatcher.submitReceive(binding.platform(), binding.channelId(), amount, nickname, type,
                                result -> {
                                    if (!dispatcher.hasOnlineReceiver(binding.platform(), binding.channelId())) return;
                                    eventFeed.publish(type == DonationType.PARTY ? "party" : "donation", binding.channelId(), Map.of(
                                            "nickname", nickname, "amount", amount,
                                            "donationType", type.name(), "platform", binding.platform().name(), "result", result.message()));
                                });
                    }
                })
                .build();
        Thread worker = new Thread(() -> {
            try {
                client.connectAll();
                plugin.getLogger().info("[" + binding.platform().name() + "] " + binding.channelId() + " 실시간 수신기를 연결했습니다.");
            } catch (Exception exception) {
                plugin.getLogger().warning("[" + binding.platform().name() + "] " + binding.channelId() + " 연결에 실패했습니다: " + exception.getMessage());
                clients.remove(key(binding.platform(), binding.channelId()), client);
            }
        }, "Donation-" + binding.platform().name() + "-" + binding.channelId());
        worker.setDaemon(true);
        worker.start();
        return client;
    }

    private boolean supported(BindingRepository.Binding binding) {
        if (binding.platform() == StreamingPlatform.CIME) {
            return !officialCimeChannels.contains(key(StreamingPlatform.CIME, binding.channelId()));
        }
        return binding.platform() == StreamingPlatform.SOOP;
    }

    private static String key(StreamingPlatform platform, String channelId) { return platform.name() + ':' + channelId; }
    private static String safe(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private static DonationType donationType(String raw) {
        String value = raw.toUpperCase(java.util.Locale.ROOT);
        if (value.contains("VIDEO")) return DonationType.VIDEO;
        if (value.contains("MISSION")) return DonationType.MISSION;
        if (value.contains("SUBSCRIPTION") || value.contains("SUBSCRIBE")) return DonationType.SUBSCRIPTION;
        if (value.contains("PARTY")) return DonationType.PARTY;
        return DonationType.CHAT;
    }
}
