package fr.euphyllia.skyllia.listeners.permissions.flags.entity;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
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
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;

public class IslandMobSpawnHostileFlag implements FlagModule {

    private FlagId ALLOW_SPAWN_ALL_HOSTILE;
    private Map<EntityType, FlagId> flagByType;

    @Override
    public void registerFlags(IslandFlagRegistry registry, Plugin owner) {
        this.ALLOW_SPAWN_ALL_HOSTILE = registry.idOrRegister(new FlagNode(
                new NamespacedKey(owner, "island.spawn.hostile.all"),
                "island.flag.spawn_hostile_all.name",
                "island.flag.spawn_hostile_all.description"
        ));

        this.flagByType = new EnumMap<>(EntityType.class);
        Map<EntityType, String> supported = SkylliaAPI.getMobsSpawnImpl().supportedHostileMobs();
        for (Map.Entry<EntityType, String> entry : supported.entrySet()) {
            if (entry.getKey() == EntityType.MAGMA_CUBE) {
                // 2026-08-21 服主反馈：岩浆怪的单独开关和总开关一样"关了也没用"（根因见
                // ImplPermissionsManagers#hasFlag 的说明，岛屿自身设置这一层的"或"逻辑
                // 只能加不能减）。先不给它注册单独的 FlagId，完全交给总开关判定；这个 key
                // 仍然留在 flagByType 里、值是 null——用来标记"这是已知的敌对生物，只是
                // 没有单独开关"，和"根本不是敌对生物、这个模块管不着"区分开，
                // 见 onPreCreatureSpawn/onCreatureSpawn 和 shouldCancelSpawn 的处理。
                // 真正的三状态修复上线后，如果服主想恢复单独控制，把这段特判删掉即可。
                flagByType.put(entry.getKey(), null);
                continue;
            }
            flagByType.put(entry.getKey(), registry.idOrRegister(new FlagNode(
                    new NamespacedKey(owner, "island.spawn.hostile." + entry.getValue()),
                    "island.flag.spawn_hostile_" + entry.getValue() + ".name",
                    "island.flag.spawn_hostile_" + entry.getValue() + ".description"
            )));
        }
    }

    /**
     * @param specific 该生物类型对应的单独开关；{@code null} 表示"已知是敌对生物，但没有
     *                 单独开关"（目前只有岩浆怪），此时只看总开关 {@link #ALLOW_SPAWN_ALL_HOSTILE}。
     */
    private boolean shouldCancelSpawn(FlagId specific, Location location) {
        final World world = location.getWorld();

        final int bx = location.getBlockX();
        final int by = location.getBlockY();
        final int bz = location.getBlockZ();

        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) return true;

        final String worldName = world.getName();
        final boolean allowed = specific == null
                ? SkylliaAPI.getPermissionsManager().hasFlag(island, ALLOW_SPAWN_ALL_HOSTILE, worldName)
                : SkylliaAPI.getPermissionsManager().hasFlag(island, specific, ALLOW_SPAWN_ALL_HOSTILE, worldName);
        if (!allowed) {
            return true;
        }
        return ListenersUtils.isBlockOutsideIsland(island, world, bx, by, bz, null);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onPreCreatureSpawn(final PreCreatureSpawnEvent event) {
        if (flagByType == null) return;
        if (SkylliaAPI.getMobsSpawnImpl().ignoredReasons().contains(event.getReason())) return;
        // containsKey 而不是 get()==null 判断："已知敌对生物但没有单独开关"（比如岩浆怪，
        // 值是 null）和"根本不在敌对生物清单里"（key 都不存在）必须区分开——前者要交给
        // 总开关判定，后者这个模块完全不该管（不能因为查到 null 就当成两种情况一样处理）。
        if (!flagByType.containsKey(event.getType())) return;

        final FlagId specific = flagByType.get(event.getType());
        if (shouldCancelSpawn(specific, event.getSpawnLocation())) {
            event.setCancelled(true);
            event.setShouldAbortSpawn(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        if (flagByType == null) return;
        if (SkylliaAPI.getMobsSpawnImpl().ignoredReasons().contains(event.getSpawnReason())) return;
        if (!flagByType.containsKey(event.getEntityType())) return;

        final FlagId specific = flagByType.get(event.getEntityType());
        if (shouldCancelSpawn(specific, event.getLocation())) {
            event.setCancelled(true);
        }
    }
}