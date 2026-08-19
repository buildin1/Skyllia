package fr.euphyllia.skyllia.configuration.manager;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 建岛选择菜单的外观配置（{@code config/island-types.toml}）。
 *
 * <p>菜单里每一项对应 {@code schematics.toml} 中的一个岛屿类型 id。首次启动时会按现有的
 * 岛屿类型自动补全条目，因此服主无需手写；之后可自由修改图标、名称、描述与排序。</p>
 *
 * <p>没有在本文件中列出的类型<b>依然可以</b>通过 {@code /is create <id>} 创建，
 * 只是不会出现在菜单里 —— 这让服主能做出「隐藏类型」。</p>
 */
public class IslandTypeGuiConfigManager implements IConfigurationProvider {

    private static final Logger log = LogManager.getLogger(IslandTypeGuiConfigManager.class);

    private final CommentedFileConfig config;
    private final List<IslandTypeEntry> entries = new ArrayList<>();

    public IslandTypeGuiConfigManager(CommentedFileConfig config) {
        this.config = config;
        loadConfig();
    }

    /**
     * 按 {@code schematics.toml} 里已存在的岛屿类型补全缺失条目。
     * 由 {@link fr.euphyllia.skyllia.configuration.ConfigLoader} 在 schematic 管理器就绪后调用。
     */
    public void seedFrom(@NotNull List<String> islandTypes) {
        int order = 1;
        boolean added = false;
        for (String type : islandTypes) {
            if (config.get(type) == null) {
                CommentedConfig node = config.createSubConfig();
                node.set("display-name", "<!italic><green>" + type);
                node.set("icon", "GRASS_BLOCK");
                node.set("lore", List.of("<gray>岛屿类型：" + type, "<dark_gray>点击创建"));
                node.set("order", order);
                config.set(type, node);
                added = true;
            }
            order++;
        }
        if (added) loadConfig();
    }

    @Override
    public void loadConfig() {
        entries.clear();

        for (String key : config.valueMap().keySet()) {
            Object value = config.valueMap().get(key);
            if (!(value instanceof CommentedConfig node)) {
                log.warn("[Skyllia] island-types.toml 中的 '{}' 不是配置段，已跳过", key);
                continue;
            }

            String iconName = node.getOrElse("icon", "GRASS_BLOCK");
            Material icon = Material.matchMaterial(iconName);
            if (icon == null || !icon.isItem()) {
                log.warn("[Skyllia] island-types.toml 中 '{}' 的图标 '{}' 不是合法物品，已回退为 GRASS_BLOCK",
                        key, iconName);
                icon = Material.GRASS_BLOCK;
            }

            List<String> lore = new ArrayList<>();
            Object rawLore = node.get("lore");
            if (rawLore instanceof List<?> list) {
                for (Object line : list) {
                    if (line != null) lore.add(String.valueOf(line));
                }
            }

            entries.add(new IslandTypeEntry(
                    key,
                    node.getOrElse("display-name", "<!italic><green>" + key),
                    icon,
                    lore,
                    node.getOrElse("order", Integer.MAX_VALUE)
            ));
        }

        entries.sort(Comparator.comparingInt(IslandTypeEntry::order).thenComparing(IslandTypeEntry::id));
    }

    /**
     * 菜单中要展示的岛屿类型，已按 {@code order} 排序。
     */
    public @NotNull List<IslandTypeEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    @Override
    public void reloadFromDisk() {
        config.load();
    }

    @Override
    public boolean canReloadFromDisk() {
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOrSetDefault(String path, T defaultValue, Class<T> expectedClass) {
        Object value = config.get(path);
        if (value == null) {
            if (defaultValue == null) return null;
            config.set(path, defaultValue);
            return defaultValue;
        }
        if (!expectedClass.isInstance(value)) {
            log.warn("[Skyllia] island-types.toml 的 '{}' 类型不符（期望 {}），已回退默认值",
                    path, expectedClass.getSimpleName());
            return defaultValue;
        }
        return (T) value;
    }

    /**
     * @param id          岛屿类型 id，与 {@code schematics.toml} 一致，也是 {@code /is create <id>} 的参数
     * @param displayName MiniMessage 格式的菜单显示名
     * @param icon        菜单图标
     * @param lore        MiniMessage 格式的描述行
     * @param order       排序权重，越小越靠前
     */
    public record IslandTypeEntry(@NotNull String id,
                                  @NotNull String displayName,
                                  @NotNull Material icon,
                                  @NotNull List<String> lore,
                                  int order) {
    }
}
