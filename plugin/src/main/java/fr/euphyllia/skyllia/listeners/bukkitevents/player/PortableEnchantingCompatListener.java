package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

/**
 * 随身附魔台兼容（2026-09 玩家反馈：买了随身附魔台附魔不了）。
 * <p>
 * 有的插件用 {@code Bukkit.createInventory(..., InventoryType.ENCHANTING)} 当随身附魔台，
 * 这种界面只是个空壳（CraftInventoryCustom），没有附魔台菜单的逻辑，放进物品和青金石也不会出附魔选项。
 * 真正的附魔台界面——包括 {@code Player#openEnchanting(null, true)} 打开的虚拟附魔台——背后都有坐标，
 * {@code getLocation()} 不为 null；空壳界面的 {@code getLocation()} 永远是 null。
 * </p>
 * <p>
 * 这里只拦空壳：取消打开，在玩家脚下强制打开一个真正的附魔台界面（周围没书架就是最高 8 级，
 * 和常见的随身附魔台实现一致）。重新打开的是真附魔台，有坐标，不会再被这里拦下，不会循环。
 * </p>
 */
public class PortableEnchantingCompatListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (top.getType() != InventoryType.ENCHANTING || top.getLocation() != null) return;

        event.setCancelled(true);
        player.getScheduler().run(SkylliaAPI.getPlugin(), task -> player.openEnchanting(null, true), null);
    }
}
