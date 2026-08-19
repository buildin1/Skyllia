package fr.euphyllia.skyllia.gui.layout;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 一个可配置按钮的外观定义。
 *
 * <p><b>只描述长什么样、放在哪，不描述点了做什么。</b>点击行为始终留在代码里，
 * 通过按钮 id 绑定 —— 这样服主可以随意挪动槽位、换图标、改文案，而不可能把某个
 * 按钮配成执行别的动作。</p>
 *
 * @param slot        所在槽位；-1 表示「隐藏该按钮」
 * @param icon        图标材质
 * @param name        MiniMessage 格式的显示名
 * @param description lore 中的功能描述行
 * @param hint        lore 中的操作提示行（按钮可用时显示）
 * @param lore        完整 lore 覆盖；非空时忽略 {@code description} / {@code hint} 与模板，直接逐行使用
 */
public record GuiButtonDef(int slot,
                           @NotNull Material icon,
                           @NotNull String name,
                           @NotNull String description,
                           @NotNull String hint,
                           @Nullable List<String> lore) {

    /** 槽位为 -1 表示服主刻意隐藏了这个按钮。 */
    public boolean hidden() {
        return slot < 0;
    }

    public GuiButtonDef withSlot(int newSlot) {
        return new GuiButtonDef(newSlot, icon, name, description, hint, lore);
    }
}
