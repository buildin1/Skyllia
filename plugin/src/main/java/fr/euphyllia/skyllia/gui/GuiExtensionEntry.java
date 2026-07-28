package fr.euphyllia.skyllia.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 一个 addon 向 Skyllia 主菜单注册的扩展入口。
 * <p>
 * addon 通过 {@link GuiExtensionRegistry#register(GuiExtensionEntry)} 注册后，
 * 该入口会出现在主菜单的「扩展功能」子菜单里，点击后交由 addon 自己的
 * {@link #onClick()} 回调打开它自己的 GUI（可以直接复用 {@link SkylliaGuiHolder}/
 * {@link GuiItem}，{@link GuiListener} 会自动处理点击分发，无需 addon 自己监听事件）。
 * </p>
 *
 * @param id          全局唯一 id，建议用 "插件名:功能名" 命名空间格式，如 "skylliaupgrade:menu"
 * @param icon        在扩展列表里显示的图标
 * @param displayName MiniMessage 格式的显示名
 * @param lore        MiniMessage 格式的多行描述
 * @param order       排序权重，数值越小越靠前，相同则按注册顺序
 * @param visible     是否对该玩家显示此入口（例如需要先创建空岛才显示），为 null 视为恒真
 * @param onClick     点击后的回调，负责打开 addon 自己的菜单
 */
public record GuiExtensionEntry(
        @NotNull String id,
        @NotNull Material icon,
        @NotNull String displayName,
        @NotNull List<String> lore,
        int order,
        @Nullable Predicate<Player> visible,
        @NotNull Consumer<Player> onClick
) {

    public GuiExtensionEntry {
        if (id.isBlank()) throw new IllegalArgumentException("GuiExtensionEntry id must not be blank");
    }

    public GuiExtensionEntry(@NotNull String id, @NotNull Material icon, @NotNull String displayName,
                              @NotNull List<String> lore, @NotNull Consumer<Player> onClick) {
        this(id, icon, displayName, lore, 0, null, onClick);
    }

    public boolean isVisibleFor(@NotNull Player player) {
        return visible == null || visible.test(player);
    }
}
