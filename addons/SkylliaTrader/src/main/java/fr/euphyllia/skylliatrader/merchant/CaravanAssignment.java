package fr.euphyllia.skylliatrader.merchant;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * shop.toml 里<b>没写 caravan 字段</b>的商品，按材质推断该归哪种商队（HANDOFF 6.3 的商队归属表）。
 *
 * <h2>为什么要在代码里推断，而不是只改 shop.toml</h2>
 * <p>
 * 旧语义是「caravan 留空 = 所有商队通卖」，而 shop.toml 里只有陶瓦/混凝土显式写了 caravan，
 * 结果主世界、下界、末地三种商队的货架几乎一模一样（2026-09 玩家反馈）。
 * jar 更新<b>不会覆盖</b>线上的 {@code plugins/SkylliaTrader/config/shop.toml}，只改默认配置的话
 * 线上照样是三家同货——所以留空时改成按材质推断，存量配置不用手动覆盖也能分开。
 * 真想让某件商品三家通卖，显式写 {@code caravan = "ALL"}。
 * </p>
 *
 * <h2>判定用材质名字符串，不用枚举常量</h2>
 * <p>
 * 编译依赖是 paper-api 1.20.6，运行时是 26.x；朱砂、硫磺这类新材质在编译期的枚举里根本不存在。
 * 按 {@link Material#name()} 做子串匹配，新旧材质都能归对，也不会因为引用了不存在的常量编译失败。
 * </p>
 * <p>
 * 规则只列下界和末地，其余一律归主世界：主世界是植物/石材/海洋/矿物/深暗之域/结构战利品的大杂烩，
 * 列白名单反而容易漏。
 * </p>
 */
public final class CaravanAssignment {

    /** 材质名含有这些片段即归下界商队。NETHER 同时覆盖 NETHERRACK / NETHERITE_* / NETHER_STAR。 */
    private static final String[] NETHER_TOKENS = {
            "NETHER", "CRIMSON", "WARPED", "SOUL_", "BLACKSTONE", "BASALT", "QUARTZ",
            "GLOWSTONE", "SHROOMLIGHT", "MAGMA", "BLAZE", "GHAST", "WEEPING_VINES", "TWISTING_VINES",
            "ANCIENT_DEBRIS", "CRYING_OBSIDIAN", "PIGLIN", "HOGLIN", "STRIDER", "PIGSTEP",
            // 堡垒遗迹 / 下界要塞的锻造模板
            "SNOUT_ARMOR_TRIM", "RIB_ARMOR_TRIM"
    };

    /** 材质名含有这些片段即归末地商队。 */
    private static final String[] END_TOKENS = {
            "END_STONE", "END_ROD", "END_CRYSTAL", "PURPUR", "CHORUS", "SHULKER", "ELYTRA", "DRAGON",
            // 末地城的锻造模板
            "SPIRE_ARMOR_TRIM"
    };

    /**
     * 挑战任务在对应商队<b>召唤出来之前</b>就要交的材料，三家通卖。只按材质归属的话会形成死锁：
     * <ul>
     *   <li>{@code CHORUS_FRUIT}：{@code credential_end} 要 1024 个紫颂果才能换末地凭证，
     *       虚空末地又没有紫颂花——只放末地商队，末地凭证永远换不到，token_19 的鞘翅也跟着断。</li>
     *   <li>领地拓展令 9/10/12/16/17/18/20 要交的下界稀有物：岩浆膏、荧石（token_9，lore 写明
     *       「攒到声望 100，换下界的东西」）、绯红菌柄（token_10）、哭泣的黑曜石（token_12）、
     *       远古残骸（token_16/18 合成合金锭）、镀金黑石 + 下界合金升级模板（token_17）、
     *       下界合金锭（token_18）、下界之星（token_20）。只放下界商队的话，做 token_9 之前就得先拿
     *       下界凭证（声望 700 + 五种怪各杀 1000，猪灵/疣猪兽还要改群系），中期进度整段卡死
     *       （2026-09 服主拍板：这几件三家通卖）。</li>
     * </ul>
     * 加挑战任务时如果用到了只归下界/末地的材料，要么加进这里，要么确认玩家做那个挑战时已经能召唤对应商队。
     */
    private static final Set<String> SHARED = Set.of(
            "CHORUS_FRUIT",
            "MAGMA_CREAM", "GLOWSTONE", "CRIMSON_STEM", "CRYING_OBSIDIAN",
            "ANCIENT_DEBRIS", "GILDED_BLACKSTONE", "NETHERITE_UPGRADE_SMITHING_TEMPLATE",
            "NETHERITE_INGOT", "NETHER_STAR");

    private CaravanAssignment() {
    }

    /**
     * 按材质推断商队，识别不出来的归主世界。
     *
     * @return {@code null} 表示三家通卖（见 {@link #SHARED}）
     */
    public static @Nullable CaravanType infer(@NotNull Material material) {
        String name = material.name().toUpperCase(Locale.ROOT);
        if (SHARED.contains(name)) return null;
        if (containsAny(name, END_TOKENS)) return CaravanType.END;
        if (containsAny(name, NETHER_TOKENS)) return CaravanType.NETHER;
        return CaravanType.OVERWORLD;
    }

    private static boolean containsAny(String name, String[] tokens) {
        for (String token : tokens) {
            if (name.contains(token)) return true;
        }
        return false;
    }
}
