package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.service.TrustService;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;

import java.util.Set;

/**
 * 防止访客玩家用弹射物（三叉戟、箭等）破坏紫颂植物等可被弹射物破坏的方块。
 * 只有岛主、正式成员（MEMBER 及以上）或信任玩家才允许。
 */
public class ProjectileBlockBreakListener implements Listener {

    /**
     * 已知可被弹射物一击破坏的方块类型。
     */
    private static final Set<Material> BREAKABLE_BY_PROJECTILE = Set.of(
            Material.CHORUS_PLANT,
            Material.CHORUS_FLOWER,
            Material.POINTED_DRIPSTONE,
            Material.DECORATED_POT
    );

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(final ProjectileHitEvent event) {
        // 只关心弹射物击中方块
        Block hitBlock = event.getHitBlock();
        if (hitBlock == null) return;

        // 只关心可被弹射物破坏的方块
        if (!BREAKABLE_BY_PROJECTILE.contains(hitBlock.getType())) return;

        // 获取投掷者（必须是玩家）
        if (!(event.getEntity().getShooter() instanceof Player player) || player.isOp()) return;

        // 检查是否在空岛世界
        final Location location = hitBlock.getLocation();
        if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) return;

        // 获取所在岛屿
        final int chunkX = location.getBlockX() >> 4;
        final int chunkZ = location.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) return; // 无人区放行

        // 判断玩家是否为岛主、成员（不含 VISITOR/BAN）或信任玩家
        final boolean isOwner = island.getOwner() != null
                && island.getOwner().getMojangId().equals(player.getUniqueId());

        final Players member = island.getMember(player.getUniqueId());
        final boolean isMember = member != null
                && member.getRoleType().getValue() >= RoleType.MEMBER.getValue();

        final TrustService trustService = SkylliaAPI.getTrustService();
        final boolean isTrusted = trustService != null
                && trustService.isTrusted(island.getId(), player.getUniqueId());

        if (!(isOwner || isMember || isTrusted)) {
            event.setCancelled(true);
        }
    }
}
