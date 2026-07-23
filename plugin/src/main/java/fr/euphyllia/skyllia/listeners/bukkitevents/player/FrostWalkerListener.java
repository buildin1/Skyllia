package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.service.TrustService;
import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.EntityBlockFormEvent;

public class FrostWalkerListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onEntityBlockForm(final EntityBlockFormEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.isOp()) return;

        if (event.getNewState().getType() != Material.FROSTED_ICE) return;

        final Location location = event.getBlock().getLocation();
        if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) return;

        // 获取所在岛屿
        final int chunkX = location.getBlockX() >> 4;
        final int chunkZ = location.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) {
            event.setCancelled(true);
            return;
        }

        // 判断玩家是否为岛主、成员或信任玩家
        final boolean isOwner = island.getOwner() != null &&
                island.getOwner().getMojangId().equals(player.getUniqueId());
        final boolean isMember = island.getMember(player.getUniqueId()) != null;
        final TrustService trustService = SkylliaAPI.getTrustService();
        final boolean isTrusted = trustService != null && trustService.isTrusted(island.getId(), player.getUniqueId());

        if (!(isOwner || isMember || isTrusted)) {
            event.setCancelled(true);
        }
    }
}