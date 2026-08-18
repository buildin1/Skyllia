package fr.euphyllia.skyllia.configuration.manager;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlWriter;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.PermissionSet;
import fr.euphyllia.skyllia.api.permissions.PermissionSetCodec;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public class PermissionsV2ConfigManager implements IConfigurationProvider {

    private static final Logger log = LoggerFactory.getLogger(PermissionsV2ConfigManager.class);

    /**
     * TOML 顶层区块名：全局接管层，形如 {@code [global-override.MEMBER]}。
     * <p>
     * 与 {@code [defaults]} 的区别见 {@link IslandFlagsConfigManager} 同名常量的说明：
     * {@code [defaults]} 只在建岛那一刻生成岛屿位图，{@code [global-override]} 则在每次
     * 权限判定时实时生效，改完立即作用于全服所有岛屿（含老岛），不碰数据库、不用重启。
     * </p>
     */
    private static final String GLOBAL_OVERRIDE = "global-override";

    private final CommentedFileConfig config;

    // 注意：islandType 可能是 null（代表全局默认），不可变 Map 的 get(null) 会抛 NPE，
    // 所以这里必须保持 HashMap。整体替换的语义见下方 loadConfig()。
    private volatile Map<String, EnumMap<RoleType, Map<PermissionId, Boolean>>> compiledDefaults = new HashMap<>();

    /**
     * 全局接管层：角色 -> 权限 -> 强制值。
     * <p>
     * 与标志的接管层一样，这份快照会被所有 region 线程在判定热路径上读取，
     * 因此采用「volatile 引用 + 不可变内容」整体替换，读取端零加锁。
     * </p>
     */
    private volatile Map<RoleType, Map<PermissionId, Boolean>> globalOverrides = Map.of();

    private boolean changed = false;
    private int configVersion;
    private boolean verbose;

    public PermissionsV2ConfigManager(CommentedFileConfig config) {
        this.config = config;
    }

    private static List<String> literalPath(String key) {
        return Collections.singletonList(key);
    }

    private static @Nullable Object getLiteral(CommentedConfig cfg, String literalKey) {
        return cfg.get(literalPath(literalKey));
    }

    private static @Nullable NamespacedKey parseNamespacedKey(String s) {
        if (s == null) return null;
        int idx = s.indexOf(':');
        if (idx <= 0 || idx == s.length() - 1) return null;
        String ns = s.substring(0, idx);
        String key = s.substring(idx + 1);
        try {
            return new NamespacedKey(ns, key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static @Nullable Boolean coerceBoolean(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.equals("true") || v.equals("on") || v.equals("yes")) return true;
            if (v.equals("false") || v.equals("off") || v.equals("no")) return false;
        }
        return null;
    }

    private static void applyInto(EnumMap<RoleType, PermissionSet> target,
                                  PermissionRegistry registry,
                                  @Nullable EnumMap<RoleType, Map<PermissionId, Boolean>> source) {
        if (source == null) return;

        for (Map.Entry<RoleType, Map<PermissionId, Boolean>> roleEntry : source.entrySet()) {
            RoleType role = roleEntry.getKey();
            PermissionSet set = target.computeIfAbsent(role, r -> new PermissionSet(registry.size()));
            set.ensureCapacity(registry.size());

            for (Map.Entry<PermissionId, Boolean> permEntry : roleEntry.getValue().entrySet()) {
                set.set(permEntry.getKey(), permEntry.getValue());
            }
        }
    }

    private void setLiteral(CommentedConfig cfg, String literalKey, Object value) {
        cfg.set(literalPath(literalKey), value);
    }

    private boolean defaultValueFor(RoleType role, NamespacedKey key) {
        // Safe default everywhere.
        return false;
    }

    private CommentedConfig getOrCreateSub(CommentedConfig parent, String key) {
        Object obj = parent.get(key);
        if (obj instanceof CommentedConfig cc) return cc;

        CommentedConfig created = config.createSubConfig();
        parent.set(key, created);
        changed = true;
        return created;
    }

    private List<RoleType> stableRoles() {
        List<RoleType> roles = new ArrayList<>(Arrays.asList(RoleType.values()));
        roles.sort(Comparator.comparingInt(RoleType::getValue).reversed().thenComparing(Enum::name));
        return roles;
    }

    private List<NamespacedKey> stableKeys(PermissionRegistry registry) {
        List<NamespacedKey> keys = registry.keys();
        keys.sort(Comparator.comparing(NamespacedKey::getNamespace).thenComparing(NamespacedKey::getKey));
        return keys;
    }

    private void migrateDefaultsRootToFlat(@Nullable CommentedConfig defaultsRoot) {
        if (defaultsRoot == null) return;

        for (String roleKey : new ArrayList<>(defaultsRoot.valueMap().keySet())) {
            Object roleNodeObj = defaultsRoot.get(roleKey);
            if (!(roleNodeObj instanceof CommentedConfig roleNode)) continue;

            boolean hasNested = false;
            for (Object v : roleNode.valueMap().values()) {
                if (v instanceof CommentedConfig) {
                    hasNested = true;
                    break;
                }
            }
            if (!hasNested) continue;

            Map<String, Boolean> flat = new TreeMap<>();
            flattenRoleNode(roleNode, "", flat);

            for (String k : new ArrayList<>(roleNode.valueMap().keySet())) {
                roleNode.remove(k);
            }
            for (Map.Entry<String, Boolean> e : flat.entrySet()) {
                setLiteral(roleNode, e.getKey(), e.getValue());
            }
            changed = true;
        }
    }


    private void flattenRoleNode(CommentedConfig node, String prefix, Map<String, Boolean> out) {
        // node contains entries like:
        // "skyllia:block" -> CommentedConfig { break=false, use -> CommentedConfig { bucket=false } }
        for (Map.Entry<String, Object> entry : node.valueMap().entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();

            String nextPrefix = prefix.isEmpty() ? k : (prefix + "." + k);

            if (v instanceof CommentedConfig cc) {
                flattenRoleNode(cc, nextPrefix, out);
            } else {
                Boolean b = coerceBoolean(v);
                if (b != null) {
                    out.put(nextPrefix, b);
                }
            }
        }
    }

    private void ensureAllDefaultsExist(PermissionRegistry registry) {
        CommentedConfig defaultsRoot = getOrCreateSub(config, "defaults");
        migrateDefaultsRootToFlat(defaultsRoot);
        ensureRoleBlocksContainAllFlat(registry, defaultsRoot);

        Object islandObj = config.get("island");
        if (!(islandObj instanceof CommentedConfig islandRoot)) return;

        for (String islandType : new TreeSet<>(islandRoot.valueMap().keySet())) {
            Object islandNodeObj = islandRoot.get(islandType);
            if (!(islandNodeObj instanceof CommentedConfig islandNode)) continue;

            CommentedConfig islandDefaultsRoot = getOrCreateSub(islandNode, "defaults");
            migrateDefaultsRootToFlat(islandDefaultsRoot);
            ensureRoleBlocksContainAllFlat(registry, islandDefaultsRoot);
        }
    }

    private void readDefaultsFlat(PermissionRegistry registry, @Nullable String islandType, @Nullable CommentedConfig defaultsRoot,
                                  Map<String, EnumMap<RoleType, Map<PermissionId, Boolean>>> target) {
        if (defaultsRoot == null) return;

        EnumMap<RoleType, Map<PermissionId, Boolean>> roleMap =
                target.computeIfAbsent(islandType, k -> new EnumMap<>(RoleType.class));

        List<String> roleKeys = new ArrayList<>(defaultsRoot.valueMap().keySet());
        roleKeys.sort(String::compareTo);

        String typeLabel = (islandType == null) ? "global" : islandType;
        //log.info("Reading permission defaults for type '{}'...", typeLabel);

        for (String roleKey : roleKeys) {
            RoleType role;
            try {
                role = RoleType.valueOf(roleKey);
            } catch (Exception ignored) {
                //log.warn("Unknown role '{}' in defaults, skipping.", roleKey);
                continue;
            }

            Object roleNodeObj = defaultsRoot.get(roleKey);
            if (!(roleNodeObj instanceof CommentedConfig roleNode)) continue;

            Map<PermissionId, Boolean> perms = roleMap.computeIfAbsent(role, r -> new HashMap<>());

            List<Map.Entry<String, Object>> entries = new ArrayList<>(roleNode.valueMap().entrySet());
            entries.sort(Map.Entry.comparingByKey());

            for (Map.Entry<String, Object> entry : entries) {
                String permString = entry.getKey();
                Object valueObj = entry.getValue();

                if (valueObj instanceof CommentedConfig) {
                    // 嵌套结构（理论上已被展平），打印警告
                    //log.warn("Found nested structure under permission key '{}' (role={}, type={}), ignoring.", permString, role, typeLabel);
                    continue;
                }

                Boolean value = coerceBoolean(valueObj);
                if (value == null) {
                    //log.warn("Unable to parse boolean value for permission '{}' (role={}, type={}), skipping.", permString, role, typeLabel);
                    continue;
                }

                NamespacedKey key = parseNamespacedKey(permString);
                if (key == null) {
                    //log.warn("Malformed permission key '{}' (role={}, type={}), skipping.", permString, role, typeLabel);
                    continue;
                }

                PermissionId pid = registry.getIfPresent(key);
                if (pid == null) {
                    //log.warn("Unknown permission '{}' in defaults (type='{}', role='{}'). It is not registered. Check spelling or namespace.",
                    //        key, typeLabel, role);
                    continue;
                }

                perms.put(pid, value);
                // 打印每个成功读取的权限条目
                //log.info("  Default permission: [{}] {} = {}", typeLabel, permString, value);
            }
        }
    }

    private void ensureRoleBlocksContainAllFlat(PermissionRegistry registry, CommentedConfig defaultsRoot) {
        List<RoleType> roles = stableRoles();
        List<NamespacedKey> keys = stableKeys(registry);

        String typeLabel = (defaultsRoot == config.get("defaults")) ? "global" : "island-type";
        //log.info("Ensuring all registered permissions exist in defaults ({}). Missing entries will be filled with default value.", typeLabel);

        for (RoleType role : roles) {
            CommentedConfig roleNode = getOrCreateSub(defaultsRoot, role.name());

            for (NamespacedKey k : keys) {
                String flatKey = k.getNamespace() + ":" + k.getKey();
                if (getLiteral(roleNode, flatKey) != null) continue;

                // 缺失的权限，打印提示
                //log.info("  Missing permission '{}' for role '{}' ({}), filling with default: {}",
                //        flatKey, role, typeLabel, defaultValueFor(role, k));

                setLiteral(roleNode, flatKey, defaultValueFor(role, k));
                changed = true;
            }
        }
    }

    @Override
    public void loadConfig() {
        changed = false;

        this.configVersion = getOrSetDefault("config-version", 1, Integer.class);
        this.verbose = getOrSetDefault("verbose", false, Boolean.class);

        PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();

        ensureAllDefaultsExist(registry);

        // 先在局部 Map 里构建完整结果，最后整体替换，避免建岛流程在重载瞬间读到半成品。
        Map<String, EnumMap<RoleType, Map<PermissionId, Boolean>>> defaults = new HashMap<>();
        readDefaultsFlat(registry, null, config.get("defaults"), defaults);

        Object islandObj = config.get("island");
        if (islandObj instanceof CommentedConfig islandRoot) {
            List<String> islandTypes = new ArrayList<>(islandRoot.valueMap().keySet());
            islandTypes.sort(String::compareTo);

            for (String islandType : islandTypes) {
                Object islandNodeObj = islandRoot.get(islandType);
                if (!(islandNodeObj instanceof CommentedConfig islandNode)) continue;
                readDefaultsFlat(registry, islandType, islandNode.get("defaults"), defaults);
            }
        }

        this.compiledDefaults = defaults;
        this.globalOverrides = readGlobalOverrides(registry);

        if (changed) {
            TomlWriter tomlWriter = new TomlWriter();
            tomlWriter.setIndent(IndentStyle.NONE);
            tomlWriter.write(config, config.getFile(), WritingMode.REPLACE);
        }
    }

    @Override
    public void reloadFromDisk() {
        config.load();
    }

    @Override
    public boolean canReloadFromDisk() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrSetDefault(String path, T defaultValue, Class<T> expected) {
        Object value = config.get(path);
        if (value == null) {
            config.set(path, defaultValue);
            changed = true;
            return defaultValue;
        }
        if (expected.isInstance(value)) return (T) value;

        if (expected == Long.class && value instanceof Integer i) return (T) Long.valueOf(i);
        if (expected == Float.class && value instanceof Double d) return (T) Float.valueOf(d.floatValue());

        throw new IllegalStateException("Cannot convert value at path '" + path + "' from " +
                value.getClass().getSimpleName() + " to " + expected.getSimpleName());
    }

    public EnumMap<RoleType, byte[]> buildDefaultRoleBlobs(@Nullable String islandType) {
        PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
        EnumMap<RoleType, PermissionSet> sets = new EnumMap<>(RoleType.class);

        // global defaults
        applyInto(sets, registry, compiledDefaults.get(null));

        // per-island-type overrides
        if (islandType != null) {
            applyInto(sets, registry, compiledDefaults.get(islandType));
        }

        EnumMap<RoleType, byte[]> out = new EnumMap<>(RoleType.class);
        for (Map.Entry<RoleType, PermissionSet> e : sets.entrySet()) {
            PermissionSet set = e.getValue();
            set.ensureCapacity(registry.size());

            long[] words = set.snapshotWords();
            byte[] blob = PermissionSetCodec.encodeLongs(words);
            out.put(e.getKey(), blob);
        }
        return out;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void compileNow() {
        loadConfig();
    }

    // ═══════════════════════════════════════════════════════════════
    //  全局接管层（[global-override.<角色>]）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 查询某个角色的某条权限是否被全局接管。
     * <p>
     * 位于权限判定热路径上：一次 volatile 读 + 两次 Map 查找，无锁无分配。
     * </p>
     *
     * @return 被接管时返回强制值，未被接管返回 {@code null}
     * —— 调用方此时应回退到岛屿自己的编译权限集
     */
    public @Nullable Boolean globalOverride(@Nullable RoleType role, @Nullable PermissionId id) {
        if (role == null || id == null) return null;

        Map<RoleType, Map<PermissionId, Boolean>> snapshot = this.globalOverrides;
        if (snapshot.isEmpty()) return null;

        Map<PermissionId, Boolean> perRole = snapshot.get(role);
        return perRole == null ? null : perRole.get(id);
    }

    /**
     * 增加、修改或撤销一条全局权限接管，并立即落盘 + 重建内存快照。
     * 由管理员命令调用（已在异步调度器上，文件写入不阻塞 region 线程）。
     *
     * @param value 强制值；传 {@code null} 表示撤销接管、交还岛主自治
     * @return {@code true} 表示已写入；{@code false} 表示该权限未注册
     */
    public synchronized boolean setGlobalOverride(RoleType role, NamespacedKey key, @Nullable Boolean value) {
        PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
        if (registry.getIfPresent(key) == null) return false;

        String flatKey = key.getNamespace() + ":" + key.getKey();

        CommentedConfig roleBlock = getOrCreateBlock(getOrCreateBlock(config, GLOBAL_OVERRIDE), role.name());

        if (value == null) {
            roleBlock.remove(literalPath(flatKey));
        } else {
            roleBlock.set(literalPath(flatKey), value.booleanValue());
        }

        persist();
        this.globalOverrides = readGlobalOverrides(registry);
        return true;
    }

    /**
     * 列出当前生效的全部全局权限接管，供 {@code /isadmin perm list} 展示。
     */
    public List<String> describeGlobalOverrides() {
        PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
        Map<RoleType, Map<PermissionId, Boolean>> snapshot = this.globalOverrides;

        List<String> lines = new ArrayList<>();
        for (RoleType role : RoleType.values()) {
            Map<PermissionId, Boolean> entries = snapshot.get(role);
            if (entries == null || entries.isEmpty()) continue;

            List<String> rendered = new ArrayList<>();
            for (Map.Entry<PermissionId, Boolean> e : entries.entrySet()) {
                String name;
                try {
                    NamespacedKey k = registry.node(e.getKey()).node();
                    name = k.getNamespace() + ":" + k.getKey();
                } catch (Exception ignored) {
                    name = "#" + e.getKey().index();
                }
                rendered.add("[" + role.name() + "] " + name + " = " + e.getValue());
            }
            Collections.sort(rendered);
            lines.addAll(rendered);
        }
        return lines;
    }

    /**
     * 从配置树解析 {@code [global-override.<角色>]}，产出一份全新的不可变快照。
     * 未知角色或未注册的权限会被跳过并记录警告，不影响其余条目。
     */
    private Map<RoleType, Map<PermissionId, Boolean>> readGlobalOverrides(PermissionRegistry registry) {
        Object rootObj = config.get(literalPath(GLOBAL_OVERRIDE));
        if (!(rootObj instanceof CommentedConfig root)) return Map.of();

        Map<RoleType, Map<PermissionId, Boolean>> out = new EnumMap<>(RoleType.class);

        for (String roleKey : root.valueMap().keySet()) {
            RoleType role;
            try {
                role = RoleType.valueOf(roleKey);
            } catch (Exception ignored) {
                log.warn("[global-override] 未知角色 '{}'，已忽略。可用角色：OWNER / CO_OWNER / MODERATOR / MEMBER / VISITOR / BAN", roleKey);
                continue;
            }

            Object roleNodeObj = root.get(literalPath(roleKey));
            if (!(roleNodeObj instanceof CommentedConfig roleNode)) continue;

            Map<PermissionId, Boolean> perRole = new HashMap<>();
            for (Map.Entry<String, Object> entry : roleNode.valueMap().entrySet()) {
                if (entry.getValue() instanceof CommentedConfig) continue;

                Boolean value = coerceBoolean(entry.getValue());
                if (value == null) continue;

                NamespacedKey key = parseNamespacedKey(entry.getKey());
                if (key == null) continue;

                PermissionId id = registry.getIfPresent(key);
                if (id == null) {
                    log.warn("[global-override] 未知权限 '{}'（角色 {}），已忽略。拼写有误，或对应附属插件尚未安装。",
                            entry.getKey(), roleKey);
                    continue;
                }
                perRole.put(id, value);
            }

            if (!perRole.isEmpty()) out.put(role, Map.copyOf(perRole));
        }

        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    private CommentedConfig getOrCreateBlock(CommentedConfig parent, String key) {
        Object obj = parent.get(literalPath(key));
        if (obj instanceof CommentedConfig cc) return cc;
        CommentedConfig created = config.createSubConfig();
        parent.set(literalPath(key), created);
        return created;
    }

    private void persist() {
        TomlWriter tomlWriter = new TomlWriter();
        tomlWriter.setIndent(IndentStyle.NONE);
        tomlWriter.write(config, config.getFile(), WritingMode.REPLACE);
    }
}
