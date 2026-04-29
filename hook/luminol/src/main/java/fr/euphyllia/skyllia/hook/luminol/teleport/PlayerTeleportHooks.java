package fr.euphyllia.skyllia.hook.luminol.teleport;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.event.teleport.PlayerTeleportIslandEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import me.earthme.luminol.api.entity.EntityTeleportAsyncEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class PlayerTeleportHooks implements Listener {

    @EventHandler
    public void onEntityTeleport(final EntityTeleportAsyncEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Location to = event.getDestination();
        if (!SkylliaAPI.isWorldSkyblock(to.getWorld())) return;

        final int chunkX = to.getBlockX() >> 4;
        final int chunkZ = to.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) return;

        // 事件现在是同步的，可以直接在当前线程触发（当前线程是 Folia 的区域线程，允许同步事件）
        new PlayerTeleportIslandEvent(
                player,
                player.getLocation(),
                to,
                island,
                event.getTeleportCause(),
                false,
                false    // 现在的 async 参数已无效，传 false 即可
        ).callEvent();
    }
}