package fr.euphyllia.skylliatrader.merchant;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 三种商队。每种对应一张独立的凭证，也对应一套独立的供货范围（见 HANDOFF.md 6.3）。
 * <p>
 * <b>「每岛每种商队最多 1 个」是按这个枚举计数的</b>，不是按「游商实体总数」——
 * T1 的 {@code credential.max-merchants-per-island = 3} 只是一个笼统的总数上限，
 * 和规格要求的「三种各 1 个」不是一回事：光有总数上限的话，一个玩家连用三张主世界凭证
 * 就能占满三个名额，而下界/末地商队永远召不出来。T2 起分类型上限
 * （{@code credential.max-per-caravan}）才是主判定，总数上限退化为一道额外的天花板。
 * </p>
 * <p>
 * 枚举名会被写进岛屿数据的 JSON（{@code MerchantRecord.caravan}）和实体 PDC，
 * <b>改名等于让存量数据全部对不上号</b>（反序列化后 {@link #parseOrNull} 返回 null，
 * 那条记录会被当成「未知商队」而不是被静默归类到别的商队）。要加第四种商队就加新枚举常量，
 * 不要改现有的名字。
 * </p>
 */
public enum CaravanType {

    /** 🌍 主世界商队：植物 / 树苗 / 染料 / 沙砾黏土冰 / 主世界石材 / 海洋 / 主世界矿物。 */
    OVERWORLD("overworld", "主世界商队"),

    /** 🔥 下界商队：下界岩石 / 下界砖石英 / 灵魂沙土 / 菌类木材 / 酿造材料 / 远古残骸。 */
    NETHER("nether", "下界商队"),

    /** 🌌 末地商队：末地石 / 紫珀 / 紫颂 / 潜影 / 鞘翅 / 龙息。 */
    END("end", "末地商队");

    private final String configKey;
    private final String defaultDisplayName;

    CaravanType(String configKey, String defaultDisplayName) {
        this.configKey = configKey;
        this.defaultDisplayName = defaultDisplayName;
    }

    /**
     * 按枚举名解析，无法识别时返回 {@code null}（不抛异常、不回退到某个默认商队）。
     * <p>
     * 回退到默认商队会把「一条读不懂的记录」变成「一条看起来正常但归错商队的记录」——
     * 那意味着玩家的主世界名额被一条坏数据永久占着，而日志里什么都看不到。
     * 返回 null 让调用方明确处理「未知商队」这种情况。
     * </p>
     */
    public static @Nullable CaravanType parseOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** config.toml 里的小写键名，例如 {@code [credential.item.overworld]}。 */
    public String configKey() {
        return configKey;
    }

    /** 配置里没写显示名时用的中文兜底名。 */
    public String defaultDisplayName() {
        return defaultDisplayName;
    }
}
