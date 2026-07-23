package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerRegionChangeListener implements Listener {

    private final Map<UUID, Position> lastRegion = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBlockTime = new ConcurrentHashMap<>();

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        // 忽略同一方块坐标内的移动
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) return;

        Player player = event.getPlayer();
        int chunkX = to.getBlockX() >> 4;
        int chunkZ = to.getBlockZ() >> 4;

        Position currentRegion = RegionHelper.getRegionFromChunk(chunkX, chunkZ);

        Position previousRegion = lastRegion.get(player.getUniqueId());

        // 首次记录，只保存不提示
        if (previousRegion == null) {
            lastRegion.put(player.getUniqueId(), currentRegion);
            return;
        }

        // 区域未改变
        if (previousRegion.equals(currentRegion)) return;

        // ── 进入新区域前的访问权限检查（主城 [0,0] 除外）──
        if ((currentRegion.x() != 0 || currentRegion.z() != 0) && !player.isOp()) {
            Island targetIsland = SkylliaAPI.getIslandByRegion(currentRegion);
            if (targetIsland != null) {
                UUID playerId = player.getUniqueId();
                Players owner = targetIsland.getOwner();
                boolean isOwner = owner != null && owner.getMojangId().equals(playerId);
                boolean isMember = isPrivilegedMember(targetIsland, playerId);

                if (!isOwner && !isMember) {
                    PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();

                    // 检查 bypass 权限：Bukkit 节点 + 内部权限 command.island.visit.bypass
                    PermissionId bypassPid = registry.getIfPresent(new NamespacedKey("skyllia", "command.island.visit.bypass"));
                    boolean bypass = (bypassPid != null && SkylliaAPI.getPermissionsManager().hasPermission(
                            player, targetIsland, bypassPid, null, ConfigLoader.general.getDebugSettings().permission()));
                    if (targetIsland.isPrivateIsland() && !bypass) {
                        cancelAndNotify(player, event, owner, "&f[&7空岛&f] &c你不能进入 &e%s &c的空岛，该岛屿不对外开放。");
                        return;
                    }

                    // 非私有岛屿，检查 player.teleport 内部权限
                    if (!bypass) {
                        PermissionId teleportPid = registry.getIfPresent(new NamespacedKey("skyllia", "player.teleport"));
                        boolean hasTeleport = teleportPid != null && SkylliaAPI.getPermissionsManager().hasPermission(
                                player, targetIsland, teleportPid, null, ConfigLoader.general.getDebugSettings().permission());
                        if (!hasTeleport) {
                            cancelAndNotify(player, event, owner, "&f[&7空岛&f] &c你不能进入 &e%s &c的空岛，你没有权限访问空岛。");
                            return;
                        }
                    }
                }
            }
        }

        // ── 离开旧区域（原有逻辑）──
        if (previousRegion.x() == 0 && previousRegion.z() == 0) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&f[&7空岛&f] &b你已离开空岛主城~"));
        } else {
            Island oldIsland = SkylliaAPI.getIslandByRegion(previousRegion);
            if (oldIsland != null) {
                UUID playerId = player.getUniqueId();
                boolean isOwnIsland = isOwnIsland(oldIsland, playerId);
                if (isOwnIsland) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&f[&7空岛&f] &c你已离开自己的空岛"));
                } else {
                    Players oldOwner = oldIsland.getOwner();
                    String oldOwnerName = oldOwner != null ? oldOwner.getLastKnowName() : "未知";
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&f[&7空岛&f] &c你已离开 &e" + oldOwnerName + " &c的空岛~"));
                    if(!player.isOp()) notifyIslandMembers(oldIsland, player, false);
                }
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&f[&7空岛&f] &6你已离开无人区~"));
            }
        }

        // ── 进入新区域（更新记录并发送消息）──
        lastRegion.put(player.getUniqueId(), currentRegion);

        if (currentRegion.x() == 0 && currentRegion.z() == 0) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&f[&7空岛&f] &b你已进入空岛主城~"));
        } else {
            Island newIsland = SkylliaAPI.getIslandByRegion(currentRegion);
            if (newIsland != null) {
                UUID playerId = player.getUniqueId();
                boolean isOwnIsland = isOwnIsland(newIsland, playerId);
                if (isOwnIsland) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&f[&7空岛&f] &a你已回到自己的空岛~"));
                } else {
                    Players newOwner = newIsland.getOwner();
                    String newOwnerName = newOwner != null ? newOwner.getLastKnowName() : "未知";
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&f[&7空岛&f] &a你已进入 &e" + newOwnerName + " &a的空岛~"));
                    if(!player.isOp()) notifyIslandMembers(newIsland, player, true);
                }
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&f[&7空岛&f] &6你已进入无人区，下次再来探索吧~"));
            }
        }
    }

    /**
     * 取消移动事件并根据冷却发送拦截消息。
     *
     * @param player      玩家
     * @param event       移动事件
     * @param owner       目标岛屿岛主
     * @param messageFormat 消息格式，%s 将被替换为岛主名
     */
    private void cancelAndNotify(Player player, PlayerMoveEvent event, Players owner,
                                 String messageFormat) {
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long lastTime = lastBlockTime.get(uuid);

        // 冷却检查：距离上次拦截超过 1 秒才发送消息
        if (lastTime == null || now - lastTime >= 1000) {
            String ownerName = owner != null ? owner.getLastKnowName() : "未知";
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    String.format(messageFormat, ownerName)));
            lastBlockTime.put(uuid, now);
        }
        // 无论是否发送消息，都不更新 lastRegion，玩家仍在旧区域
    }

    /**
     * 判断玩家是否为岛屿的真正成员（非被禁足）。
     */
    private boolean isPrivilegedMember(Island island, UUID playerId) {
        Players member = island.getMember(playerId);
        return member != null && member.getRoleType() != RoleType.BAN;
    }

    /**
     * 判断该岛屿是否为玩家自己的岛屿（岛主或未被禁足的成员）。
     */
    private boolean isOwnIsland(Island island, UUID playerId) {
        Players owner = island.getOwner();
        if (owner != null && owner.getMojangId().equals(playerId)) return true;
        return isPrivilegedMember(island, playerId);
    }

    private void notifyIslandMembers(Island island, Player visitor, boolean entering) {
        String visitorName = visitor.getName();
        String action = entering ? "访问了" : "离开了";
        String message = "&f[&7空岛&f] &e" + visitorName + " &d" + action + "你的空岛~";
        String colored = ChatColor.translateAlternateColorCodes('&', message);

        Players owner = island.getOwner();
        if (owner != null) {
            Player ownerPlayer = Bukkit.getPlayer(owner.getMojangId());
            if (ownerPlayer != null && !ownerPlayer.equals(visitor)) {
                ownerPlayer.sendMessage(colored);
            }
        }

        for (Players member : island.getMembers()) {
            if (member.getMojangId().equals(visitor.getUniqueId())) continue;
            if (owner != null && member.getMojangId().equals(owner.getMojangId())) continue;
            Player memberPlayer = Bukkit.getPlayer(member.getMojangId());
            if (memberPlayer != null) {
                memberPlayer.sendMessage(colored);
            }
        }
    }
}