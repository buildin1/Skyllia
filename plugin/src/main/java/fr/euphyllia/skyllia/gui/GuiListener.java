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
import org.jetbrains.annotations.NotNull;

/**
 * Skyllia GUI 全局事件监听。
 * <p>
 * 仅处理由 {@link SkylliaGuiHolder} 持有的 Inventory；其他 Inventory 直接放过。
 * 负责三件事：
 * <ol>
 *   <li>{@link InventoryClickEvent}：取消事件 + 按 slot 分发到绑定的回调</li>
 *   <li>{@link InventoryDragEvent}：取消拖拽（避免破坏菜单布局）</li>
 *   <li>{@link InventoryCloseEvent}：触发 onClose 清理回调</li>
 * </ol>
 * </p>
 */
public class GuiListener implements Listener {

    private static final Logger log = LogManager.getLogger(GuiListener.class);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();
        if (!(holder instanceof SkylliaGuiHolder guiHolder)) return;

        // 取消所有点击（玩家不能拿走菜单按钮）
        event.setCancelled(true);

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
        if (event.getInventory().getHolder() instanceof SkylliaGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof SkylliaGuiHolder guiHolder)) return;

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
