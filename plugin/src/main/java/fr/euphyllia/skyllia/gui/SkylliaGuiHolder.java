package fr.euphyllia.skyllia.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 所有 Skyllia GUI 的 InventoryHolder 基类。
 * <p>
 * 通过 {@link #clickActions} 槽位→回调映射，让 {@link GuiListener} 在收到
 * {@link InventoryClickEvent} 时按槽位分发到对应回调，菜单构建器无需自己处理事件。
 * </p>
 * <p>
 * 持有 {@link #returnTo} 引用用于「返回」按钮回到上级菜单；为 {@code null} 表示无上级。
 * </p>
 */
public final class SkylliaGuiHolder implements org.bukkit.inventory.InventoryHolder {

    /** GUI 类型标识，便于 listener 区分菜单 */
    public enum GuiType {
        MAIN,           // 主菜单
        CONFIRM,        // 二次确认
        INFO,           // 信息菜单
        POSITION,       // 位置设置
        SETTINGS,       // 空岛设置
        MEMBER,         // 成员管理
        VISITOR,        // 访客管理
        PERMISSION_ROOT,
        PERMISSION_CATEGORY,
        PERMISSION_LIST,
        FLAG_ROOT,
        FLAG_CATEGORY,
        FLAG_LIST,
        EXTENSION
    }

    private final GuiType type;
    /** 槽位 → 点击回调。回调在主线程（RegionScheduler 的 tick 线程）执行 */
    private final Map<Integer, ClickAction> clickActions = new ConcurrentHashMap<>();
    /** 返回上级菜单的回调（点击「返回」按钮触发）；为 null 表示无上级 */
    private Runnable returnTo;
    /** 关闭菜单后的清理回调（可选） */
    private Runnable onClose;
    /** 二次确认：要执行的危险操作 */
    private Runnable confirmAction;

    public SkylliaGuiHolder(@NotNull GuiType type) {
        this.type = type;
    }

    public GuiType getType() {
        return type;
    }

    public void bind(int slot, @NotNull ClickAction action) {
        clickActions.put(slot, action);
    }

    public void bindReturn(@NotNull Runnable returnTo) {
        this.returnTo = returnTo;
    }

    public void bindClose(@NotNull Runnable onClose) {
        this.onClose = onClose;
    }

    public void bindConfirm(@NotNull Runnable confirmAction) {
        this.confirmAction = confirmAction;
    }

    public Runnable getReturnTo() {
        return returnTo;
    }

    public Runnable getOnClose() {
        return onClose;
    }

    public Runnable getConfirmAction() {
        return confirmAction;
    }

    /**
     * 处理一次点击：返回该槽位绑定的回调，若未绑定返回 null。
     * 由 {@link GuiListener} 在事件触发时调用。
     */
    public ClickAction resolve(int slot) {
        return clickActions.get(slot);
    }

    @Override
    public @NotNull org.bukkit.inventory.Inventory getInventory() {
        // Bukkit 要求 InventoryHolder 提供此方法，但实际 Inventory 由菜单构建器创建并反向关联到本 holder
        // 这里返回一个空 inventory 作为占位（不会被使用）
        return org.bukkit.Bukkit.createInventory(this, 9);
    }

    /** 点击回调函数式接口 */
    @FunctionalInterface
    public interface ClickAction {
        /**
         * 处理点击事件。回调在主线程执行，可直接操作 Inventory/Player。
         * 若需调用 SkylliaAPI 异步操作（teleport/biome），自行调度 scheduler。
         *
         * @param event 触发的点击事件
         */
        void onClick(@NotNull InventoryClickEvent event);
    }
}
