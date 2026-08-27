package fr.euphyllia.skyllia.listeners.permissions.flags.grief;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.FlagId;
import fr.euphyllia.skyllia.api.permissions.FlagNode;
import fr.euphyllia.skyllia.api.permissions.IslandFlagRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.FlagModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LargeFireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;

public class IslandGhastGriefFlag implements FlagModule {

    private FlagId ALLOW_MOB_GRIEF;
    private FlagId ALLOW_GHAST_GRIEF;

    @Override
    public void registerFlags(IslandFlagRegistry registry, Plugin owner) {
        this.ALLOW_MOB_GRIEF = registry.idOrRegister(new FlagNode(
                new NamespacedKey(owner, "island.allow.mob-grief"),
                "island.flag.allow_mob_grief.name",
                "island.flag.allow_mob_grief.description"
        ));
        this.ALLOW_GHAST_GRIEF = registry.idOrRegister(new FlagNode(
                new NamespacedKey(owner, "island.allow.ghast-grief"),
                "island.flag.allow_ghast_grief.name",
                "island.flag.allow_ghast_grief.description"
        ));
        registry.declareFallback(ALLOW_GHAST_GRIEF, ALLOW_MOB_GRIEF);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(final EntityExplodeEvent event) {
        // 只认实体类型、不认 getShooter()：shooter 是按 UUID 现场解析的，恶魂在火球落地前
        // 死亡/被卸载/跨 region 时会解析成 null，火球被玩家反弹后还会变成玩家——按 shooter
        // 过滤会让这些爆炸完全绕过本开关（2026-08 恶魂爆炸反馈的漏网路径之一）。
        // 用 LargeFireball 而不是 Fireball：凋灵之首（WitherSkull）和末影龙火球在 Bukkit
        // 里也是 Fireball 的子类，各有各的开关，不能被这里重复管辖。
        final Entity entity = event.getEntity();
        if (!(entity instanceof LargeFireball)) return;

        final Location location = event.getLocation();
        final World world = location.getWorld();
        if (world == null) return;

        final int bx = location.getBlockX();
        final int by = location.getBlockY();
        final int bz = location.getBlockZ();

        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) {
            event.setCancelled(true);
            return;
        }

        final String worldName = world.getName();
        if (!SkylliaAPI.getPermissionsManager().hasFlag(island, ALLOW_GHAST_GRIEF, ALLOW_MOB_GRIEF, worldName)) {
            event.setCancelled(true);
            return;
        }

        ListenersUtils.isBlockOutsideIsland(island, world, bx, by, bz, event);
    }
}
