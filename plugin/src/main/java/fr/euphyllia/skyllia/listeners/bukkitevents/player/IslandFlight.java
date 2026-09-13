package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空岛飞行（/is fly）：只能在自己的空岛上飞。
 * <p>
 * 「自己的空岛」= 岛主或正式成员（MEMBER 及以上），访客和被封禁的不算；位置必须在岛屿边界内。
 * 开启后是一个开关状态：离开自己的空岛自动暂停，回来自动恢复，玩家下线即清除。
 * 创造/旁观模式本身就能飞，这里完全不碰。
 * </p>
 * <p>
 * <b>线程</b>：所有改飞行状态的方法都必须在玩家自己的线程上调用（Folia 下实体只能在所在区域线程访问）。
 * </p>
 */
public final class IslandFlight {

    /** 被中途关掉飞行的玩家，这段时间内的第一次摔落伤害免除。 */
    private static final long FALL_GRACE_MILLIS = 15_000L;

    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> FALL_GRACE = new ConcurrentHashMap<>();

    private IslandFlight() {
    }

    public static boolean isEnabled(@NotNull UUID playerId) {
        return ENABLED.contains(playerId);
    }

    /** 切换飞行开关。 */
    public static void toggle(@NotNull Player player) {
        UUID id = player.getUniqueId();
        if (ENABLED.remove(id)) {
            stopFlying(player);
            send(player, "&f[&7空岛&f] &e飞行已关闭");
            return;
        }
        if (isExemptGameMode(player)) {
            send(player, "&f[&7空岛&f] &e创造/旁观模式本身就能飞，不需要开启");
            return;
        }
        if (!canFlyAt(player, player.getLocation())) {
            send(player, "&f[&7空岛&f] &c只能在自己的空岛上开启飞行");
            return;
        }
        ENABLED.add(id);
        player.setAllowFlight(true);
        send(player, "&f[&7空岛&f] &a飞行已开启，双击空格起飞；离开自己的空岛会自动暂停");
    }

    /** 按玩家所在位置同步飞行资格：离开自己的空岛暂停，回来恢复。只处理开了飞行的玩家。 */
    public static void refresh(@NotNull Player player, @NotNull Location at) {
        if (!ENABLED.contains(player.getUniqueId()) || isExemptGameMode(player)) return;

        boolean allowed = canFlyAt(player, at);
        if (allowed == player.getAllowFlight()) return;

        if (allowed) {
            player.setAllowFlight(true);
            send(player, "&f[&7空岛&f] &a回到自己的空岛，飞行已恢复");
        } else {
            stopFlying(player);
            send(player, "&f[&7空岛&f] &c已离开自己的空岛，飞行暂停，回来后自动恢复");
        }
    }

    /** 玩家下线：清掉开关和摔落保护。 */
    public static void forget(@NotNull UUID playerId) {
        ENABLED.remove(playerId);
        FALL_GRACE.remove(playerId);
    }

    /** 这次摔落伤害是否该免除（只免一次）。 */
    public static boolean consumeFallGrace(@NotNull UUID playerId) {
        Long at = FALL_GRACE.remove(playerId);
        return at != null && System.currentTimeMillis() - at <= FALL_GRACE_MILLIS;
    }

    private static boolean canFlyAt(Player player, Location location) {
        World world = location.getWorld();
        if (world == null || !SkylliaAPI.isWorldSkyblock(world)) return false;

        Island island = SkylliaAPI.getIslandByChunk(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        if (island == null || !island.isInside(location)) return false;

        UUID id = player.getUniqueId();
        Players owner = island.getOwner();
        if (owner != null && id.equals(owner.getMojangId())) return true;

        Players member = island.getMember(id);
        if (member == null) return false;
        RoleType role = member.getRoleType();
        return role == RoleType.OWNER || role == RoleType.CO_OWNER
                || role == RoleType.MODERATOR || role == RoleType.MEMBER;
    }

    private static void stopFlying(Player player) {
        if (isExemptGameMode(player)) return;
        if (player.isFlying()) {
            FALL_GRACE.put(player.getUniqueId(), System.currentTimeMillis());
            player.setFlying(false);
        }
        player.setAllowFlight(false);
    }

    private static boolean isExemptGameMode(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    private static void send(Player player, String message) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
