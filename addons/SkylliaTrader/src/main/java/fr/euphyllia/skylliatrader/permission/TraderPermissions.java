package fr.euphyllia.skylliatrader.permission;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * SkylliaTrader 注册进 Skyllia 岛屿权限系统（{@code skyllia:*} 之外，各 addon 自带命名空间）
 * 的角色权限点。仿 {@code SkylliaChest} 的 {@code ChestCommand} 里
 * {@code SkylliaAPI.getPermissionRegistry().idOrRegister(...)} 的写法：
 * <b>实例 final 字段，不是可变的公开静态字段</b>——静态字段在 {@code /reload} 之后会留下指向
 * 上一份注册表的悬空引用，而实例字段随插件实例一起重建，不会有这个问题。
 * <p>
 * T1 阶段还没有真正的游商实体交互监听器（那是 T2 的范围），这里先把权限节点注册好、
 * 并在 permissions-v2.toml 里给六个角色都写好默认值，避免"新增权限点默认全灭"的坑
 * （新建岛屿的附属权限如果没写进 defaults，会连岛主自己都用不了）。
 * </p>
 * <p>
 * 注意：本类的注册发生在 addon 自己的 {@code onEnable} 里，而核心的
 * {@code ConfigLoader.permissionsV2.compileNow()} 在那之前就已经跑过一次了。核心因此在
 * {@code Skyllia#onEnable} 末尾追加了一次"延迟 1 tick 的重新编译"，专门把各 addon 注册的
 * 权限点补进权限默认值——没有那次重编译的话，permissions-v2.toml 里
 * {@code skylliatrader:merchant.interact} 那六行会被当成未知权限静默丢弃。
 * </p>
 * <p>
 * T2 实现游商实体交互监听器时，直接用 {@link #merchantInteract()} 调用
 * {@code SkylliaAPI.getPermissionsManager().hasPermission(...)} 即可，不需要再注册一遍。
 * </p>
 */
public final class TraderPermissions {

    /** 是否允许该角色与本岛的游商交互（含未来的购买 / 提交订单）。T2 起在监听器里实际判定。 */
    private final PermissionId merchantInteract;

    /**
     * 是否允许该角色在本岛使用商队凭证召唤常驻商人。
     * <p>
     * 和 {@link #merchantInteract} 分开是因为两者的后果完全不同：交互只是买东西，
     * 而召唤会<b>占掉这座岛这一种商队的唯一名额</b>——名额被占之后，其他成员再用凭证会失败。
     * 岛主应该能把「谁可以决定用掉这个名额」单独收回去，而不是被迫连买东西的权限一起关掉。
     * </p>
     */
    private final PermissionId merchantSummon;

    public TraderPermissions(@NotNull Plugin plugin) {
        this.merchantInteract = SkylliaAPI.getPermissionRegistry().idOrRegister(
                new PermissionNode(
                        new NamespacedKey(plugin, "merchant.interact"),
                        "addons.trader.permission.merchant_interact.name",
                        "addons.trader.permission.merchant_interact.description",
                        true
                )
        );
        this.merchantSummon = SkylliaAPI.getPermissionRegistry().idOrRegister(
                new PermissionNode(
                        new NamespacedKey(plugin, "merchant.summon"),
                        "addons.trader.permission.merchant_summon.name",
                        "addons.trader.permission.merchant_summon.description",
                        true
                )
        );
    }

    public @NotNull PermissionId merchantInteract() {
        return merchantInteract;
    }

    public @NotNull PermissionId merchantSummon() {
        return merchantSummon;
    }
}
