package fr.euphyllia.skylliatrader.credential;

import fr.euphyllia.skylliatrader.merchant.CaravanType;
import fr.euphyllia.skylliatrader.merchant.MerchantOrigin;
import fr.euphyllia.skylliatrader.shop.ShopSession;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * 管理员专用的「游商测试凭证」：右键直接打开某个商队的货架，
 * 无视岛屿等级、声望、交易次数、限购、商队等级门槛、岛屿权限，也不需要召唤实体。
 *
 * <h2>和商队凭证的区别</h2>
 * <ul>
 *   <li>商队凭证按「材质 + CMD」识别（挑战任务只能写 CMD，见 {@code CredentialItemSpec}）；
 *       测试凭证只由 {@code /skylliadmin trader testpass} 发出，<b>按 PDC 识别</b>，
 *       玩家改名、配材质包都伪造不出来；</li>
 *   <li><b>使用时再判一次管理员权限</b>（{@link #PERMISSION}）：物品万一流到玩家手里也打不开，
 *       购买事务里还会再判一次。</li>
 * </ul>
 */
public final class AdminTestPass {

    /** 使用测试凭证需要的 Bukkit 权限，和 {@code /skylliadmin trader} 同一个。 */
    public static final String PERMISSION = "skyllia.trader.admin";

    /** PDC 里存的目标值：商队枚举名，或者这个表示路人游商货架的值。 */
    private static final String NATURAL_TARGET = "NATURAL";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final NamespacedKey key;

    public AdminTestPass(@NotNull Plugin plugin) {
        this.key = new NamespacedKey(plugin, "admin_test_pass");
    }

    /**
     * 解析命令参数：{@code overworld / nether / end} 是对应商队的凭证游商货架，
     * {@code natural} 是路人游商货架。无法识别返回 {@code null}。
     */
    public static @Nullable ShopSession parseTarget(@Nullable String raw) {
        if (raw == null) return null;
        if (NATURAL_TARGET.equalsIgnoreCase(raw.trim())) {
            return ShopSession.adminTest(MerchantOrigin.NATURAL, CaravanType.OVERWORLD);
        }
        CaravanType caravan = CaravanType.parseOrNull(raw);
        return caravan == null ? null : ShopSession.adminTest(MerchantOrigin.CREDENTIAL, caravan);
    }

    /** Tab 补全用的全部目标名。 */
    public static List<String> targetNames() {
        return List.of("overworld", "nether", "end", NATURAL_TARGET.toLowerCase(Locale.ROOT));
    }

    public @NotNull ItemStack build(@NotNull ShopSession session) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        String target = session.origin() == MerchantOrigin.NATURAL || session.caravan() == null
                ? NATURAL_TARGET : session.caravan().name();

        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, target);
        meta.displayName(MM.deserialize("<!italic><gold>🧪 游商测试凭证 · " + session.shelfLabel()));
        meta.lore(List.of(
                MM.deserialize("<!italic><red>管理员专用"),
                MM.deserialize("<!italic><dark_gray>─────────"),
                MM.deserialize("<!italic><gray>任意位置<white>右键</white>，直接打开<white>" + session.shelfLabel() + "</white>货架"),
                MM.deserialize("<!italic><gray>无视岛屿等级 / 声望 / 交易次数 / 限购 / 岛屿权限"),
                MM.deserialize("<!italic><dark_gray>─────────"),
                MM.deserialize("<!italic><gray>购买按原价扣金币，<white>不写入</white>任何岛屿数据"),
                MM.deserialize("<!italic><gray>没有 " + PERMISSION + " 权限的人右键无效")
        ));
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    /** 这件物品是不是测试凭证；是的话返回它对应的货架，否则 {@code null}。 */
    public @Nullable ShopSession read(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String target = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return target == null ? null : parseTarget(target);
    }
}
