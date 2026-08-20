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

public class IslandMobSpawnOtherFlag implements FlagModule {

    private FlagId ALLOW_SPAWN_ALL_OTHER;
    private Map<EntityType, FlagId> flagByType;

    @Override
    public void registerFlags(IslandFlagRegistry registry, Plugin owner) {
        this.ALLOW_SPAWN_ALL_OTHER = registry.idOrRegister(new FlagNode(
                new NamespacedKey(owner, "island.spawn.other.all"),
                "island.flag.spawn_other_all.name",
                "island.flag.spawn_other_all.description"
        ));

        this.flagByType = new EnumMap<>(EntityType.class);
        Map<EntityType, String> supported = SkylliaAPI.getMobsSpawnImpl().supportedOtherMobs();
        for (Map.Entry<EntityType, String> entry : supported.entrySet()) {
            // 盔甲架不是"生物生成"意义上的生物，而是玩家手动放置的装饰物（Bukkit 用
            // CreatureSpawnEvent+SpawnReason.DEFAULT 表示这个放置动作），不应该和
            // 巨人/唤魔者这类需要岛主手动开启的稀有生物共用"默认关闭"的口径，否则新岛
            // 玩家连自己的盔甲架都放不了。
            boolean defaultValue = entry.getKey() == EntityType.ARMOR_STAND;
            flagByType.put(entry.getKey(), registry.idOrRegister(new FlagNode(
                    new NamespacedKey(owner, "island.spawn.other." + entry.getValue()),
                    "island.flag.spawn_other_" + entry.getValue() + ".name",
                    "island.flag.spawn_other_" + entry.getValue() + ".description",
                    defaultValue
            )));
        }
    }

    private boolean shouldCancelSpawn(FlagId specific, Location location) {
        final World world = location.getWorld();

        final int bx = location.getBlockX();
        final int by = location.getBlockY();
        final int bz = location.getBlockZ();

        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) return true;

        final String worldName = world.getName();
        if (!SkylliaAPI.getPermissionsManager().hasFlag(island, specific, ALLOW_SPAWN_ALL_OTHER, worldName)) {
            return true;
        }
        return ListenersUtils.isBlockOutsideIsland(island, world, bx, by, bz, null);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onPreCreatureSpawn(final PreCreatureSpawnEvent event) {
        if (flagByType == null) return;
        if (SkylliaAPI.getMobsSpawnImpl().ignoredReasons().contains(event.getReason())) return;

        final FlagId specific = flagByType.get(event.getType());
        if (specific == null) return;

        if (shouldCancelSpawn(specific, event.getSpawnLocation())) {
            event.setCancelled(true);
            event.setShouldAbortSpawn(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        if (flagByType == null) return;
        if (SkylliaAPI.getMobsSpawnImpl().ignoredReasons().contains(event.getSpawnReason())) return;

        final FlagId specific = flagByType.get(event.getEntityType());
        if (specific == null) return;

        if (shouldCancelSpawn(specific, event.getLocation())) {
            event.setCancelled(true);
        }
    }
}