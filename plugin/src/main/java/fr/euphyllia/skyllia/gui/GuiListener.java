package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/**
 * Skyllia GUI 全局事件监听。
 * <p>
 * 仅处理由 {@link SkylliaGuiHolder} 持有的 Inventory；其他 Inventory 直接放过。
 * 负责三件事：
 * <ol>
 *   <li>{@link InventoryClickEvent}：取消事件 + 按 slot 分发到绑定的回调</li>
 *   <li>{@link InventoryDragEvent}：取消拖拽（避免破坏菜单布局）</li>
 *   <li>{@link InventoryCloseEvent}：退还「编辑格」里的物品 + 触发 onClose 清理回调</li>
 * </ol>
 * </p>
 * <p>
 * <b>T3 第二波新增</b>：绝大多数 Skyllia GUI 是纯展示型菜单，点击/拖拽一律取消——
 * 这是默认行为，未调用过 {@link SkylliaGuiHolder#markEditableSlots(int...)} 的菜单
 * 完全不受下面这段逻辑影响。少数「编辑型」菜单（比如 SkylliaTrader 订单编辑页用物品
 * 摆放来指定材料）会显式标记一批「编辑格」槽位，本类据此放行这些槽位上的点击/拖拽，
 * 并在菜单关闭时把这些槽位里当前的物品退还给玩家——不管关闭原因是什么（ESC、切物品栏、
 * 退出游戏、菜单内部触发的换页/重开），退还这一步只在 {@link InventoryCloseEvent} 里
 * 发生，而且这个事件本身就在这个玩家自己的 region 线程上同步触发，直接操作背包/世界
 * 是安全的，不需要再跳线程。是否要把这批物品的材料写进配置，由各编辑型菜单自己在点击
 * 「确认」时读取（只读，不清空）这些槽位来决定；退还永远发生、且只发生这一次，
 * 写配置与退还物品因此天然互斥，不会重复也不会遗漏。
 * </p>
 */
public class GuiListener implements Listener {

    private static final Logger log = LogManager.getLogger(GuiListener.class);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();
        if (!(holder instanceof SkylliaGuiHolder guiHolder)) return;

        // 「编辑格」放行给原版处理（放入/取出物品）；其余槽位维持原有的"全部取消"，
        // 玩家拿不走任何菜单按钮。绝大多数菜单没有标记任何编辑格，这里恒为 false。
        if (!guiHolder.isEditableSlot(event.getRawSlot())) {
            event.setCancelled(true);
        }

        // 只处理 GUI 内点击，忽略 shift-click 从玩家背包塞入的情况
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(inv)) {
            return;
        }

        int slot = event.getRawSlot();
        SkylliaGuiHolder.ClickAction action = guiHolder.resolve(slot);
        if (action == null) return;

        // 异步执行回调（Folia 下 InventoryClickEvent 在 region tick 线程触发，
        // 但回调可能调用 SkylliaAPI 的异步操作；统一调度避免阻塞 tick）
        // 注意：多数操作（如 performCommand）本身会调度到 async，直接同步执行即可
        try {
            action.onClick(event);
        } catch (Exception e) {
            log.error("[Skyllia-GUI] 点击回调异常 slot={} type={}", slot, guiHolder.getType(), e);
            if (event.getWhoClicked() instanceof Player p) {
                p.sendMessage(net.kyori.adventure.text.Component.text(
                        "§c菜单操作失败，请查看控制台日志。"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof SkylliaGuiHolder guiHolder)) return;

        // 没有标记过任何编辑格的菜单（绝大多数）维持原有的"一律取消"，行为完全不变。
        if (guiHolder.getEditableSlots().isEmpty()) {
            event.setCancelled(true);
            return;
        }

        // 编辑型菜单：拖拽涉及的槽位必须整体落在编辑格里才放行（允许"一组物品拖开摊到
        // 多个材料槽"这种类似箱子的操作）；只要有一个槽位不是编辑格（比如拖到了边框/
        // 按钮上），整个拖拽取消，避免破坏菜单布局。
        for (int rawSlot : event.getRawSlots()) {
            if (!guiHolder.isEditableSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof SkylliaGuiHolder guiHolder)) return;

        // 编辑格退还：不管菜单为什么关闭，只要标记过编辑格，就把里面当前的物品原样还给玩家。
        // 这一步必须同步做在这里（不能像下面 onClose 回调那样延迟 1 tick 到 GlobalRegionScheduler
        // 上）——InventoryCloseEvent 本身已经在这个玩家自己的 region 线程上触发，直接操作
        // 他的背包/所在世界是安全的；GlobalRegionScheduler 不属于任何具体 region，
        // 在那上面碰玩家背包/世界会违反 Folia 的线程模型。
        Set<Integer> editableSlots = guiHolder.getEditableSlots();
        if (!editableSlots.isEmpty() && event.getPlayer() instanceof Player player) {
            for (int slot : editableSlots) {
                ItemStack stack = event.getInventory().getItem(slot);
                if (stack == null || stack.getType().isAir()) continue;
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack.clone());
                for (ItemStack overflow : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow);
                }
                event.getInventory().setItem(slot, null);
            }
        }

        Runnable onClose = guiHolder.getOnClose();
        if (onClose != null) {
            // 延迟 1 tick 执行清理，避免在 InventoryCloseEvent 中再次打开菜单时冲突
            Bukkit.getGlobalRegionScheduler().runDelayed(Skyllia.getInstance(), t -> {
                try {
                    onClose.run();
                } catch (Exception e) {
                    log.error("[Skyllia-GUI] onClose 回调异常", e);
                }
            }, 1L);
        }
    }

    /** 调试用：把 Adventure Component 序列化成纯文本用于日志 */
    @SuppressWarnings("unused")
    private static String toPlain(@NotNull net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
