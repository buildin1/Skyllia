package fr.euphyllia.skyllia.managers.skyblock;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.FlagId;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionsManagers;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImplPermissionsManagers implements PermissionsManagers {

    private static final Logger log = LoggerFactory.getLogger(ImplPermissionsManagers.class);

    /**
     * Checks whether the given player has the specified permission on the island.
     * No Bukkit permission bypass is applied and debug logging is disabled.
     *
     * @param player     the player to check.
     * @param island     the island on which the permission is checked.
     * @param permission the permission to verify.
     * @return {@code true} if the player has the permission, {@code false} otherwise.
     */
    public boolean hasPermission(Player player, Island island, PermissionId permission) {
        return hasPermission(player, island, permission, null, ConfigLoader.general.getDebugSettings().permission());
    }

    /**
     * Checks whether the given player has the specified permission on the island,
     * with an optional Bukkit permission bypass. Debug logging is disabled.
     *
     * @param player           the player to check.
     * @param island           the island on which the permission is checked.
     * @param permission       the permission to verify.
     * @param bukkitPermission an optional Bukkit permission node; if the player holds it, access is granted immediately.
     * @return {@code true} if the player has the permission, {@code false} otherwise.
     */
    public boolean hasPermission(Player player, Island island, PermissionId permission, @Nullable String bukkitPermission) {
        return hasPermission(player, island, permission, bukkitPermission, ConfigLoader.general.getDebugSettings().permission());
    }

    /**
     * Checks whether the given player has the specified permission on the island,
     * with an optional Bukkit permission bypass and optional debug logging.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>If {@code bukkitPermission} is non-null and the player holds it, access is granted immediately.</li>
     *   <li>If the player's role is {@link RoleType#OWNER}, access is always granted.</li>
     *   <li>If the player's role is {@link RoleType#BAN}, access is always denied.</li>
     *   <li>Otherwise, the island's compiled permissions are consulted for the player's role.</li>
     * </ol>
     * </p>
     *
     * @param player           the player to check.
     * @param island           the island on which the permission is checked.
     * @param permission       the permission to verify.
     * @param bukkitPermission an optional Bukkit permission node; if the player holds it, access is granted immediately.
     * @param debug            if {@code true}, detailed resolution steps are logged at INFO level.
     * @return {@code true} if the player has the permission, {@code false} otherwise.
     */
    public boolean hasPermission(Player player, Island island, PermissionId permission, @Nullable String bukkitPermission, boolean debug) {
        if (bukkitPermission != null && PlayerUtils.hasPermission(player, bukkitPermission)) {
            if (debug) {
                String permName;
                try {
                    permName = SkylliaAPI.getPermissionRegistry().node(permission).node().toString();
                } catch (Exception e) {
                    permName = "unknown#" + permission.index();
                }
                log.info("Player {} has Bukkit permission {}, granting access to {}", player.getName(), bukkitPermission, permName);
            }
            return true;
        }
        var member = island.getMember(player.getUniqueId());
        RoleType role = member != null ? member.getRoleType() : RoleType.VISITOR;
        if (role == null) role = RoleType.VISITOR;

        if (!ConfigLoader.general.getPermissionsSettings().checkOwner()) {
            if (role == RoleType.OWNER) return true;
        }
        if (!ConfigLoader.general.getPermissionsSettings().checkBan()) {
            if (role == RoleType.BAN) return false;
        }

        if (SkylliaAPI.getTrustService().isTrusted(island.getId(), player.getUniqueId())) {
            role = RoleType.MEMBER;
        }

        // 全局接管层：管理员在 permissions-v2.toml 的 [global-override.<角色>] 里接管的权限
        // 直接决定结果，不再查岛屿自己的位图。这让「全服放开某项权限」立刻作用于所有岛屿
        // （含建岛时位图被写成全零的老岛），无需迁移数据库、无需重启。
        // 放在岛主/封禁短路之后：岛主永远不会被全局配置锁死在自己岛上。
        Boolean override = permissionOverride(role, permission);
        if (override != null) {
            if (debug) {
                log.info("Player {} has role {}, permission {}: {} (来源: 全局接管)",
                        player.getName(), role, describePermission(permission), override);
            }
            return override;
        }

        var compiled = island.getCompiledPermissions();
        boolean has = compiled.has(SkylliaAPI.getPermissionRegistry(), role, permission);
        if (debug) {
            String permName;
            try {
                permName = SkylliaAPI.getPermissionRegistry().node(permission).node().toString();
            } catch (Exception e) {
                permName = "unknown#" + permission.index();
            }
            log.info("Player {} has role {}, permission {}: {}", player.getName(), role, permName, has);
        }
        return has;
    }

    @Override
    public boolean hasFlag(Island island, FlagId flag, String worldName) {
        if (flag == null) return false;

        Boolean override = flagOverride(flag, worldName);
        if (override != null) return override;

        return island.getIslandFlags(worldName)
                .has(SkylliaAPI.getFlagRegistry(), flag);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 解析优先级：<b>单体标志的全局接管 &gt; 全体（{@code .all}）标志的全局接管 &gt; 岛屿自身设置</b>。
     * </p>
     * <p>
     * 单体接管排在全体接管之前，是为了让「全服禁止某一种生物」成为可能：管理员全局接管层
     * 用 {@code Boolean}（可空）表达「没碰过 / 明确开 / 明确关」三种状态，所以能做到
     * 「即便 hostile.all = true，也能单独强制关掉幻翼」——接管层这部分逻辑没有问题。
     * </p>
     * <p>
     * 岛屿自身设置这一层取<b>「与」</b>：总开关和单体开关<b>都</b>开才允许——总开关是总闸，
     * 单体开关在总闸开着时各自独立生效。这正是玩家的直觉语义（「开启爆炸 + 关闭恶魂」
     * 应当挡住恶魂爆炸，2026-08 岩浆怪刷新与恶魂爆炸两单反馈皆因旧「或」逻辑而起）。
     * </p>
     * <p>
     * 「与」能安全上线的前提是存量位图已完成「或→与」归一化：2026-08-21 曾直接改「与」，
     * 因存量岛屿扩容补 0 的单体位被暴露成「永远拒绝」而全服刷怪全灭、连夜回滚。现在
     * 归一化由 {@code FlagWordsNormalizer} 在数据库加载路径上<b>惰性</b>完成（含此后
     * 新注册标志的自动回填），本方法拿到的位图恒为已归一化状态，不需要再兼容旧语义。
     * 配对关系的唯一事实来源是 {@code IslandFlagRegistry#declareFallback}。
     * </p>
     */
    @Override
    public boolean hasFlag(Island island, FlagId specific, FlagId fallback, String worldName) {
        Boolean specificOverride = flagOverride(specific, worldName);
        if (specificOverride != null) return specificOverride;

        Boolean fallbackOverride = flagOverride(fallback, worldName);
        if (fallbackOverride != null) return fallbackOverride;

        var flags = island.getIslandFlags(worldName);
        var registry = SkylliaAPI.getFlagRegistry();
        // 调用方（含附属）可能用 getIfPresent 拿标志、传进来 null，按“只看另一个”兜底
        if (specific == null) return fallback != null && flags.has(registry, fallback);
        if (fallback == null) return flags.has(registry, specific);
        return flags.has(registry, specific) && flags.has(registry, fallback);
    }

    /**
     * 读取标志的全局接管值。
     * <p>
     * 之所以做空值防护：这些判定会在插件启动早期就被事件触发，而
     * {@code ConfigLoader.islandFlags} 要等 {@code ConfigLoader.init()} 之后才可用。
     * 配置尚未就绪时返回 {@code null}，调用方自然回退到岛屿自身设置。
     * </p>
     */
    private static @Nullable Boolean flagOverride(@Nullable FlagId id, String worldName) {
        var cfg = ConfigLoader.islandFlags;
        return cfg == null ? null : cfg.globalOverride(id, worldName);
    }

    /**
     * 读取权限的全局接管值。空值防护理由同 {@link #flagOverride}。
     */
    private static @Nullable Boolean permissionOverride(@Nullable RoleType role, @Nullable PermissionId id) {
        var cfg = ConfigLoader.permissionsV2;
        return cfg == null ? null : cfg.globalOverride(role, id);
    }

    /**
     * 把权限 ID 渲染成可读名称，仅用于调试日志；解析失败时退回索引号。
     */
    private static String describePermission(PermissionId permission) {
        try {
            return SkylliaAPI.getPermissionRegistry().node(permission).node().toString();
        } catch (Exception e) {
            return "unknown#" + permission.index();
        }
    }
}
