package fr.euphyllia.skyllia.gui;

import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局注册表，供 addon 往 Skyllia 主菜单挂自己的入口。
 * <p>
 * 典型用法（在 addon 的 {@code onEnable} 里）：
 * <pre>{@code
 * GuiExtensionRegistry.register(new GuiExtensionEntry(
 *         "skylliaupgrade:menu",
 *         Material.NETHER_STAR,
 *         "<light_purple>岛屿升级",
 *         List.of("<gray>扩大边境、提升成员上限"),
 *         player -> SkylliaAPI.getIslandByPlayerId(player.getUniqueId()) != null,
 *         player -> UpgradeGui.open(player)
 * ));
 * }</pre>
 * 并在 {@code onDisable} 里调用 {@link #unregister(String)} 避免残留引用。
 * </p>
 */
public final class GuiExtensionRegistry {

    private static final Map<String, GuiExtensionEntry> ENTRIES = new ConcurrentHashMap<>();

    private GuiExtensionRegistry() {}

    public static void register(@NotNull GuiExtensionEntry entry) {
        ENTRIES.put(entry.id(), entry);
    }

    public static void unregister(@NotNull String id) {
        ENTRIES.remove(id);
    }

    /** 由某个插件统一撤销自己所有以某前缀开头的入口（onDisable 兜底用） */
    public static void unregisterByPrefix(@NotNull String idPrefix) {
        ENTRIES.keySet().removeIf(id -> id.startsWith(idPrefix));
    }

    public static boolean isEmpty() {
        return ENTRIES.isEmpty();
    }

    /** 按 order 排序后的当前所有入口（快照） */
    public static @NotNull List<GuiExtensionEntry> entries() {
        return ENTRIES.values().stream()
                .sorted(Comparator.comparingInt(GuiExtensionEntry::order))
                .toList();
    }
}
