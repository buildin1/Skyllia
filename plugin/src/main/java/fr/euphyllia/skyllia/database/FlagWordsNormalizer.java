package fr.euphyllia.skyllia.database;

import fr.euphyllia.skyllia.api.permissions.FlagId;
import fr.euphyllia.skyllia.api.permissions.IslandFlagRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 岛屿标志位图的<b>惰性迁移归一化</b>——「总开关关不掉单体行为」这个老毛病的根治层。
 *
 * <h2>背景</h2>
 * 岛屿层的标志是纯布尔位图，没有「没设置」这个状态。历史上两参
 * {@code hasFlag(specific, fallback)} 在岛屿层取「或」（单体 || 总开关），
 * 于是单体开关只能加不能减：总开关开着时，单独关掉恶魂/岩浆怪毫无效果
 * （2026-08 的岩浆怪刷新与恶魂爆炸两单皆源于此）。直接改「与」又会把存量岛屿
 * 反序列化扩容出来的全 0 单体位暴露成「永远拒绝」——2026-08-21 当晚试过，
 * 全服刷怪全灭，连夜回滚。
 *
 * <h2>方案</h2>
 * {@code islands_flags} 增加 {@code words_version} 列，记录该行落库时位图覆盖到的
 * 标志数量（{@code registry.size()}）；旧行默认 0。每次从数据库加载时按覆盖数归一化：
 * <ul>
 *   <li><b>covered == 0（“或”时代的旧行）</b>：对每个已声明配对做
 *       {@code 单体位 |= 总开关位}，再做 {@code 总开关位 |= 任一单体位}。
 *       两步合起来保证「或→与」切换前后每座岛的实际生效结果逐位不变
 *       （含「总开关关、白名单式单独开」的岛）。</li>
 *   <li><b>covered &gt; 0 但小于当前注册量（此后新注册的标志）</b>：位图里
 *       {@code index >= covered} 的单体位不是岛主的选择、只是扩容补的 0，
 *       从其总开关回填（= 跟随总开关）。{@code index < covered} 的位是岛主
 *       落过库的真实选择，永不改写。这样未来每次给游戏加新生物/新标志，
 *       存量岛屿都自动继承总开关，不再复发「新位天生 0 = 全灭」。</li>
 * </ul>
 * 归一化在读取路径完成、幂等，可被并发线程重复执行；写回失败也无碍——
 * 下次加载会对同一份旧行重算出同样的结果。
 *
 * @see fr.euphyllia.skyllia.api.permissions.IslandFlagRegistry#declareFallback
 */
public final class FlagWordsNormalizer {

    private FlagWordsNormalizer() {
    }

    /**
     * @param words           归一化后的位图（可能比输入长）
     * @param changed         位图内容是否发生了变化（决定要不要写回数据库）
     * @param newCoveredCount 写回时应记录的覆盖数（= 当前注册表大小）
     */
    public record Result(long[] words, boolean changed, int newCoveredCount) {
    }

    /**
     * 归一化一份从数据库读出的位图。
     *
     * @param stored       数据库里的原始位图（不会被修改）
     * @param coveredCount 该行落库时的 {@code words_version}；0 表示「或」时代的旧行
     * @param registry     当前标志注册表
     * @return 归一化结果；返回 {@code null} 表示不需要也不应该动这一行
     * （已是最新覆盖、或配对关系尚未注册完毕——此时写回反而会把旧行错误地标成已迁移）
     */
    public static @Nullable Result normalize(long[] stored, int coveredCount, IslandFlagRegistry registry) {
        Map<FlagId, FlagId> pairs = registry.fallbackPairs();
        int registrySize = registry.size();

        // 防御：标志模块还没注册完（配对为空）时绝不迁移旧行，
        // 否则会把 covered 从 0 抬上去、让真正的“或→与”合并永远不再发生。
        if (pairs.isEmpty()) return null;

        if (coveredCount >= registrySize) return null;

        int wordsNeeded = Math.max(1, (registrySize + 63) >>> 6);
        long[] out = new long[Math.max(wordsNeeded, stored.length)];
        System.arraycopy(stored, 0, out, 0, stored.length);

        boolean changed = false;

        if (coveredCount <= 0) {
            // “或”时代旧行：先按旧位图快照算总开关/单体的旧值，再统一写入，
            // 保证 单体' = 单体 || 总开关、总开关' = 总开关 || 任一单体 都基于旧值。
            long[] old = stored.clone();
            for (Map.Entry<FlagId, FlagId> e : pairs.entrySet()) {
                int spec = e.getKey().index();
                int master = e.getValue().index();
                if (get(old, master) && !get(out, spec)) {
                    set(out, spec);
                    changed = true;
                }
                if (get(old, spec) && !get(out, master)) {
                    set(out, master);
                    changed = true;
                }
            }
        } else {
            // 已迁移过的行：只回填此后新注册的单体位（跟随各自的总开关）。
            for (Map.Entry<FlagId, FlagId> e : pairs.entrySet()) {
                int spec = e.getKey().index();
                int master = e.getValue().index();
                if (spec < coveredCount) continue;      // 岛主落过库的真实选择，不动
                if (master >= coveredCount) continue;   // 整组都是新的，无旧值可继承
                if (get(out, master) && !get(out, spec)) {
                    set(out, spec);
                    changed = true;
                }
            }
        }

        // 旧行即便逐位没变也要写回一次版本戳，否则每次重启都会重扫；
        // covered > 0 的行没变就不写，等下一次真实保存自然把版本带上去。
        if (!changed && coveredCount > 0) return null;

        return new Result(out, changed, registrySize);
    }

    private static boolean get(long[] words, int bit) {
        int w = bit >>> 6;
        if (w >= words.length) return false;
        return (words[w] & (1L << (bit & 63))) != 0;
    }

    private static void set(long[] words, int bit) {
        words[bit >>> 6] |= (1L << (bit & 63));
    }
}
