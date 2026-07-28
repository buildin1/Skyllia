package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.enums.RemovalCause;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.cache.commands.CacheCommands;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.managers.skyblock.SkyblockManager;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.UpdateCheckerTask;
import fr.euphyllia.skyllia.utils.WorldUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public class JoinEvent implements Listener {

    private final InterneAPI api;
    private final Logger logger = LogManager.getLogger(JoinEvent.class);

    public JoinEvent(InterneAPI interneAPI) {
        this.api = interneAPI;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoinNotifUpdate(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        player.getScheduler().execute(api.getPlugin(),
                () -> UpdateCheckerTask.notifyIfUpdateAvailable(player),
                null, 40L);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        final String worldName = player.getWorld().getName();
        final int joinChunkX = player.getLocation().getBlockX() >> 4;
        final int joinChunkZ = player.getLocation().getBlockZ() >> 4;

        Bukkit.getAsyncScheduler().runNow(api.getPlugin(), _ -> {
            try {
                CacheCommands.refreshFor(playerId);

                final SkyblockManager skyblockManager = api.getSkyblockManager();
                final Island island = SkylliaAPI.getIslandByPlayerId(playerId);

                if (island != null) {
                    Players member = island.getMember(playerId);
                    if (member != null) {
                        String currentName = player.getName();
                        String last = member.getLastKnowName();
                        if (last == null || !last.equals(currentName)) {
                            member.setLastKnowName(currentName);
                            skyblockManager.updateMember(island, member);
                        }
                    }
                }

                // 登录安全检查：如果玩家当前位于不应访问的空岛上，强制传送
                if (WorldUtils.isWorldSkyblock(worldName)) {
                    Island currentIsland = SkylliaAPI.getIslandByChunk(joinChunkX, joinChunkZ);
                    if (currentIsland != null && !isPlayerAllowedOnIsland(player, currentIsland)) {
                        teleportToSafeLocation(player, island);
                        return;
                    }
                }

                // 核心改动：只要玩家不在空岛世界，且有岛屿或无岛屿都强制传送到正确位置
                if (!WorldUtils.isWorldSkyblock(worldName)) {
                    System.out.println("[Skyllia-加入] 玩家 " + player.getName() + " 在非空岛世界 " + worldName + "，强制传送");
                    if (island != null) {
                        teleportToIslandHome(player, island);
                    } else {
                        teleportToSkyWorldSpawn(player);
                    }
                    return;
                }

                // 在空岛世界但没有岛屿 -> 传送到出生点
                if (island == null) {
                    teleportToSkyWorldSpawn(player);
                    return;
                }

                checkAndClearPlayerStuffOnJoin(player);

            } catch (Exception e) {
                logger.error("Error during JoinEvent async task for {}", playerId, e);
            }
        });
    }


    /**
     * 判断玩家是否有权访问当前所在的岛屿。
     * 被拉黑、岛屿不开放且无 bypass、或缺少 player.teleport 权限 → 无权限。
     */
    private boolean isPlayerAllowedOnIsland(Player player, Island currentIsland) {
        UUID playerId = player.getUniqueId();

        // 岛主永远允许
        if (currentIsland.getOwner() != null && currentIsland.getOwner().getMojangId().equals(playerId)) {
            return true;
        }

        // 被拉黑 → 不允许
        Players member = currentIsland.getMember(playerId);
        if (member != null && member.getRoleType() == RoleType.BAN && !player.isOp()) {
            ConfigLoader.language.sendMessage(player, "island.visit.banned");
            return false;
        }

        // 成员/访客：检查 player.teleport 权限
        PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
        PermissionId teleportPid = registry.getIfPresent(new NamespacedKey("skyllia", "player.teleport"));
        boolean hasTeleport = teleportPid != null && SkylliaAPI.getPermissionsManager().hasPermission(
                player, currentIsland, teleportPid, null, ConfigLoader.general.getDebugSettings().permission());
        if (!player.isOp() && !hasTeleport) {
            ConfigLoader.language.sendMessage(player, "island.player.permission-denied");
            return false;
        }

        // 私有岛屿额外检查 bypass
        if (currentIsland.isPrivateIsland()) {
            PermissionId bypassPid = registry.getIfPresent(new NamespacedKey("skyllia", "command.island.visit.bypass"));
            boolean bypass = bypassPid != null && SkylliaAPI.getPermissionsManager().hasPermission(
                    player, currentIsland, bypassPid, null, ConfigLoader.general.getDebugSettings().permission());
            if(player.isOp() || bypass) {
                return true;
            }
            ConfigLoader.language.sendMessage(player, "island.visit.island-closed");
            return false;
        }

        return true;
    }

    private void teleportToIslandHome(Player player, Island island) {
        WarpIsland homeWarp = island.getWarpByName("home");
        World islandWorld = WorldUtils.getWorldConfigs().getFirst().getWorld();

        Location loc;
        if (island.hasCustomSpawn()) {
            loc = island.getSpawnLocation(islandWorld);
        } else if (homeWarp != null && homeWarp.location() != null) {
            loc = homeWarp.location().clone();
        } else {
            loc = island.getSpawnLocation(islandWorld);
        }
        loc.add(0.5, 0.1, 0.5);

        System.out.println("[Skyllia-加入] 传送玩家 " + player.getName() + " 到岛屿: " + loc);
        // NMS 层面传送（绕过 Bukkit 事件）
        SkylliaAPI.getPlayerNMS().teleport(player, loc);
    }

    private void teleportToSkyWorldSpawn(Player player) {
        World islandWorld = WorldUtils.getWorldConfigs().getFirst().getWorld();
        if (islandWorld == null) return;
        Location spawn = new Location(islandWorld, 256.5, 64, 256.5);
        System.out.println("[Skyllia-加入] 传送新玩家 " + player.getName() + " 到空岛出生点: " + spawn);
        // NMS 层面传送（绕过 Bukkit 事件）
        SkylliaAPI.getPlayerNMS().teleport(player, spawn);
    }

    /**
     * 将玩家传送到安全位置：若有空岛则传回自己空岛，否则传送到空岛主世界。
     */
    private void teleportToSafeLocation(Player player, Island ownIsland) {
        if (ownIsland != null) {
            player.getScheduler().run(api.getPlugin(), _ -> {
                if (!player.isOnline()) return;
                World world = player.getWorld();
                RegionCoordinate region = ownIsland.getRegionCoordinate();
                Location center = RegionHelper.getCenterRegion(world, region.x(), region.z());
                center.setY(64);
                SkylliaAPI.getPlayerNMS().teleport(player, center);
            }, null);
        } else {
            player.getScheduler().run(api.getPlugin(), _ -> {
                if (!player.isOnline()) return;
                World world = WorldUtils.getWorldConfigs().getFirst().getWorld();
                if (world != null) {
                    Location spawn = new Location(world, 256.5, 64, 256.5);
                    SkylliaAPI.getPlayerNMS().teleport(player, spawn);
                }
            }, null);
        }
    }

    private void checkAndClearPlayerStuffOnJoin(Player player) {
        UUID uuid = player.getUniqueId();

        for (RemovalCause cause : RemovalCause.values()) {
            boolean deleted = api.getSkyblockManager().deleteClearMember(uuid, cause);
            if (!deleted) continue;

            player.getScheduler().execute(api.getPlugin(), () -> {
                clearPlayerData(player, cause);
                player.setGameMode(GameMode.SURVIVAL);
            }, null, 1L);
        }
    }


    private void clearPlayerData(Player player, RemovalCause cause) {
        switch (cause) {
            case KICKED -> {
                if (ConfigLoader.playerManager.isClearInventoryWhenKicked()) player.getInventory().clear();
                if (ConfigLoader.playerManager.isClearEnderChestWhenKicked()) player.getEnderChest().clear();
                if (ConfigLoader.playerManager.isResetExperienceWhenKicked()) {
                    player.setLevel(0);
                    player.setExp(0);
                    player.setTotalExperience(0);
                    player.sendExperienceChange(0, 0);
                }
            }
            case ISLAND_DELETED -> {
                if (ConfigLoader.playerManager.isClearInventoryWhenDelete()) player.getInventory().clear();
                if (ConfigLoader.playerManager.isClearEnderChestWhenDelete()) player.getEnderChest().clear();
                if (ConfigLoader.playerManager.isResetExperienceWhenDelete()) {
                    player.setLevel(0);
                    player.setExp(0);
                    player.setTotalExperience(0);
                    player.sendExperienceChange(0, 0);
                }
            }
            case LEAVE -> {
                if (ConfigLoader.playerManager.isClearInventoryWhenLeave()) player.getInventory().clear();
                if (ConfigLoader.playerManager.isClearEnderChestWhenLeave()) player.getEnderChest().clear();
                if (ConfigLoader.playerManager.isResetExperienceWhenLeave()) {
                    player.setLevel(0);
                    player.setExp(0);
                    player.setTotalExperience(0);
                    player.sendExperienceChange(0, 0);
                }
            }
        }
    }
}
