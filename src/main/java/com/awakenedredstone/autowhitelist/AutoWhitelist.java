package com.awakenedredstone.autowhitelist;

import com.awakenedredstone.autowhitelist.commands.AutoWhitelistCommand;
import com.awakenedredstone.autowhitelist.config.ConfigData;
import com.awakenedredstone.autowhitelist.config.EntryData;
import com.awakenedredstone.autowhitelist.config.compat.LuckpermsEntry;
import com.awakenedredstone.autowhitelist.discord.Bot;
import com.awakenedredstone.autowhitelist.mixin.ServerConfigEntryMixin;
import com.awakenedredstone.autowhitelist.mixin.ServerLoginNetworkHandlerAccessor;
import com.awakenedredstone.autowhitelist.util.ExtendedGameProfile;
import com.awakenedredstone.autowhitelist.whitelist.ExtendedWhitelist;
import com.awakenedredstone.autowhitelist.whitelist.ExtendedWhitelistEntry;
import com.awakenedredstone.autowhitelist.whitelist.WhitelistCache;
import com.awakenedredstone.autowhitelist.whitelist.WhitelistCacheEntry;
import com.mojang.authlib.GameProfile;
import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.Whitelist;
import net.minecraft.server.WhitelistEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Unique;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Environment(EnvType.SERVER)
public class AutoWhitelist implements DedicatedServerModInitializer {
    public static final String MOD_ID = "autowhitelist";
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoWhitelist");
    public static final ConfigData CONFIG;
    public static final File WHITELIST_CACHE_FILE = new File("whitelist-cache.json");
    public static final WhitelistCache WHITELIST_CACHE = new WhitelistCache(WHITELIST_CACHE_FILE);
    @Deprecated
    public static MinecraftServer server;
    public static Map<String, EntryData> whitelistDataMap = new HashMap<>();

    static {
        CONFIG = new ConfigData();
    }

    public static void updateWhitelist() {
        PlayerManager playerManager = server.getPlayerManager();
        ExtendedWhitelist whitelist = (ExtendedWhitelist) playerManager.getWhitelist();

        Collection<? extends WhitelistEntry> entries = whitelist.getEntries();

        List<GameProfile> profiles = entries.stream().map(v -> (GameProfile) ((ServerConfigEntryMixin<?>) v).getKey()).toList();

        for (GameProfile profile : profiles) {
            GameProfile cachedProfile = server.getUserCache().getByUuid(profile.getId()).orElse(null);

            if (!profile.getName().equals(cachedProfile.getName()) && profile instanceof ExtendedGameProfile extendedProfile) {
                getCommandSource().sendFeedback(() -> Text.literal("Fixing bad entry from " + profile.getName()), true);
                whitelist.add(new ExtendedWhitelistEntry(new ExtendedGameProfile(cachedProfile.getId(), cachedProfile.getName(), extendedProfile.getRole(), extendedProfile.getDiscordId())));
            }
        }

        CONFIG.entries.forEach(EntryData::purgeInvalid);

        for (GameProfile profile : profiles) {
            if (profile instanceof ExtendedGameProfile extended) {
                EntryData entry = whitelistDataMap.get(extended.getRole());
                if (entry.shouldUpdate(extended)) entry.updateUser(extended);
            }
        }

        if (server.getPlayerManager().isWhitelistEnabled()) {
            server.kickNonWhitelistedPlayers(server.getCommandSource());
        }
    }

    public static void removePlayer(ExtendedGameProfile profile) {
        if (server.getPlayerManager().getWhitelist().isAllowed(profile)) {
            server.getPlayerManager().getWhitelist().remove(new ExtendedWhitelistEntry(profile));
            whitelistDataMap.get(profile.getRole()).removeUser(profile);
        }
    }

    public static ServerCommandSource getCommandSource() {
        ServerWorld serverWorld = server.getOverworld();
        return new ServerCommandSource(server, serverWorld == null ? Vec3d.ZERO : Vec3d.of(serverWorld.getSpawnPos()), Vec2f.ZERO,
            serverWorld, 4, "AutoWhitelist", Text.literal("AutoWhitelist"), server, null);
    }

    public static void loadWhitelistCache() {
        try {
            WHITELIST_CACHE.load();
        } catch (Exception exception) {
            LOGGER.warn("Failed to load whitelist cache: ", exception);
        }
    }

    @Override
    public void onInitializeServer() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Bot.stopBot(true), "JDA shutdown"));

        EntryData.register(new EntryData.Team(""));
        EntryData.register(new EntryData.Command("", ""));
        EntryData.register(new EntryData.Whitelist());
        if (FabricLoader.getInstance().isModLoaded("luckperms")) {
            EntryData.register(new LuckpermsEntry.Permission(""));
            EntryData.register(new LuckpermsEntry.Group(""));
        }

        if (!WHITELIST_CACHE_FILE.exists()) {
            try {
                WHITELIST_CACHE.save();
            } catch (IOException e) {
                LOGGER.warn("Failed to save whitelist cache: ", e);
            }
        }

        CONFIG.<List<EntryData>>registerListener("entries", newEntries -> {
            whitelistDataMap.clear();
            newEntries.forEach(entry -> entry.getRoleIds().forEach(id -> AutoWhitelist.whitelistDataMap.put(id, entry)));
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            AutoWhitelist.server = server;
            CONFIG.load();
            loadWhitelistCache();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> AutoWhitelistCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STOPPING.register((server -> Bot.stopBot(false)));
        ServerLifecycleEvents.SERVER_STARTED.register((server -> {
            new Bot().start();
            if (!server.isOnlineMode()) {
                LOGGER.warn("**** OFFLINE SERVER DETECTED!");
                LOGGER.warn("This mod does not offer support for offline servers!");
                LOGGER.warn("Using a whitelist on an offline server offers little to no protection");
                LOGGER.warn("AutoWhitelist may not work properly, fully or at all on an offline server");
            }
        }));

        Placeholders.register(new Identifier(MOD_ID, "prefix"),
            (ctx, arg) -> PlaceholderResult.value(Text.literal(AutoWhitelist.CONFIG.prefix))
        );

        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            if (!AutoWhitelist.CONFIG.enableWhitelistCache) return;
            if (Bot.jda == null) return;
            if (Bot.guild == null) return;
            ServerLoginNetworkHandlerAccessor accessor = (ServerLoginNetworkHandlerAccessor) handler;
            GameProfile profile = accessor.getProfile();
            if (handler.getConnectionInfo() == null) return;
            if (AutoWhitelist.getServer().getPlayerManager().checkCanJoin(accessor.getConnection().getAddress(), profile) == null) return;

            WhitelistCacheEntry cachedEntry = AutoWhitelist.WHITELIST_CACHE.get(profile);
            if (cachedEntry == null) return;
            String discordId = cachedEntry.getProfile().getDiscordId();
            Member member = Bot.guild.getMemberById(discordId);
            if (member == null) {
                AutoWhitelist.WHITELIST_CACHE.remove(profile);
                return;
            }
            List<Role> roles = member.getRoles();

            Optional<String> roleOptional = getTopRole(roles);
            if (roleOptional.isEmpty()) return;
            String role = roleOptional.get();

            EntryData entry = AutoWhitelist.whitelistDataMap.get(role);
            if (hasException(entry::assertSafe)) return;

            Whitelist whitelist = server.getPlayerManager().getWhitelist();
            ExtendedGameProfile extendedProfile = new ExtendedGameProfile(profile.getId(), profile.getName(), role, discordId);
            whitelist.add(new ExtendedWhitelistEntry(extendedProfile));
            entry.registerUser(profile);
        });
    }

    @Unique
    private Optional<String> getTopRole(List<Role> roles) {
        for (Role r : roles)
            if (AutoWhitelist.whitelistDataMap.containsKey(r.getId())) return Optional.of(r.getId());

        return Optional.empty();
    }

    @Unique
    private boolean hasException(Runnable task) {
        try {
            task.run();
            return false;
        } catch (Throwable e) {
            LOGGER.error("Failed to use whitelist cache due to a broken entry, please check your config file!", e);
            return true;
        }
    }

    public static MinecraftServer getServer() {
        return server;
    }
}
