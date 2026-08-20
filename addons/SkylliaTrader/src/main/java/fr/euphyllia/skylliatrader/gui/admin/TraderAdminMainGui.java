package fr.euphyllia.skylliatrader.gui.admin;

import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理端统一入口菜单：{@code /skylliadmin trader gui}。
 * <p>
 * T1 只把框架和入口摆出来——订单管理指向真正能用的只读列表 {@link TraderOrderListGui}；
 * 商人管理、凭证管理这两块要等 T2（游商实体召唤/回收）和 T4（凭证发放）才有内容，
 * 这里先用 {@link GuiItem#placeholder} 占位，点了没有任何效果，图标上会显示"开发中"。
 * </p>
 * <p>
 * 线程模型见 {@code TraderProgressGui} 的类注释：{@code /skylliadmin} 的子命令同样跑在
 * async scheduler 上（{@code SkylliaAdminCommand}），直接 {@code openInventory} 会抛
 * {@code Asynchronous InventoryOpenEvent}。本菜单不读数据库（档位表是内存里的配置快照），
 * 所以只需要把"建界面 + 打开"这一步调度回玩家线程。
 * </p>
 */
public final class TraderAdminMainGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** 27 格菜单的边框：除去 4（总览）与 11/13/15（三个入口）之外全部填满，中间行不留洞。 */
    private static final int[] BORDER = {
            0, 1, 2, 3, 5, 6, 7, 8,
            9, 10, 12, 14, 16, 17,
            18, 19, 20, 21, 23, 24, 25, 26
    };

    private TraderAdminMainGui() {
    }

    public static void open(@NotNull Player player) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;
        player.getScheduler().run(plugin, t -> render(player), null);
    }

    private static void render(@NotNull Player player) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 27, MM.deserialize("<light_purple>🛒 游商管理"));

        for (int slot : BORDER) {
            inv.setItem(slot, GuiItem.filler());
        }

        inv.setItem(11, GuiItem.of(Material.WRITTEN_BOOK, "<!italic><green>📦 订单管理",
                List.of("<dark_gray>─────────",
                        "<gray>查看/新增/编辑/删除 orders.toml 里的商队订单</gray>")));
        holder.bind(11, e -> TraderOrderListGui.open(player, 0));

        inv.setItem(13, GuiItem.of(Material.VILLAGER_SPAWN_EGG, "<!italic><green>🧑 商人管理",
                List.of("<dark_gray>─────────",
                        "<gray>查看某座岛登记的常驻商人：</gray>",
                        "<white>/skylliadmin trader merchants <玩家></white>",
                        "<gray>强制释放一种商队的名额：</gray>",
                        "<white>/skylliadmin trader release <玩家> <商队></white>",
                        "<dark_gray>─────────",
                        "<gray>（图形化管理界面见后续阶段）</gray>")));

        inv.setItem(15, GuiItem.placeholder(Material.PAPER, "🎫 凭证管理",
                "按岛发放/回收召唤名额（T4 实现）；"
                        + "凭证样品见 /skylliadmin trader credential"));

        inv.setItem(4, buildTierSummaryItem());

        inv.setItem(22, GuiItem.close());
        holder.bind(22, e -> player.closeInventory());

        player.openInventory(inv);
    }

    private static org.bukkit.inventory.ItemStack buildTierSummaryItem() {
        TrackTiers tiers = TraderConfigLoader.config.getTrackTiers();
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>交易次数：<white>" + tiers.tradeCount() + "</white></gray>");
        lore.add("<gray>岛屿等级：<white>" + tiers.islandLevel() + "</white></gray>");
        lore.add("<gray>商会声望：<white>" + tiers.reputation() + "</white></gray>");
        lore.add("<gray>累计消费：<white>" + tiers.spending() + "</white></gray>");
        lore.add("<dark_gray>─────────");
        lore.add("<gray>档位数值改 config.toml 后 <yellow>/skyllia reload</yellow></gray>");
        return GuiItem.of(Material.NETHER_STAR, "<!italic><light_purple>四轨当前档位配置", lore);
    }
}
