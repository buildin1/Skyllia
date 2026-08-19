package fr.euphyllia.skyllia.listeners.bukkitevents.blocks;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 反矿物农场：矿石方块被破坏 / 熔炼 / 炸毁时不掉落任何物品。
 * <p>
 * <b>默认关闭</b>，由 {@code settings.island.prevent-ore-drops} 控制。开关在每个处理器
 * 入口处实时读取，因此 {@code /isadmin reload} 之后立即生效，无需重启。
 */
public class OreDropPreventionListener implements Listener {

    /**
     * 参与拦截的矿石方块。
     * <p>
     * <b>必须是显式集合，不能用 {@code name().endsWith("_ORE")} 判断。</b>
     * 后缀匹配会把 {@link Material#NETHER_QUARTZ_ORE} 和 {@link Material#NETHER_GOLD_ORE}
     * 一并误伤 —— 玩家自己放下的下界石英矿挖了什么都不掉，这正是本监听器此前被报的 bug。
     * <p>
     * 这里只收录<b>主世界会被矿物生成器批量产出</b>的矿石。下界石英矿 / 下界金矿刻意不在其中：
     * 它们在本服无法量产，拦截它们只会误伤玩家的正常游玩。
     */
    private static final Set<Material> PREVENTED_ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.ANCIENT_DEBRIS
    );

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!enabled()) return;
        Block block = event.getBlock();
        if (!SkylliaAPI.isWorldSkyblock(block.getWorld())) return;

        if (isOre(block.getType())) {
            Player player = event.getPlayer();
            ItemStack tool = player.getInventory().getItemInMainHand();
            if (!tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
                event.setDropItems(false);
            }
            event.setExpToDrop(0);
        }
    }

    /**
     * 防止熔炉/高炉熔炼矿物方块产出矿物。
     */
    @EventHandler(ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        if (!enabled()) return;
        if (!SkylliaAPI.isWorldSkyblock(event.getBlock().getWorld())) return;

        ItemStack source = event.getSource();
        if (isOre(source.getType())) {
            event.setCancelled(true);
        }
    }

    /**
     * 凋零破坏矿物方块时：方块被摧毁但不掉落任何物品。
     */
    @EventHandler(ignoreCancelled = true)
    public void onWitherBreakBlock(EntityChangeBlockEvent event) {
        if (!enabled()) return;
        if (event.getEntityType() != EntityType.WITHER) return;
        Block block = event.getBlock();
        if (!SkylliaAPI.isWorldSkyblock(block.getWorld())) return;
        if (isOre(block.getType())) {
            event.setCancelled(true);
            block.setType(Material.AIR, false);
        }
    }

    /**
     * 实体爆炸（TNT、苦力怕、凋零 skull 等）破坏矿物方块时：方块被摧毁但不掉落任何物品。
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!enabled()) return;
        if (!SkylliaAPI.isWorldSkyblock(event.getLocation().getWorld())) return;
        stripOres(event.blockList());
    }

    /**
     * 方块爆炸（床、重生锚等）破坏矿物方块时：方块被摧毁但不掉落任何物品。
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!enabled()) return;
        if (!SkylliaAPI.isWorldSkyblock(event.getBlock().getWorld())) return;
        stripOres(event.blockList());
    }

    /**
     * 把爆炸波及范围内的矿石就地抹成空气并移出掉落列表，其余方块保持原有爆炸行为。
     */
    private void stripOres(List<Block> blocks) {
        List<Block> remaining = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            if (isOre(block.getType())) {
                block.setType(Material.AIR, false);
            } else {
                remaining.add(block);
            }
        }
        blocks.clear();
        blocks.addAll(remaining);
    }

    /**
     * 本机制是否启用。实时读取配置，{@code /isadmin reload} 后立即生效。
     */
    private boolean enabled() {
        return ConfigLoader.general != null
                && ConfigLoader.general.getIslandSettings().preventOreDrops();
    }

    private boolean isOre(Material material) {
        return PREVENTED_ORES.contains(material);
    }
}
