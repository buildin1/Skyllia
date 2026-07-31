package fr.euphyllia.skyllia.listeners.zone;

import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.zone.ActivityZone;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Optional;

/**
 * 活动区内的方块放置权限，见 {@link ZoneBlockBreakPermissions} 的说明：需要在
 * 岛屿权限监听器（{@code BlockPlacePermissions}，NORMAL 优先级）之后运行，
 * 才能覆盖掉"该位置没有岛屿所以直接取消"的默认判定。
 */
public class ZoneBlockPlacePermissions implements Listener {

    private final InterneAPI interneAPI;

    public ZoneBlockPlacePermissions(InterneAPI interneAPI) {
        this.interneAPI = interneAPI;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(final BlockPlaceEvent event) {
        final Block placed = event.getBlockPlaced();
        final World world = placed.getWorld();
        final Player player = event.getPlayer();

        if (!SkylliaAPI.isWorldSkyblock(world) || player.isOp()) return;

        final Optional<ActivityZone> zoneOpt = interneAPI.getActivityZoneManager()
                .findZoneAt(placed.getX(), placed.getZ());
        if (zoneOpt.isEmpty()) return;

        final ActivityZone zone = zoneOpt.get();
        event.setCancelled(!zone.allowPlace());
        if (!zone.allowPlace()) {
            ConfigLoader.language.sendMessage(player, "island.zone.place-denied");
        }
    }
}
