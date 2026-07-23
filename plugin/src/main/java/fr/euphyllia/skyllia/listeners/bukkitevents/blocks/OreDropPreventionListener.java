package fr.euphyllia.skyllia.listeners.bukkitevents.blocks;

import fr.euphyllia.skyllia.api.SkylliaAPI;
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
import java.util.List;

public class OreDropPreventionListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
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
        if (!SkylliaAPI.isWorldSkyblock(event.getLocation().getWorld())) return;

        List<Block> remaining = new ArrayList<>();
        for (Block block : event.blockList()) {
            if (isOre(block.getType())) {
                block.setType(Material.AIR, false);
            } else {
                remaining.add(block);
            }
        }
        event.blockList().clear();
        event.blockList().addAll(remaining);
    }

    /**
     * 方块爆炸（床、重生锚等）破坏矿物方块时：方块被摧毁但不掉落任何物品。
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!SkylliaAPI.isWorldSkyblock(event.getBlock().getWorld())) return;

        List<Block> remaining = new ArrayList<>();
        for (Block block : event.blockList()) {
            if (isOre(block.getType())) {
                block.setType(Material.AIR, false);
            } else {
                remaining.add(block);
            }
        }
        event.blockList().clear();
        event.blockList().addAll(remaining);
    }

    /**
     * 判断材质是否为矿物方块（以 _ORE 结尾）。
     */
    private boolean isOre(Material material) {
        String name = material.name();
        return name.endsWith("_ORE");
    }
}
