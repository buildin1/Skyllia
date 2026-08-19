package fr.euphyllia.skyllia.gui.layout;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个菜单的外观配置（{@code gui/<menuId>.toml}）。
 *
 * <p>内置默认值由代码提供，首次加载时写入文件 —— 文件因此是自解释的，服主打开就能看到
 * 全部可调项，不需要另配一份文档。</p>
 *
 * <p><b>容错优先</b>：任何一项配错（图标不是合法物品、槽位越界、尺寸不是 9 的倍数）
 * 都只打一条警告并回退到默认值，绝不让菜单打不开。GUI 是玩家进服后的主要入口，
 * 因配置手滑而整个菜单炸掉是不可接受的。</p>
 */
public class GuiLayoutConfigManager implements IConfigurationProvider {

    private static final Logger log = LogManager.getLogger(GuiLayoutConfigManager.class);

    private final CommentedFileConfig config;
    private final GuiLayout defaults;
    private GuiLayout layout;

    public GuiLayoutConfigManager(@NotNull CommentedFileConfig config, @NotNull GuiLayout defaults) {
        this.config = config;
        this.defaults = defaults;
        loadConfig();
    }

    public @NotNull GuiLayout getLayout() {
        return layout;
    }

    @Override
    public void loadConfig() {
        config.setComment("title",
                " 菜单外观配置。改完执行 /isadmin reload 即生效，无需重启。\n"
                        + " 槽位 slot 设为 -1 可隐藏该按钮；按钮的点击行为固定在代码里，不受本文件影响。");

        String title = getOrSetDefault("title", defaults.title(), String.class);
        int size = normalizeSize(getOrSetDefault("size", defaults.size(), Integer.class));

        Material fillerIcon = material(getOrSetDefault("filler.icon", "PURPLE_STAINED_GLASS_PANE", String.class),
                Material.PURPLE_STAINED_GLASS_PANE, "filler.icon");
        String fillerName = getOrSetDefault("filler.name", "<!italic><dark_gray> ", String.class);
        List<Integer> fillerSlots = intList("filler.slots", defaults.fillerSlots(), size);

        GuiLayout.LoreTemplate tpl = defaults.loreTemplate();
        GuiLayout.LoreTemplate loreTemplate = new GuiLayout.LoreTemplate(
                getOrSetDefault("lore-template.separator", tpl.separator(), String.class),
                getOrSetDefault("lore-template.description", tpl.descriptionFormat(), String.class),
                getOrSetDefault("lore-template.hint", tpl.hintFormat(), String.class),
                getOrSetDefault("lore-template.disabled", tpl.disabledText(), String.class),
                getOrSetDefault("lore-template.extra-hint", tpl.extraHintFormat(), String.class)
        );

        Map<String, GuiButtonDef> buttons = new LinkedHashMap<>();
        for (Map.Entry<String, GuiButtonDef> entry : defaults.buttons().entrySet()) {
            String key = entry.getKey();
            GuiButtonDef def = entry.getValue();
            String base = "buttons." + key + ".";

            int slot = getOrSetDefault(base + "slot", def.slot(), Integer.class);
            if (slot >= size) {
                log.warn("[Skyllia-GUI] {}.toml 中按钮 '{}' 的槽位 {} 超出菜单尺寸 {}，已回退为默认槽位 {}",
                        defaults.menuId(), key, slot, size, def.slot());
                slot = def.slot();
            }

            Material icon = material(getOrSetDefault(base + "icon", def.icon().name(), String.class),
                    def.icon(), defaults.menuId() + ".toml 的 buttons." + key + ".icon");

            buttons.put(key, new GuiButtonDef(
                    slot,
                    icon,
                    getOrSetDefault(base + "name", def.name(), String.class),
                    getOrSetDefault(base + "description", def.description(), String.class),
                    getOrSetDefault(base + "hint", def.hint(), String.class),
                    def.lore() == null ? null : stringList(base + "lore", def.lore())
            ));
        }

        this.layout = new GuiLayout(defaults.menuId(), title, size, fillerIcon, fillerName,
                fillerSlots, buttons, loreTemplate);
    }

    /** 箱子界面尺寸必须是 9 的倍数且不超过 54。 */
    private int normalizeSize(int raw) {
        if (raw > 0 && raw <= 54 && raw % 9 == 0) return raw;
        log.warn("[Skyllia-GUI] {}.toml 的 size={} 不是 9 的倍数或超出 54，已回退为 {}",
                defaults.menuId(), raw, defaults.size());
        return defaults.size();
    }

    private Material material(String raw, Material fallback, String where) {
        Material m = Material.matchMaterial(raw);
        if (m == null || !m.isItem()) {
            log.warn("[Skyllia-GUI] {} 的 '{}' 不是合法物品，已回退为 {}", where, raw, fallback);
            return fallback;
        }
        return m;
    }

    private List<Integer> intList(String path, List<Integer> fallback, int size) {
        Object raw = config.get(path);
        if (raw == null) {
            config.set(path, fallback);
            return fallback;
        }
        if (!(raw instanceof List<?> list)) {
            log.warn("[Skyllia-GUI] {}.toml 的 {} 不是数组，已回退默认值", defaults.menuId(), path);
            return fallback;
        }
        List<Integer> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Number n) {
                int v = n.intValue();
                if (v >= 0 && v < size) out.add(v);
            }
        }
        return out;
    }

    private List<String> stringList(String path, List<String> fallback) {
        Object raw = config.get(path);
        if (raw == null) {
            config.set(path, fallback);
            return fallback;
        }
        if (!(raw instanceof List<?> list)) return fallback;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out.isEmpty() ? fallback : out;
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
        // night-config 读 TOML 整数可能给出 Long，这里统一收敛到 Integer。
        if (expectedClass == Integer.class && value instanceof Number n) {
            return (T) Integer.valueOf(n.intValue());
        }
        if (!expectedClass.isInstance(value)) {
            log.warn("[Skyllia-GUI] {}.toml 的 '{}' 类型不符（期望 {}），已回退默认值",
                    defaults.menuId(), path, expectedClass.getSimpleName());
            return defaultValue;
        }
        return (T) value;
    }
}
