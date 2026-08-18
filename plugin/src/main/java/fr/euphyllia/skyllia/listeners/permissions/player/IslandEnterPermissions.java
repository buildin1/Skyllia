package fr.euphyllia.skyllia.listeners.permissions.player;

import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * 注册「进入他人岛屿」权限 {@code skyllia:island.enter}。
 * <p>
 * 这个模块本身<b>不监听任何事件</b>，只负责把权限节点注册进注册表 —— 真正的拦截发生在
 * {@code PlayerRegionChangeListener} 里，玩家跨 region 移动时判定。
 * </p>
 * <p>
 * <b>为什么需要它</b>：在此之前，控制「访客能不能走进这座岛」的其实是
 * {@code skyllia:player.teleport}，而它在权限菜单里显示的名字是「传送玩家」——
 * 没有人会猜到那是岛屿通行开关，导致岛主既找不到"访客移动权限"这一项，
 * 也不知道自己关掉的到底是什么。现在这项能力有了名副其实的独立节点。
 * </p>
 * <p>
 * <b>兼容性</b>：拦截处仍然保留对 {@code player.teleport} 的回退判定，
 * 因此升级前依赖 {@code player.teleport} 放行访客的岛屿不会突然把人挡在门外。
 * </p>
 */
public class IslandEnterPermissions implements PermissionModule {

    /** 权限键名，供拦截方按名查找。 */
    public static final String KEY = "island.enter";

    private PermissionId islandEnter;

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.islandEnter = registry.register(new PermissionNode(
                new NamespacedKey(owner, KEY),
                "island.permission.island_enter.name",
                "island.permission.island_enter.description"
        ));
    }

    public PermissionId getIslandEnter() {
        return islandEnter;
    }
}
