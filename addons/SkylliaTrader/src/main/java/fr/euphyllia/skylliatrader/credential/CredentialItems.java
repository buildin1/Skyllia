package fr.euphyllia.skylliatrader.credential;

import fr.euphyllia.skylliatrader.configuration.model.CredentialItemSpec;
import fr.euphyllia.skylliatrader.merchant.CaravanType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 按配置造出一张商队凭证的<b>样品</b>。
 *
 * <h2>这不是发放渠道</h2>
 * <p>
 * 凭证的正式来源是<b>挑战任务奖励</b>（{@code SkylliaChallenge} 的 {@code ItemReward}
 * 已经支持 {@code customModelData}，服主在 challenge 配置里填上同一个 CMD 就行，零代码）。
 * 本类只服务于 {@code /skylliadmin trader credential give}：让服主先拿一张出来对一下
 * 材质包模型对不对、右键能不能召唤，再去配挑战奖励。
 * </p>
 * <p>
 * <b>识别只看材质 + CMD</b>（见 {@link CredentialItemSpec}），所以这里加的名字和 lore
 * 纯属好看，挑战任务发的凭证不写这些也照样能用。
 * </p>
 */
public final class CredentialItems {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private CredentialItems() {
    }

    /**
     * 造一张凭证样品。
     *
     * @return 凭证物品；这种凭证被停用（CMD &lt; 0，通常是配置里 CMD 撞车被自动停用）时返回 {@code null}
     */
    public static ItemStack build(@NotNull CaravanType caravan, @NotNull CredentialItemSpec spec, int amount) {
        if (!spec.enabled()) return null;

        Material material = spec.material() != null ? spec.material() : Material.PAPER;
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MM.deserialize("<!italic>" + spec.displayName()));
        meta.lore(List.of(
                MM.deserialize("<!italic><dark_gray>─────────"),
                MM.deserialize("<!italic><gray>对着主岛上的<white>实心方块右键</white>："),
                MM.deserialize("<!italic><gray>没有商人就<white>放出</white>，已经有了就<white>收回</white>。"),
                MM.deserialize("<!italic><gray>不能对着空气，商人会出现在方块上方 2 格。"),
                MM.deserialize("<!italic><dark_gray>─────────"),
                MM.deserialize("<!italic><yellow>每座岛每种商队只能有 1 个。"),
                MM.deserialize("<!italic><gray>凭证是开关，<white>不会被消耗</white>，可以反复使用。")
        ));
        // CustomModelData 是识别凭证的唯一依据，必须设。
        meta.setCustomModelData(spec.customModelData());
        item.setItemMeta(meta);
        return item;
    }
}
