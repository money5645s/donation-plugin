package kr.unofficialdonation;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

public final class ChzzkDonationBridgePlugin extends JavaPlugin {
    private ApiServer apiServer;
    private ChzzkLiveClient chzzkLiveClient;
    private ChzzkOpenApiClient chzzkOpenApiClient;
    private MultiPlatformLiveClient multiPlatformLiveClient;
    private CimeOfficialApiClient cimeOfficialApiClient;
    private EventFeed eventFeed;
    private DonationDispatcher dispatcher;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureConfigurationDefaults();
        saveBundledDonationRules();

        BindingRepository bindings = new BindingRepository(migrateSteamerFile());
        dispatcher = new DonationDispatcher(this, bindings,
                getDataFolder().toPath().resolve("donation.json"));
        eventFeed = new EventFeed(this, getConfig().getInt("dashboard.event-history-limit", 5000));
        chzzkLiveClient = new ChzzkLiveClient(this, dispatcher, eventFeed);
        chzzkOpenApiClient = new ChzzkOpenApiClient(this, dispatcher, eventFeed);
        multiPlatformLiveClient = new MultiPlatformLiveClient(this, bindings, dispatcher, eventFeed);
        cimeOfficialApiClient = new CimeOfficialApiClient(this, bindings, dispatcher, eventFeed, multiPlatformLiveClient);
        apiServer = new ApiServer(this, bindings, dispatcher, chzzkLiveClient, chzzkOpenApiClient, multiPlatformLiveClient,
                cimeOfficialApiClient, eventFeed);
        ApiCommand apiCommand = new ApiCommand(this, bindings, dispatcher, chzzkLiveClient, multiPlatformLiveClient,
                cimeOfficialApiClient, apiServer);
        getCommand("api").setExecutor(apiCommand);
        getCommand("api").setTabCompleter(apiCommand);
        // Vanilla/Paper can allow operators to bypass a whitelist. The dashboard's whitelist is
        // intended as an access gate, so enforce it at login for every player, including OPs.
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void onPlayerLogin(PlayerLoginEvent event) {
                if (getServer().hasWhitelist() && !event.getPlayer().isWhitelisted()) {
                    event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST,
                            "이 서버의 화이트리스트에 등록되어 있지 않습니다.");
                }
            }
        }, this);
        try {
            apiServer.start();
            chzzkLiveClient.start(bindings.channelIds(StreamingPlatform.CHZZK));
            chzzkOpenApiClient.start(bindings.channelIds(StreamingPlatform.CHZZK));
            cimeOfficialApiClient.start();
            multiPlatformLiveClient.start();
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "후원 API를 시작하지 못해 플러그인을 비활성화합니다.", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (apiServer != null) {
            apiServer.stop();
        }
        if (chzzkLiveClient != null) {
            chzzkLiveClient.stop();
        }
        if (chzzkOpenApiClient != null) {
            chzzkOpenApiClient.stop();
        }
        if (multiPlatformLiveClient != null) {
            multiPlatformLiveClient.stop();
        }
        if (cimeOfficialApiClient != null) {
            cimeOfficialApiClient.stop();
        }
        if (eventFeed != null) {
            eventFeed.close();
        }
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    private void saveBundledDonationRules() {
        Path rulesPath = getDataFolder().toPath().resolve("donation.json");
        if (Files.notExists(rulesPath)) {
            saveResource("donation.json", false);
        }
    }

    /** Adds newly introduced settings to existing installations on the next plugin enable/reload. */
    private void ensureConfigurationDefaults() {
        boolean changed = false;
        if (!getConfig().contains("cime.follow.enabled")) {
            getConfig().set("cime.follow.enabled", true);
            changed = true;
        }
        if (!getConfig().contains("cime.follow.poll-interval-seconds")) {
            getConfig().set("cime.follow.poll-interval-seconds", 60);
            changed = true;
        }
        if (!getConfig().contains("donation.max-commands-per-tick")) {
            getConfig().set("donation.max-commands-per-tick", 4);
            changed = true;
        }
        if (!getConfig().contains("api-worker-threads")) {
            getConfig().set("api-worker-threads", 4);
            changed = true;
        }
        if (changed) {
            saveConfig();
            getLogger().info("새 성능 및 씨미 팔로우 기본 설정을 config.yml에 추가했습니다.");
        }
    }

    private Path migrateSteamerFile() {
        Path steamer = getDataFolder().toPath().resolve("steamer.json");
        Path legacy = getDataFolder().toPath().resolve("bindings.json");
        if (Files.notExists(steamer) && Files.exists(legacy)) {
            try {
                Files.move(legacy, steamer, StandardCopyOption.REPLACE_EXISTING);
                getLogger().info("Renamed bindings.json to steamer.json.");
            } catch (IOException exception) {
                getLogger().warning("bindings.json의 이름을 변경하지 못했습니다: " + exception.getMessage());
            }
        }
        return steamer;
    }
}
