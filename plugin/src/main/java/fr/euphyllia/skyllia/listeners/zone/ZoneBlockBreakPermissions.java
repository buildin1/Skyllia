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
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Optional;

/**
 * 活动区（商店、PVP 场地等）内的方块破坏权限。这些区域通常没有岛屿覆盖，
 * 所以要在岛屿权限监听器（{@code BlockBreakPermissions}，NORMAL 优先级）判定
 * 完之后再跑，需要时把它的取消结果覆盖掉。
 */
public class ZoneBlockBreakPermissions implements Listener {

    private final InterneAPI interneAPI;

    public ZoneBlockBreakPermissions(InterneAPI interneAPI) {
        this.interneAPI = interneAPI;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(final BlockBreakEvent event) {
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        final World world = block.getWorld();

        if (!SkylliaAPI.isWorldSkyblock(world) || player.isOp()) return;

        final Optional<ActivityZone> zoneOpt = interneAPI.getActivityZoneManager()
                .findZoneAt(block.getX(), block.getZ());
        if (zoneOpt.isEmpty()) return;

        final ActivityZone zone = zoneOpt.get();
        event.setCancelled(!zone.allowBreak());
        if (!zone.allowBreak()) {
            ConfigLoader.language.sendMessage(player, "island.zone.break-denied");
        }
    }
}
