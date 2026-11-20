/*
 * Copyright 2022-2026 Noah Ross
 *
 * This file is part of PerPlayerKit.
 *
 * PerPlayerKit is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * PerPlayerKit is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PerPlayerKit. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.noah.perplayerkit.util;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BroadcastManager {

    private static final int LINE_LENGTH = 60; // Length of the strikethrough line
    private static final String FIGURE_SPACE = " "; // A whitespace character of consistent width
    private static BroadcastManager instance;
    private final int broadcastDistance = 200;
    private final Plugin plugin;
    private final CooldownManager repairBroadcastCooldown = new CooldownManager(5);
    private final CooldownManager kitroomBroadcastCooldown = new CooldownManager(15);
    private volatile BukkitAudiences audience;
    private volatile FoliaScheduler.TaskHandle scheduledBroadcastTask;

    public BroadcastManager(Plugin plugin) {
        this.plugin = plugin;
        // On Paper/Folia, AudienceCompat uses the server's native Adventure API,
        // avoiding adventure-platform-bukkit initialization on Folia.
        this.audience = AudienceCompat.isAvailable() ? null : BukkitAudiences.create(plugin);
        instance = this;
    }

    public void shutdown() {
        FoliaScheduler.TaskHandle task = scheduledBroadcastTask;
        if (task != null) {
            task.cancel();
            scheduledBroadcastTask = null;
        }
        BukkitAudiences current = audience;
        if (current != null) {
            audience = null;
            current.close();
        }
    }

    public static BroadcastManager get() {
        if (instance == null) {
            throw new IllegalStateException("BroadcastManager has not been initialized yet!");
        }
        return instance;
    }

    public static Component generateBroadcastComponent(String message) {
        String strikeThroughLine = "<gray>" + " ".repeat(3) + "<st>" + FIGURE_SPACE.repeat(LINE_LENGTH) + "</st>";

        int messageLength = MiniMessage.miniMessage().stripTags(message).length();

        int padding = (LINE_LENGTH - messageLength) / 2;

        String formattedMessage = strikeThroughLine + "\n\n" + " ".repeat(3) + FIGURE_SPACE.repeat(Math.max(padding, 0)) + message + "\n\n" + strikeThroughLine;

        return TextUtil.parse(formattedMessage);
    }

    private boolean isKitLoadingMessage(MessageKey key) {
        return key == MessageKey.PLAYER_LOADED_PRIVATE_KIT ||
                key == MessageKey.PLAYER_LOADED_PUBLIC_KIT ||
                key == MessageKey.PLAYER_LOADED_ENDER_CHEST;
    }

    private void broadcastMessage(Player player, String message, String permission) {
        Location origin = player.getLocation();
        World world = origin.getWorld();
        if (world == null) {
            return;
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            message = PlaceholderAPI.setPlaceholders(player, message);
        }

        Component fullMessage = Lang.get().prefix().append(TextUtil.parse(message));
        double maxDistanceSquared = (double) broadcastDistance * broadcastDistance;
        for (Player broadcastPlayer : world.getPlayers()) {
            if (!FoliaScheduler.isFolia() || FoliaScheduler.isOwnedByCurrentRegion(broadcastPlayer)) {
                Location targetLoc = broadcastPlayer.getLocation();
                if (world.equals(targetLoc.getWorld())
                        && targetLoc.distanceSquared(origin) < maxDistanceSquared
                        && broadcastPlayer.hasPermission(permission)) {
                    sendComponentMessage(broadcastPlayer, fullMessage);
                }
            } else {
                FoliaScheduler.runEntity(plugin, broadcastPlayer, () -> {
                    if (!broadcastPlayer.isOnline()) {
                        return;
                    }
                    Location targetLoc = broadcastPlayer.getLocation();
                    if (world.equals(targetLoc.getWorld())
                            && targetLoc.distanceSquared(origin) < maxDistanceSquared
                            && broadcastPlayer.hasPermission(permission)) {
                        sendComponentMessage(broadcastPlayer, fullMessage);
                    }
                });
            }
        }
    }

    private void broadcastMessage(Player player, MessageKey key, CooldownManager cooldownManager) {
        broadcastMessage(player, key, cooldownManager, null);
    }

    private void broadcastMessage(Player player, MessageKey key, CooldownManager cooldownManager, String kitName) {

        if (!plugin.getConfig().getBoolean("broadcasts.enabled", true)) return;

        if (cooldownManager != null && cooldownManager.isOnCooldown(player)) {
            return;
        }

        String configBase = key.getConfigKey();
        String enabledPath = configBase + ".enabled";
        String permissionPath = configBase + ".permission";

        if (!plugin.getConfig().getBoolean(enabledPath, true)) {
            return;
        }

        String permission = plugin.getConfig().getString(permissionPath, "perplayerkit.kitnotify");

        String playerName;
        if (plugin.getConfig().getBoolean("broadcasts.use-display-name", false)) {
            playerName = player.getDisplayName();
        } else {
            playerName = player.getName();
        }

        String message = Lang.get().raw(key.getLangKey(), Map.of(
                "player", playerName,
                "kitname", kitName == null ? "" : kitName
        ));

        broadcastMessage(player, message, permission);

        if (cooldownManager != null) {
            cooldownManager.setCooldown(player);
        }
    }

    public void broadcastPlayerRepaired(Player player) {
        broadcastMessage(player, MessageKey.PLAYER_REPAIRED, repairBroadcastCooldown);
    }

    public void broadcastPlayerHealed(Player player) {
        broadcastMessage(player, MessageKey.PLAYER_HEALED, null);
    }

    public void broadcastPlayerOpenedKitRoom(Player player) {
        broadcastMessage(player, MessageKey.PLAYER_OPENED_KIT_ROOM, kitroomBroadcastCooldown);
    }

    public void broadcastPlayerLoadedPrivateKit(Player player, String kitName) {
        broadcastMessage(player, MessageKey.PLAYER_LOADED_PRIVATE_KIT, null, kitName);
    }

    public void broadcastPlayerLoadedPublicKit(Player player, String kitName) {
        broadcastMessage(player, MessageKey.PLAYER_LOADED_PUBLIC_KIT, null, kitName);
    }

    public void broadcastPlayerLoadedEnderChest(Player player) {
        broadcastMessage(player, MessageKey.PLAYER_LOADED_ENDER_CHEST, null);
    }

    public void broadcastPlayerCopiedKit(Player player) {
        broadcastMessage(player, MessageKey.PLAYER_COPIED_KIT, null);
    }

    public void broadcastPlayerCopiedEC(Player player) {
        broadcastMessage(player, MessageKey.PLAYER_COPIED_EC, null);
    }

    public void broadcastPlayerRegeared(Player player) {
        broadcastMessage(player, MessageKey.PLAYER_REGEARED, null);
    }

    public void startScheduledBroadcast() {
        List<Component> messages = new ArrayList<>();
        Lang.get().rawList("scheduled-broadcast.messages").forEach(message -> messages.add(generateBroadcastComponent(message)));

        int[] index = {0};

        if (plugin.getConfig().getBoolean("broadcasts.scheduled.enabled") && !messages.isEmpty()) {
            long periodTicks = Math.max(1, plugin.getConfig().getInt("broadcasts.scheduled.period-seconds", 90)) * 20L;
            scheduledBroadcastTask = FoliaScheduler.runGlobalTimer(plugin, () -> {
                Component current = messages.get(index[0]);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    sendComponentMessage(player, current);
                }
                index[0] = (index[0] + 1) % messages.size();
            }, 1L, periodTicks);
        }
    }

    /** Every component this class sends to a player goes through here. */
    public void sendComponentMessage(Player player, Component message) {
        // Paper's own chat API first: adventure-platform silently delivers
        // nothing on servers newer than it knows about. See AudienceCompat.
        if (AudienceCompat.send(player, message)) {
            return;
        }
        BukkitAudiences current = audience;
        if (current == null && plugin != null) {
            synchronized (this) {
                if (audience == null) {
                    audience = BukkitAudiences.create(plugin);
                }
                current = audience;
            }
        }
        if (current != null) {
            current.player(player).sendMessage(message);
        }
    }

    public enum MessageKey {
        PLAYER_REPAIRED("broadcasts.actions.player-repaired", "broadcast-messages.player-repaired"),
        PLAYER_HEALED("broadcasts.actions.player-healed", "broadcast-messages.player-healed"),
        PLAYER_OPENED_KIT_ROOM("broadcasts.actions.player-opened-kit-room", "broadcast-messages.player-opened-kit-room"),
        PLAYER_LOADED_PRIVATE_KIT("broadcasts.actions.player-loaded-private-kit", "broadcast-messages.player-loaded-private-kit"),
        PLAYER_LOADED_PUBLIC_KIT("broadcasts.actions.player-loaded-public-kit", "broadcast-messages.player-loaded-public-kit"),
        PLAYER_LOADED_ENDER_CHEST("broadcasts.actions.player-loaded-enderchest", "broadcast-messages.player-loaded-enderchest"),
        PLAYER_COPIED_KIT("broadcasts.actions.player-copied-kit", "broadcast-messages.player-copied-kit"),
        PLAYER_COPIED_EC("broadcasts.actions.player-copied-ec", "broadcast-messages.player-copied-ec"),
        PLAYER_REGEARED("broadcasts.actions.player-regeared", "broadcast-messages.player-regeared");

        private final String configKey;
        private final String langKey;

        MessageKey(String configKey, String langKey) {
            this.configKey = configKey;
            this.langKey = langKey;
        }

        public String getConfigKey() {
            return configKey;
        }

        public String getLangKey() {
            return langKey;
        }
    }
}
