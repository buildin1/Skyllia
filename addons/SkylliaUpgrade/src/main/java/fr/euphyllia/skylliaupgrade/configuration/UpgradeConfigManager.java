package fr.euphyllia.skylliaupgrade.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlWriter;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import org.bukkit.Material;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 岛屿升级系统的配置：每级的边境半径/成员上限/门槛/成本，以及领地拓展令物品外观。
 * <p>
 * 首次加载时会按 {@link #defaultForLevel(int)} 的曲线自动生成默认值并写回文件
 * （前 5 级新手友好，之后成本陡增）；此后所有数值都可以通过管理员 GUI
 * （{@code UpgradeAdminGui}）单独编辑，编辑会立即持久化到本文件。
 * </p>
 */
public class UpgradeConfigManager implements IConfigurationProvider {

    private static final Logger log = LoggerFactory.getLogger(UpgradeConfigManager.class);

    private final CommentedFileConfig config;
    private boolean changed = false;

    private int maxLevel = 20;
    private Material tokenMaterial = Material.NAME_TAG;
    private int tokenCustomModelData = 900100;
    private String tokenDisplayName = "<light_purple>✦ 领地拓展令";
    private List<String> tokenLore = List.of(
            "<gray>用于扩大岛屿边境",
            "<gray>只能通过完成空岛挑战获得"
    );

    private final Map<Integer, UpgradeLevelDefinition> levels = new LinkedHashMap<>();

    public UpgradeConfigManager(CommentedFileConfig config) {
        this.config = config;
    }

    @Override
    public void loadConfig() throws Exception {
        changed = false;

        this.maxLevel = getOrSetDefault("general.max-level", maxLevel, Integer.class);

        String materialName = getOrSetDefault("token-item.material", tokenMaterial.name(), String.class);
        this.tokenMaterial = parseMaterialSafe(materialName, Material.NAME_TAG);
        this.tokenCustomModelData = getOrSetDefault("token-item.custom-model-data", tokenCustomModelData, Integer.class);
        this.tokenDisplayName = getOrSetDefault("token-item.display-name", tokenDisplayName, String.class);
        this.tokenLore = getOrSetDefaultStringList("token-item.lore", tokenLore);

        levels.clear();
        for (int n = 1; n <= maxLevel; n++) {
            levels.put(n, loadLevel(n));
        }

        if (changed) {
            persistNow();
        }
    }

    private UpgradeLevelDefinition loadLevel(int n) {
        String prefix = "levels." + n + ".";
        UpgradeLevelDefinition seed = defaultForLevel(n);

        double size = getOrSetDefault(prefix + "size", seed.size(), Double.class);
        int maxMembers = getOrSetDefault(prefix + "max-members", seed.maxMembers(), Integer.class);
        double scoreThreshold = getOrSetDefault(prefix + "score-threshold", seed.scoreThreshold(), Double.class);
        int tokenCount = getOrSetDefault(prefix + "token-count", seed.tokenCount(), Integer.class);
        List<String> materialsRaw = getOrSetDefaultStringList(prefix + "materials",
                seed.materials().stream().map(MaterialCost::serialize).toList());

        List<MaterialCost> materials = new ArrayList<>();
        for (String raw : materialsRaw) {
            try {
                materials.add(MaterialCost.parse(raw));
            } catch (Exception e) {
                log.warn("[SkylliaUpgrade] 跳过无效的材料配置 '{}' (等级 {}): {}", raw, n, e.getMessage());
            }
        }

        return new UpgradeLevelDefinition(n, size, maxMembers, scoreThreshold, tokenCount, materials);
    }

    /** 前 5 级新手友好（低门槛/低成本），之后曲线陡增。仅在配置文件里缺该字段时用作初始种子值。 */
    private UpgradeLevelDefinition defaultForLevel(int n) {
        if (n <= 5) {
            double size = 50 + n * 10.0;
            int maxMembers = 6 + n;
            double scoreThreshold = 200.0 * n;
            List<MaterialCost> materials = List.of(new MaterialCost(Material.COBBLESTONE, 64 * n));
            return new UpgradeLevelDefinition(n, size, maxMembers, scoreThreshold, 1, materials);
        }
        int step = n - 5;
        double size = 100 + step * 15.0;
        int maxMembers = Math.min(20, 11 + step);
        double scoreThreshold = 1000.0 + step * 800.0;
        List<MaterialCost> materials = new ArrayList<>();
        materials.add(new MaterialCost(Material.IRON_BLOCK, 8 * step));
        if (n > 10) materials.add(new MaterialCost(Material.DIAMOND, (n - 10) * 2));
        return new UpgradeLevelDefinition(n, size, maxMembers, scoreThreshold, 1, materials);
    }

    private static Material parseMaterialSafe(String name, Material fallback) {
        try {
            return Material.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            log.warn("[SkylliaUpgrade] 无效的 token-item.material '{}'，回退为 {}", name, fallback);
            return fallback;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  管理员 GUI 写入接口：改一个字段立即持久化
    // ═══════════════════════════════════════════════════════════

    public void setLevelSize(int level, double size) {
        setLevelField(level, "size", size);
    }

    public void setLevelMaxMembers(int level, int maxMembers) {
        setLevelField(level, "max-members", maxMembers);
    }

    public void setLevelScoreThreshold(int level, double threshold) {
        setLevelField(level, "score-threshold", threshold);
    }

    public void setLevelTokenCount(int level, int count) {
        setLevelField(level, "token-count", count);
    }

    public void setLevelMaterials(int level, List<MaterialCost> materials) {
        config.set("levels." + level + ".materials", materials.stream().map(MaterialCost::serialize).toList());
        persistNow();
        levels.put(level, loadLevel(level));
    }

    private void setLevelField(int level, String key, Object value) {
        config.set("levels." + level + "." + key, value);
        persistNow();
        levels.put(level, loadLevel(level));
    }

    private void persistNow() {
        TomlWriter tomlWriter = new TomlWriter();
        tomlWriter.setIndent(IndentStyle.NONE);
        tomlWriter.write(config, config.getFile(), WritingMode.REPLACE);
    }

    // ═══════════════════════════════════════════════════════════
    //  读取接口
    // ═══════════════════════════════════════════════════════════

    public int getMaxLevel() {
        return maxLevel;
    }

    public UpgradeLevelDefinition getLevel(int level) {
        return levels.get(level);
    }

    public List<UpgradeLevelDefinition> getAllLevels() {
        return new ArrayList<>(levels.values());
    }

    public Material getTokenMaterial() {
        return tokenMaterial;
    }

    public int getTokenCustomModelData() {
        return tokenCustomModelData;
    }

    public String getTokenDisplayName() {
        return tokenDisplayName;
    }

    public List<String> getTokenLore() {
        return tokenLore;
    }

    // ═══════════════════════════════════════════════════════════
    //  IConfigurationProvider
    // ═══════════════════════════════════════════════════════════

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrSetDefault(String path, T defaultValue, Class<T> expectedClass) {
        Object value = config.get(path);
        if (value == null) {
            config.set(path, defaultValue);
            changed = true;
            return defaultValue;
        }
        if (expectedClass.isInstance(value)) return (T) value;
        return switch (value) {
            case Integer i when expectedClass == Long.class -> (T) Long.valueOf(i);
            case Double d when expectedClass == Float.class -> (T) Float.valueOf(d.floatValue());
            case Integer i when expectedClass == Double.class -> (T) Double.valueOf(i);
            default -> throw new IllegalStateException("Cannot convert value at '" + path + "' from "
                    + value.getClass().getSimpleName() + " to " + expectedClass.getSimpleName());
        };
    }

    private List<String> getOrSetDefaultStringList(String path, List<String> defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            config.set(path, defaultValue);
            changed = true;
            return defaultValue;
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object o : list) result.add(String.valueOf(o));
            return result;
        }
        return defaultValue;
    }

    @Override
    public void reloadFromDisk() {
        config.load();
        try {
            loadConfig();
        } catch (Exception e) {
            log.error("[SkylliaUpgrade] 重新加载配置失败", e);
        }
    }

    @Override
    public boolean canReloadFromDisk() {
        return true;
    }
}
