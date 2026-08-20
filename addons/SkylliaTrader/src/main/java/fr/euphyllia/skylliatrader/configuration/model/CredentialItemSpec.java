package fr.euphyllia.skylliatrader.configuration.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

/**
 * 一种商队凭证的<b>识别规则</b>（config.toml 的 {@code [credential.item.<商队>]}）。
 *
 * <h2>为什么用「材质 + CustomModelData」而不是 PDC</h2>
 * <p>
 * 凭证的唯一来源是挑战任务奖励，而 {@code SkylliaChallenge} 的 {@code ItemReward} 能设置的
 * 只有材质 / 数量 / 显示名 / lore / 附魔 / <b>customModelData</b>——它<b>不能</b>写 PDC。
 * 如果本插件按 PDC 识别凭证，服主就得先改 SkylliaChallenge 才能发凭证，
 * 规格里「发凭证是零代码的配置工作」这句话就不成立了。
 * </p>
 * <p>
 * 所以识别规则是 <b>材质匹配 且 CustomModelData 匹配</b>。CMD 是一个玩家无法凭空造出来的数值
 * （创造模式和管理员另说，那本来就绕不过），拿它当凭证的身份标识是这套 addon 体系里的既定做法。
 * </p>
 * <p>
 * <b>{@code material} 允许为 null</b>（配置里留空），表示只看 CMD 不看材质——服主想换凭证外观
 * 却不想动配置时可以这么用。但默认配置里是写死材质的：只看 CMD 的话，任何一个恰好带着同样 CMD
 * 的材质包物品都会被吃掉。
 * </p>
 *
 * @param material        凭证物品的材质；{@code null} 表示不校验材质
 * @param customModelData 凭证物品的 CustomModelData；<b>必须 &gt;= 0</b>，负数表示这种凭证被禁用
 * @param displayName     管理端 {@code /skylliadmin trader credential give} 发出的样品上写的名字
 *                        （MiniMessage）。<b>只影响样品，不参与识别</b>——玩家改个铁砧名字就能
 *                        伪造凭证的话这套判定就白做了
 */
public record CredentialItemSpec(@Nullable Material material, int customModelData, String displayName) {

    /** 这种凭证是否配置可用。CMD 为负数（或配置写错被夹成 -1）时整种凭证停用，不会误吃玩家物品。 */
    public boolean enabled() {
        return customModelData >= 0;
    }

    /**
     * 判断一个物品是不是这种凭证。
     * <p>
     * 对 {@code null}、空气、无 meta、无 CMD 的物品一律返回 false，不抛异常——
     * 这个方法会被挂在 {@code PlayerInteractEvent} 上，对着空手右键也会走进来。
     * </p>
     */
    public boolean matches(@Nullable ItemStack item) {
        if (!enabled()) return false;
        if (item == null || item.getType().isAir()) return false;
        if (material != null && item.getType() != material) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasCustomModelData()) return false;
        return meta.getCustomModelData() == customModelData;
    }
}
