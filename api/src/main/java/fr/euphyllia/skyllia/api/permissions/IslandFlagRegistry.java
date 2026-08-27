package fr.euphyllia.skyllia.api.permissions;

import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class IslandFlagRegistry {

    private final PermissionIndexStore indexStore;

    private final Map<NamespacedKey, FlagId> ids = new HashMap<>();
    private final List<FlagNode> byIndex = new ArrayList<>();

    /**
     * 单体标志 -> 其总开关（兜底标志）的配对关系。
     * <p>
     * 由各 {@code FlagModule#registerFlags} 在注册标志的同时声明，是
     * <b>位图惰性迁移</b>（{@code FlagWordsNormalizer}）与两参
     * {@code hasFlag(specific, fallback)} 判定共用的唯一事实来源：
     * 迁移要靠它知道「哪一位的空缺应该从哪个总开关回填」。
     * 漏声明的后果是存量岛屿在该单体位上被回填成 0（= 永远拒绝），
     * 所以新增带总开关的标志时必须同步调用 {@link #declareFallback}。
     * </p>
     */
    private final Map<FlagId, FlagId> fallbackBySpecific = new HashMap<>();

    private int version = 0;
    private int maxIndex = -1;

    public IslandFlagRegistry(PermissionIndexStore indexStore) {
        this.indexStore = indexStore;
    }

    private static String toNodeKey(NamespacedKey key) {
        return "flag:" + key.getNamespace() + ":" + key.getKey();
    }

    public synchronized int version() {
        return version;
    }

    public synchronized int size() {
        return Math.max(0, maxIndex + 1);
    }

    public synchronized FlagId register(FlagNode node) {
        FlagId existing = ids.get(node.node());
        if (existing != null) return existing;

        int idx = indexStore.getOrAllocate(toNodeKey(node.node()));
        FlagId id = new FlagId(idx);

        ids.put(node.node(), id);
        putByIndex(idx, node);

        version++;
        return id;
    }

    public synchronized FlagId getIfPresent(NamespacedKey node) {
        return ids.get(node);
    }

    public synchronized FlagId idOrRegister(FlagNode node) {
        FlagId existing = ids.get(node.node());
        if (existing != null) return existing;
        return register(node);
    }

    public synchronized FlagId id(NamespacedKey node) {
        FlagId id = ids.get(node);
        if (id == null) throw new IllegalArgumentException("Unknown flag node: " + node);
        return id;
    }

    public synchronized FlagNode node(FlagId id) {
        int idx = id.index();
        if (idx < 0 || idx >= byIndex.size()) {
            throw new IndexOutOfBoundsException("Invalid FlagId: " + idx);
        }
        FlagNode node = byIndex.get(idx);
        if (node == null) {
            throw new IllegalStateException("No FlagNode registered for index: " + idx);
        }
        return node;
    }

    /**
     * 声明「单体标志 specific 的总开关是 fallback」。幂等；重复声明同一配对无副作用，
     * 同一单体声明两个不同总开关视为编程错误、直接抛异常。
     */
    public synchronized void declareFallback(FlagId specific, FlagId fallback) {
        if (specific == null || fallback == null) {
            throw new IllegalArgumentException("declareFallback: specific/fallback 不能为 null");
        }
        FlagId previous = fallbackBySpecific.putIfAbsent(specific, fallback);
        if (previous != null && previous.index() != fallback.index()) {
            throw new IllegalStateException(
                    "标志 " + describe(specific) + " 已声明总开关 " + describe(previous)
                            + "，不能再声明为 " + describe(fallback));
        }
    }

    /**
     * 当前已声明的全部「单体 -> 总开关」配对的不可变快照。
     */
    public synchronized Map<FlagId, FlagId> fallbackPairs() {
        return Map.copyOf(fallbackBySpecific);
    }

    private String describe(FlagId id) {
        try {
            return node(id).node().toString();
        } catch (Exception e) {
            return "#" + id.index();
        }
    }

    public synchronized List<NamespacedKey> keys() {
        return new ArrayList<>(ids.keySet());
    }

    public synchronized Map<NamespacedKey, FlagId> entries() {
        return Map.copyOf(ids);
    }

    private void putByIndex(int idx, FlagNode node) {
        if (idx > maxIndex) maxIndex = idx;

        ensureByIndexCapacity(idx + 1);

        FlagNode previous = byIndex.get(idx);
        if (previous != null && !previous.node().equals(node.node())) {
            throw new IllegalStateException(
                    "Flag index collision: idx=" + idx + " already mapped to " + previous.node()
                            + ", attempted to map " + node.node()
            );
        }
        byIndex.set(idx, node);
    }

    private void ensureByIndexCapacity(int size) {
        while (byIndex.size() < size) byIndex.add(null);
    }
}
