package fr.euphyllia.skyllia.gui.layout;

import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个菜单的完整外观定义：标题、尺寸、边框、各按钮，以及 lore 的排版模板。
 *
 * <p>菜单构建代码只需要说「把 <code>info</code> 按钮放上去，可用状态是 X，点击执行 Y」，
 * 槽位 / 图标 / 文案全部由本对象提供，从而做到外观可配置而行为不可篡改。</p>
 */
public final class GuiLayout {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final String menuId;
    private final String title;
    private final int size;
    private final Material fillerIcon;
    private final String fillerName;
    private final List<Integer> fillerSlots;
    private final Map<String, GuiButtonDef> buttons;
    private final LoreTemplate loreTemplate;

    public GuiLayout(@NotNull String menuId,
                     @NotNull String title,
                     int size,
                     @NotNull Material fillerIcon,
                     @NotNull String fillerName,
                     @NotNull List<Integer> fillerSlots,
                     @NotNull Map<String, GuiButtonDef> buttons,
                     @NotNull LoreTemplate loreTemplate) {
        this.menuId = menuId;
        this.title = title;
        this.size = size;
        this.fillerIcon = fillerIcon;
        this.fillerName = fillerName;
        this.fillerSlots = List.copyOf(fillerSlots);
        this.buttons = new LinkedHashMap<>(buttons);
        this.loreTemplate = loreTemplate;
    }

    public @NotNull String menuId() {
        return menuId;
    }

    public @NotNull String title() {
        return title;
    }

    public int size() {
        return size;
    }

    public @NotNull Map<String, GuiButtonDef> buttons() {
        return buttons;
    }

    public @NotNull LoreTemplate loreTemplate() {
        return loreTemplate;
    }

    public @NotNull List<Integer> fillerSlots() {
        return fillerSlots;
    }

    public @Nullable GuiButtonDef button(@NotNull String key) {
        return buttons.get(key);
    }

    /** 反序列化标题，供 {@code Bukkit.createInventory} 使用。 */
    public net.kyori.adventure.text.@NotNull Component titleComponent() {
        return MM.deserialize(title);
    }

    /** 按配置铺设边框装饰。 */
    public void paintFiller(@NotNull Inventory inv) {
        for (int slot : fillerSlots) {
            if (slot >= 0 && slot < inv.getSize()) {
                inv.setItem(slot, GuiItem.of(fillerIcon, fillerName));
            }
        }
    }

    /**
     * 放置一个按钮并绑定点击回调。
     *
     * @param enabled   按钮是否可用；不可用时 lore 末行换成模板里的禁用文案，且<b>不绑定回调</b>
     * @param extraHint 额外提示行（如「仅岛主可操作」），为空则不加
     * @param action    点击回调；{@code null} 表示纯展示按钮
     */
    public void place(@NotNull Inventory inv,
                      @NotNull SkylliaGuiHolder holder,
                      @NotNull String key,
                      boolean enabled,
                      @Nullable String extraHint,
                      @Nullable SkylliaGuiHolder.ClickAction action) {
        GuiButtonDef def = buttons.get(key);
        if (def == null || def.hidden()) return;
        if (def.slot() >= inv.getSize()) return;

        inv.setItem(def.slot(), GuiItem.of(def.icon(), def.name(), buildLore(def, enabled, extraHint)));
        if (action != null) {
            holder.bind(def.slot(), action);
        }
    }

    /** 与 {@link #place} 相同，但按钮恒为可用状态。 */
    public void place(@NotNull Inventory inv,
                      @NotNull SkylliaGuiHolder holder,
                      @NotNull String key,
                      @Nullable SkylliaGuiHolder.ClickAction action) {
        place(inv, holder, key, true, null, action);
    }

    private @NotNull List<String> buildLore(@NotNull GuiButtonDef def, boolean enabled, @Nullable String extraHint) {
        // 显式 lore 覆盖：整段照抄，模板与启用状态一律不参与。
        if (def.lore() != null && !def.lore().isEmpty()) {
            return new ArrayList<>(def.lore());
        }

        List<String> lore = new ArrayList<>(5);
        lore.add(loreTemplate.separator());
        if (!def.description().isEmpty()) {
            lore.add(loreTemplate.descriptionFormat().replace("{value}", def.description()));
        }
        lore.add(loreTemplate.separator());
        if (extraHint != null && !extraHint.isEmpty()) {
            lore.add(loreTemplate.extraHintFormat().replace("{value}", extraHint));
        }
        lore.add(enabled
                ? loreTemplate.hintFormat().replace("{value}", def.hint())
                : loreTemplate.disabledText());
        return lore;
    }

    /**
     * lore 的排版模板。{@code {value}} 是占位符。
     *
     * @param separator       分隔线
     * @param descriptionFormat 功能描述行
     * @param hintFormat      操作提示行（可用时）
     * @param disabledText    禁用时替代操作提示的整行文本
     * @param extraHintFormat 额外提示行（如权限不足）
     */
    public record LoreTemplate(@NotNull String separator,
                               @NotNull String descriptionFormat,
                               @NotNull String hintFormat,
                               @NotNull String disabledText,
                               @NotNull String extraHintFormat) {

        public static LoreTemplate defaults() {
            return new LoreTemplate(
                    "<dark_gray>─────────",
                    "<gray>{value}</gray>",
                    "<yellow>左键</yellow> <gray>{value}</gray>",
                    "<dark_gray>需先创建空岛</dark_gray>",
                    "<dark_gray>{value}</dark_gray>"
            );
        }
    }
}
