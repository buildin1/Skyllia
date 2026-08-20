package fr.euphyllia.skyllia.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
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
        CREATE_ISLAND,  // 建岛类型选择
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
    /**
     * 「允许摆放物品」的编辑格槽位（比如 SkylliaTrader 订单编辑页用来摆材料的槽）。
     * <p>
     * 默认空集合——绝大多数 Skyllia GUI 都是纯展示型菜单，{@link GuiListener#onClick}/
     * {@link GuiListener#onDrag} 照旧取消一切点击/拖拽。只有显式调用
     * {@link #markEditableSlots(int...)} 标记过的槽位，点击/拖拽才会放行给原版处理
     * （放入/取出物品），且 {@link GuiListener#onClose} 会在菜单关闭时把这些槽位里
     * 当前的物品原样退还给玩家——不管关闭原因是什么（ESC、切物品栏、退出游戏、
     * 菜单内部触发的重开），这样"编辑型" GUI 才能安全地借用玩家背包物品来指定材料，
     * 同时保证物品不会丢失也不会被重复退还（退还只在关闭事件里发生一次）。
     * </p>
     */
    private final Set<Integer> editableSlots = ConcurrentHashMap.newKeySet();

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

    /** 标记若干槽位为「允许摆放物品」的编辑格，见 {@link #editableSlots} 的说明。 */
    public void markEditableSlots(int... slots) {
        for (int slot : slots) {
            editableSlots.add(slot);
        }
    }

    /** 该槽位是否被标记为编辑格。未标记过任何编辑格的菜单（绝大多数）恒为 false。 */
    public boolean isEditableSlot(int slot) {
        return editableSlots.contains(slot);
    }

    /** 当前菜单全部编辑格槽位；供 {@link GuiListener#onClose} 关闭时扫描退还物品用。 */
    public @NotNull Set<Integer> getEditableSlots() {
        return Collections.unmodifiableSet(editableSlots);
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
