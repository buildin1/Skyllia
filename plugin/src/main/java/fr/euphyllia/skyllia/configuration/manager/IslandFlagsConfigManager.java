package fr.euphyllia.skyllia.configuration.manager;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import fr.euphyllia.skyllia.api.permissions.FlagId;
import fr.euphyllia.skyllia.api.permissions.FlagNode;
import fr.euphyllia.skyllia.api.permissions.IslandFlagRegistry;
import fr.euphyllia.skyllia.api.permissions.IslandFlags;
import fr.euphyllia.skyllia.api.permissions.PermissionSetCodec;
import fr.euphyllia.skyllia.database.FlagWordsNormalizer;
import fr.euphyllia.skyllia.utils.ConfigFileWriter;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

public class IslandFlagsConfigManager implements IConfigurationProvider {

    private static final Logger log = LoggerFactory.getLogger(IslandFlagsConfigManager.class);

    /**
     * TOML 顶层区块名：全局接管层。
     * <p>
     * 与 {@code [defaults]} 的区别：{@code [defaults]} 只在<b>建岛那一刻</b>被用来生成岛屿
     * 自己的标志位图，之后改它对已存在的岛屿毫无影响；而 {@code [global-override]} 是在
     * <b>每次判定时</b>实时生效的，写在这里的标志会直接决定全服所有岛屿（含老岛）的取值，
     * 无需改数据库、无需重启。没有写在这里的标志则完全交给岛主自治。
     * </p>
     */
    private static final String GLOBAL_OVERRIDE = "global-override";

    /**
     * {@link #globalOverrides} 中代表「不限世界」的键。
     * 用哨兵字符串而不是 {@code null}，因为快照是不可变 Map（不允许 null 键）。
     * 世界名不可能是 {@code *}，不会冲突。
     */
    private static final String ANY_WORLD = "*";

    private final CommentedFileConfig config;

    /**
     * 建岛时使用的默认值：islandType(null=全局) -> worldName(null=全部) -> flag -> 值。
     * <p>
     * 整体替换的不可变快照：{@link #loadConfig()} 先在局部变量里构建完整结果，最后一次性
     * 赋值。这样并发的建岛流程要么看到旧的完整快照、要么看到新的完整快照，不会像原来
     * 「先 clear 再逐条填」那样读到中间的空状态（那会让恰好在重载瞬间创建的岛屿拿到一份
     * 全 false 的标志位图）。
     * </p>
     */
    // 注意：这里必须用 HashMap 而不是 Map.of()——islandType 与 worldName 都可能是 null
    // （代表「全局」/「所有世界」），而不可变 Map 的 get(null) 会抛 NPE。
    private volatile Map<String, Map<String, Map<FlagId, Boolean>>> compiledDefaults = new HashMap<>();

    /**
     * 全局接管层：worldName（或 {@link #ANY_WORLD}）-> flag -> 强制值。
     * <p>
     * 这份快照会被<b>所有 region 线程</b>在事件判定的热路径上高频读取，因此必须是
     * 「volatile 引用 + 不可变内容」的组合：读取端零加锁零拷贝，写入端整体替换。
     * 绝不能在这里用可变 Map 加锁——在 region 线程上抢锁会直接拖慢事件处理。
     * </p>
     */
    private volatile Map<String, Map<FlagId, Boolean>> globalOverrides = Map.of();

    private boolean changed = false;
    private int configVersion;
    private boolean verbose;

    public IslandFlagsConfigManager(CommentedFileConfig config) {
        this.config = config;
    }

    private static void applyInto(IslandFlags target,
                                  IslandFlagRegistry registry,
                                  @Nullable Map<FlagId, Boolean> source) {
        if (source == null) return;
        for (Map.Entry<FlagId, Boolean> e : source.entrySet()) {
            target.set(registry, e.getKey(), e.getValue());
        }
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
        try {
            return new NamespacedKey(s.substring(0, idx), s.substring(idx + 1));
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

    @Override
    public void loadConfig() {
        changed = false;
        this.configVersion = getOrSetDefault("config-version", 2, Integer.class);
        this.verbose = getOrSetDefault("verbose", false, Boolean.class);

        IslandFlagRegistry registry = SkylliaAPI.getFlagRegistry();

        ensureAllDefaultsExist(registry);

        // 先在局部 Map 里构建完整结果，最后整体替换，避免并发读到半成品（见字段注释）。
        Map<String, Map<String, Map<FlagId, Boolean>>> defaults = new HashMap<>();
        readDefaultsFlat(registry, null, null, config.get("defaults"), defaults);

        Object worldsObj = config.get("worlds");
        if (worldsObj instanceof CommentedConfig worldsRoot) {
            for (String worldName : new TreeSet<>(worldsRoot.valueMap().keySet())) {
                Object worldNodeObj = worldsRoot.get(worldName);
                if (!(worldNodeObj instanceof CommentedConfig worldNode)) continue;
                readDefaultsFlat(registry, null, worldName, worldNode.get("defaults"), defaults);
            }
        }

        Object islandObj = config.get("island");
        if (islandObj instanceof CommentedConfig islandRoot) {
            for (String islandType : new TreeSet<>(islandRoot.valueMap().keySet())) {
                Object islandNodeObj = islandRoot.get(islandType);
                if (!(islandNodeObj instanceof CommentedConfig islandNode)) continue;
                readDefaultsFlat(registry, islandType, null, islandNode.get("defaults"), defaults);

                Object islandWorldsObj = islandNode.get("worlds");
                if (islandWorldsObj instanceof CommentedConfig islandWorldsRoot) {
                    for (String worldName : new TreeSet<>(islandWorldsRoot.valueMap().keySet())) {
                        Object worldNodeObj = islandWorldsRoot.get(worldName);
                        if (!(worldNodeObj instanceof CommentedConfig worldNode)) continue;
                        readDefaultsFlat(registry, islandType, worldName, worldNode.get("defaults"), defaults);
                    }
                }
            }
        }

        this.compiledDefaults = defaults;
        this.globalOverrides = readGlobalOverrides(registry);

        if (changed) {
            persist();
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
        throw new IllegalStateException("Cannot convert value at path '" + path + "' from "
                + value.getClass().getSimpleName() + " to " + expected.getSimpleName());
    }


    public byte[] buildDefaultFlagBlob(@Nullable String islandType, String worldName) {
        IslandFlagRegistry registry = SkylliaAPI.getFlagRegistry();
        IslandFlags flags = new IslandFlags(registry);

        applyInto(flags, registry, getMap(null, null));

        applyInto(flags, registry, getMap(null, worldName));

        if (islandType != null) applyInto(flags, registry, getMap(islandType, null));

        if (islandType != null) applyInto(flags, registry, getMap(islandType, worldName));

        flags.ensureUpToDate(registry);

        // [defaults] 沿用「或」时代的书写语义：单体 false + 总开关 true = 跟随总开关。
        // 生产环境的 flags.toml 里单体条目多是注册时自动补的 false，判定层改「与」后
        // 若按字面落库，新建岛屿会直接全灭——所以落库前做与存量位图同一套归一化
        // （单体 |= 总开关，总开关 |= 任一单体），保证新老岛屿语义一致。
        long[] normalizedWords = flags.snapshotWords();
        FlagWordsNormalizer.Result normalized = FlagWordsNormalizer.normalize(normalizedWords, 0, registry);
        if (normalized != null) normalizedWords = normalized.words();

        return PermissionSetCodec.encodeLongs(normalizedWords);
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
    //  全局接管层（[global-override]）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 查询某个标志是否被全局接管。
     * <p>
     * 这是事件判定热路径的一部分（每次生物生成、每次爆炸、每次活塞伸缩都会走到），
     * 因此实现里只有一次 volatile 读加一到两次 HashMap 查找，没有加锁也没有分配。
     * </p>
     *
     * @param id        要查询的标志；{@code null} 直接返回 {@code null}
     * @param worldName 判定所在世界名；为 {@code null} 时只查「不限世界」层
     * @return 被接管时返回强制值（{@code true}/{@code false}），未被接管返回 {@code null}
     * —— 调用方此时应回退到岛屿自己的标志位图
     */
    public @Nullable Boolean globalOverride(@Nullable FlagId id, @Nullable String worldName) {
        if (id == null) return null;

        Map<String, Map<FlagId, Boolean>> snapshot = this.globalOverrides;
        if (snapshot.isEmpty()) return null;

        // 世界级接管优先于全局接管，允许「主世界开、下界关」这类差异化配置
        if (worldName != null) {
            Map<FlagId, Boolean> perWorld = snapshot.get(worldName);
            if (perWorld != null) {
                Boolean value = perWorld.get(id);
                if (value != null) return value;
            }
        }

        Map<FlagId, Boolean> anyWorld = snapshot.get(ANY_WORLD);
        return anyWorld == null ? null : anyWorld.get(id);
    }

    /**
     * 增加、修改或撤销一条全局接管，并立即落盘 + 重建内存快照。
     * <p>
     * 由管理员命令调用。Skyllia 的 admin 子命令本身就跑在
     * {@code Bukkit.getAsyncScheduler()} 上（见 {@code SkylliaAdminCommand#execute}），
     * 所以这里的文件写入不会阻塞任何 region 线程。
     * </p>
     *
     * @param key       标志的完整键，如 {@code skyllia:island.spawn.passive.all}
     * @param worldName 只对该世界生效；{@code null} 表示不限世界
     * @param value     强制值；传 {@code null} 表示撤销接管、交还岛主自治
     * @return {@code true} 表示已写入；{@code false} 表示该标志未注册（拼错或对应附属没装）
     */
    public synchronized boolean setGlobalOverride(NamespacedKey key,
                                                  @Nullable String worldName,
                                                  @Nullable Boolean value) {
        IslandFlagRegistry registry = SkylliaAPI.getFlagRegistry();
        if (registry.getIfPresent(key) == null) return false;

        String flatKey = key.getNamespace() + ":" + key.getKey();

        CommentedConfig root = getOrCreateBlock(config, GLOBAL_OVERRIDE);
        CommentedConfig target = root;
        if (worldName != null) {
            target = getOrCreateBlock(getOrCreateBlock(root, "worlds"), worldName);
        }

        if (value == null) {
            target.remove(literalPath(flatKey));
        } else {
            target.set(literalPath(flatKey), value.booleanValue());
        }

        persist();
        this.globalOverrides = readGlobalOverrides(registry);
        return true;
    }

    /**
     * 列出当前生效的全部全局接管，供 {@code /isadmin flag list} 展示。
     *
     * @return 形如 {@code "[所有世界] skyllia:island.spawn.passive.all = true"} 的可读文本
     */
    public List<String> describeGlobalOverrides() {
        IslandFlagRegistry registry = SkylliaAPI.getFlagRegistry();
        Map<String, Map<FlagId, Boolean>> snapshot = this.globalOverrides;

        List<String> lines = new ArrayList<>();
        for (String scope : new TreeSet<>(snapshot.keySet())) {
            String scopeLabel = ANY_WORLD.equals(scope) ? "所有世界" : scope;
            Map<FlagId, Boolean> entries = snapshot.get(scope);
            if (entries == null) continue;

            List<String> rendered = new ArrayList<>();
            for (Map.Entry<FlagId, Boolean> e : entries.entrySet()) {
                String name;
                try {
                    NamespacedKey k = registry.node(e.getKey()).node();
                    name = k.getNamespace() + ":" + k.getKey();
                } catch (Exception ignored) {
                    name = "#" + e.getKey().index();
                }
                rendered.add("[" + scopeLabel + "] " + name + " = " + e.getValue());
            }
            Collections.sort(rendered);
            lines.addAll(rendered);
        }
        return lines;
    }

    /**
     * 从配置树解析 {@code [global-override]}，产出一份全新的不可变快照。
     * 未注册的标志键会被跳过而不是报错——附属插件可能还没装，但配置里可以先写着。
     */
    private Map<String, Map<FlagId, Boolean>> readGlobalOverrides(IslandFlagRegistry registry) {
        Object rootObj = config.get(literalPath(GLOBAL_OVERRIDE));
        if (!(rootObj instanceof CommentedConfig root)) return Map.of();

        Map<String, Map<FlagId, Boolean>> out = new HashMap<>();

        // [global-override] 下直接写的扁平键 = 不限世界
        Map<FlagId, Boolean> anyWorld = collectOverrideBlock(registry, root, ANY_WORLD);
        if (!anyWorld.isEmpty()) out.put(ANY_WORLD, Map.copyOf(anyWorld));

        // [global-override.worlds.<世界名>] = 只对该世界生效
        Object worldsObj = root.get(literalPath("worlds"));
        if (worldsObj instanceof CommentedConfig worldsRoot) {
            for (String worldName : worldsRoot.valueMap().keySet()) {
                Object worldNodeObj = worldsRoot.get(literalPath(worldName));
                if (!(worldNodeObj instanceof CommentedConfig worldNode)) continue;
                Map<FlagId, Boolean> perWorld = collectOverrideBlock(registry, worldNode, worldName);
                if (!perWorld.isEmpty()) out.put(worldName, Map.copyOf(perWorld));
            }
        }

        return Map.copyOf(out);
    }

    private Map<FlagId, Boolean> collectOverrideBlock(IslandFlagRegistry registry,
                                                      CommentedConfig block,
                                                      String scopeLabel) {
        Map<FlagId, Boolean> out = new HashMap<>();
        for (Map.Entry<String, Object> entry : block.valueMap().entrySet()) {
            // 子表（例如 worlds）不是标志条目，跳过
            if (entry.getValue() instanceof CommentedConfig) continue;

            Boolean value = coerceBoolean(entry.getValue());
            if (value == null) continue;

            NamespacedKey key = parseNamespacedKey(entry.getKey());
            if (key == null) continue;

            FlagId id = registry.getIfPresent(key);
            if (id == null) {
                log.warn("[global-override] 未知标志 '{}'（作用域 {}），已忽略。拼写有误，或对应附属插件尚未安装。",
                        entry.getKey(), scopeLabel);
                continue;
            }
            out.put(id, value);
        }
        return out;
    }

    private CommentedConfig getOrCreateBlock(CommentedConfig parent, String key) {
        Object obj = parent.get(literalPath(key));
        if (obj instanceof CommentedConfig cc) return cc;
        CommentedConfig created = config.createSubConfig();
        parent.set(literalPath(key), created);
        return created;
    }

    /**
     * 整表落盘。走 {@link ConfigFileWriter#writeAtomically}「先写 .tmp 再原子改名」，
     * 不直接截断目标文件：flags.toml 写坏了就是全服岛屿标志判定失效。
     */
    private void persist() {
        ConfigFileWriter.writeAtomically(config);
    }

    private void ensureAllDefaultsExist(IslandFlagRegistry registry) {
        CommentedConfig defaultsRoot = getOrCreateSub(config);
        ensureFlatBlockContainsAll(registry, defaultsRoot);

        Object islandObj = config.get("island");
        if (!(islandObj instanceof CommentedConfig islandRoot)) return;

        for (String islandType : new TreeSet<>(islandRoot.valueMap().keySet())) {
            Object islandNodeObj = islandRoot.get(islandType);
            if (!(islandNodeObj instanceof CommentedConfig islandNode)) continue;
            CommentedConfig islandDefaultsRoot = getOrCreateSub(islandNode);
            ensureFlatBlockContainsAll(registry, islandDefaultsRoot);
        }
    }

    private void ensureFlatBlockContainsAll(IslandFlagRegistry registry, CommentedConfig block) {
        List<NamespacedKey> keys = new ArrayList<>(registry.keys());
        keys.sort(Comparator.comparing(NamespacedKey::getNamespace).thenComparing(NamespacedKey::getKey));

        for (NamespacedKey k : keys) {
            String flatKey = k.getNamespace() + ":" + k.getKey();
            if (getLiteral(block, flatKey) != null) continue;

            FlagNode node = registry.node(registry.id(k));
            setLiteral(block, flatKey, node.defaultValue());
            changed = true;
        }
    }

    private void readDefaultsFlat(IslandFlagRegistry registry,
                                  @Nullable String islandType,
                                  @Nullable String worldName,
                                  @Nullable Object defaultsObj,
                                  Map<String, Map<String, Map<FlagId, Boolean>>> target) {
        if (!(defaultsObj instanceof CommentedConfig defaultsBlock)) return;

        Map<FlagId, Boolean> flagMap = target
                .computeIfAbsent(islandType, k -> new HashMap<>())
                .computeIfAbsent(worldName, k -> new HashMap<>());

        List<Map.Entry<String, Object>> entries = new ArrayList<>(defaultsBlock.valueMap().entrySet());
        entries.sort(Map.Entry.comparingByKey());

        for (Map.Entry<String, Object> entry : entries) {
            String permString = entry.getKey();
            Boolean value = coerceBoolean(entry.getValue());

            if (value == null) continue;

            NamespacedKey key = parseNamespacedKey(permString);
            if (key == null) continue;

            FlagId id = registry.getIfPresent(key);
            if (id == null) {
                if (verbose) log.info("Unknown flag '{}' (islandType='{}', world='{}')", key, islandType, worldName);
                continue;
            }
            flagMap.put(id, value);
        }
    }

    private CommentedConfig getOrCreateSub(CommentedConfig parent) {
        Object obj = parent.get("defaults");
        if (obj instanceof CommentedConfig cc) return cc;
        CommentedConfig created = config.createSubConfig();
        parent.set("defaults", created);
        changed = true;
        return created;
    }

    private void setLiteral(CommentedConfig cfg, String literalKey, Object value) {
        cfg.set(literalPath(literalKey), value);
    }

    private @Nullable Map<FlagId, Boolean> getMap(@Nullable String islandType, @Nullable String worldName) {
        Map<String, Map<FlagId, Boolean>> byWorld = compiledDefaults.get(islandType);
        if (byWorld == null) return null;
        return byWorld.get(worldName);
    }
}
