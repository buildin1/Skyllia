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
     * <b>岛屿自身设置这一层（走到这里说明两层全局接管都没碰过）目前是纯布尔位图，没有
     * 「没碰过」这个状态</b>——单体标志和总开关都只有开/关两种值，新建岛屿两者默认都是开的。
     * 2026-08-21 服主反馈实测确认：这一层如果用「或」（旧写法），会导致「关掉总开关，
     * 但没碰过的单体标志仍是默认开」时总开关形同虚设——单体默认开着，或出来永远是开；
     * 反过来「只关掉某一个单体标志，总开关还开着」也一样会被或穿。也就是说旧的「或」在
     * 这一层实际上只能表达「加」，永远表达不了「减」，跟这段方法本来想解决的问题（总开关
     * 关了应该真的把所有生物都关掉）背道而驰。改成「与」：两者都开，这种生物才允许生成；
     * 任意一个关掉，就不允许。代价是「总开关关了、但单独放行某一种生物」这种玩法在岛屿
     * 自身设置这一层做不到了——想做到仍然可以靠上面的全局接管层（那一层是三态判定，不受
     * 这个限制）。这是有意的取舍：真正能让岛屿自身设置也两种用法都支持，需要给它也补一套
     * 三态标记（记录某个具体标志有没有被玩家亲手碰过），工作量更大，留作后续单独一轮，
     * 这里先保证总开关和单体开关都能按直觉正常工作。
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
