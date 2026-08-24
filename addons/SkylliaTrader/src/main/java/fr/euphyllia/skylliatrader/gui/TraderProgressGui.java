package fr.euphyllia.skylliatrader.gui;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.bridge.IslandLevelBridge;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.TraderConfigManager;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import fr.euphyllia.skylliatrader.merchant.CaravanType;
import fr.euphyllia.skylliatrader.merchant.MerchantService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家端「游商进度指南」：只读展示四条解锁轨道（交易次数/岛屿等级/商会声望/累计消费）
 * 目前各自到了哪一档、下一档还差多少。T1 阶段没有购买/订单执行逻辑，这个菜单纯展示，
 * 不含任何会改变游戏状态的点击操作（除了「关闭」按钮）。
 * <p>
 * 布局风格仿 {@code SkylliaIslandValue} 的 {@code ScoreDetailGui}：边框用填充物、
 * 中间区域摆内容物品、底部一行放翻页/关闭之类的功能键。这里内容固定只有 4 项，用不上分页。
 * </p>
 *
 * <h2>线程模型（改之前先看这段）</h2>
 * <p>
 * 本菜单有两条调用入口，分别在<b>两个不同的线程</b>上：
 * </p>
 * <ul>
 *   <li>{@code /is trader} —— 核心的 {@code SkylliaCommand} 把所有子命令丢进
 *       {@code Bukkit.getAsyncScheduler().runNow(...)}，所以是<b>异步线程</b>；</li>
 *   <li>{@code /is gui} → 扩展功能 → 点游商入口 —— {@code GuiListener#onClick} 直接调
 *       {@code entry.onClick().accept(player)}，所以是<b>region tick 线程</b>。</li>
 * </ul>
 * <p>
 * 两条路都不能照着"当场读数据 + 当场 openInventory"写：
 * </p>
 * <ul>
 *   <li>{@link Player#openInventory} 在异步线程上会抛
 *       {@code IllegalStateException: Asynchronous InventoryOpenEvent}，
 *       而异常还会被 asyncScheduler 吞成一段控制台堆栈，玩家那边什么反应都没有；</li>
 *   <li>{@code TraderDataService#load} 和 {@code IslandLevelBridge} 都是<b>同步阻塞 JDBC</b>，
 *       在 region tick 线程上读库会让同一个 region 里的所有玩家一起卡住。</li>
 * </ul>
 * <p>
 * 所以统一成一个结构：<b>先在 async scheduler 上把数据一次取完，拿到数据后再回玩家线程
 * 建界面并打开</b>。核心的 {@code GuiSubCommand} 用的就是同一套 {@code player.getScheduler().run}
 * 范式（第三个参数是玩家下线时的回退任务，传 {@code null} 即可）。
 * </p>
 *
 * <h2>失败反馈</h2>
 * <p>
 * async 块里的每一步都可能抛：{@code SkylliaAPI#getIslandByPlayerId} 在缓存未命中时是同步
 * JDBC（连接池耗尽、SQLite 锁表都会抛）、{@code TraderDataService#load} 同理、
 * {@code IslandLevelBridge} 依赖的附属可能中途被卸载。而 asyncScheduler 会把异常吞成一段
 * 控制台堆栈，玩家侧<b>什么提示都没有、界面永远不开</b>——「点了没反应」是线上最难排查的反馈。
 * 所以 async 块整块包 try/catch：堆栈进日志，同时给玩家发一条明确的中文提示。
 * </p>
 */
public final class TraderProgressGui {

    private static final Logger log = LoggerFactory.getLogger(TraderProgressGui.class);
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int[] BORDER = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 50, 51, 52, 53};

    private TraderProgressGui() {
    }

    public static void open(@NotNull Player player) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;

        // 第一步：异步线程，把所有要读库的东西一次取完（只调一次 load()，不用多个 getter 各读一遍）。
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            // 整块包 try/catch 的理由见方法上方的「失败反馈」段落：这里面每一步都可能抛，
            // 而 asyncScheduler 会把异常吞成一段控制台堆栈，玩家那边永远等不到界面。
            try {
                Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
                if (island == null) {
                    player.sendMessage(Component.text("§c你还没有空岛，先创建一个空岛吧。"));
                    return;
                }

                TraderIslandData data = plugin.getDataService().load(island);
                boolean levelTrackAvailable = IslandLevelBridge.isAvailable();
                long islandLevel = levelTrackAvailable ? IslandLevelBridge.getIslandLevel(island) : 0L;
                TrackTiers tiers = TraderConfigLoader.config.getTrackTiers();

                // 第二步：回玩家线程建界面 + openInventory。
                player.getScheduler().run(plugin,
                        t -> render(player, island, data, tiers, islandLevel, levelTrackAvailable),
                        null);
            } catch (Throwable t) {
                // 堆栈进控制台给管理员排查，玩家侧给一句人话——「点了没反应」是最难收到有效反馈的故障。
                log.error("为玩家 {} 打开游商进度指南失败", player.getName(), t);
                // player.sendMessage 是线程安全的，不需要调度回玩家线程；
                // 而且这条提示恰恰是在「调度可能也出问题」的情况下要送达的，不该再绕一层调度。
                player.sendMessage(Component.text("§c读取游商数据失败，请稍后重试或联系管理员。"));
            }
        });
    }

    private static void render(@NotNull Player player, @NotNull Island island, @NotNull TraderIslandData data,
                               @NotNull TrackTiers tiers, long islandLevel, boolean levelTrackAvailable) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>🛒 游商进度指南"));

        for (int slot : BORDER) {
            inv.setItem(slot, GuiItem.filler());
        }

        inv.setItem(20, buildLongTrackItem(Material.GOLD_INGOT, "累计交易次数", "交易次数",
                data.tradeCount, tiers.tradeCount(), "开放基础生活物资池", true));

        inv.setItem(22, buildIslandLevelTrackItem(data, tiers, islandLevel, levelTrackAvailable));

        inv.setItem(24, buildLongTrackItem(Material.NETHER_STAR, "商会声望", "商会声望",
                data.reputation, tiers.reputation(), "开放钻石/下界合金/下界之星等稀有物资", true));

        inv.setItem(31, buildSpendingTrackItem(data.totalSpent, tiers.spending()));

        inv.setItem(40, GuiItem.of(Material.WRITABLE_BOOK, "<!italic><gold>📦 商队订单看板",
                List.of("<dark_gray>─────────",
                        "<gray>查看当前订单槽位，交材料换金币/物品 + 声望</gray>",
                        "<gray>声望是解锁钻石/下界合金等稀有物资的唯一途径</gray>",
                        "<dark_gray>─────────",
                        "<yellow>点击打开</yellow>")));
        holder.bind(40, e -> fr.euphyllia.skylliatrader.gui.order.TraderOrderBoardGui.open(player));

        inv.setItem(38, GuiItem.of(Material.HOPPER, "<!italic><gold>♻ 回收商品",
                List.of("<dark_gray>─────────",
                        "<gray>把背包里游商在卖的商品换成金币（原价的 40%）</gray>",
                        "<gray>每种商品每天各有独立额度</gray>",
                        "<dark_gray>─────────",
                        "<yellow>点击打开</yellow>")));
        holder.bind(38, e -> fr.euphyllia.skylliatrader.gui.shop.MerchantRecycleGui.open(player));

        inv.setItem(42, GuiItem.of(Material.BARRIER, "<!italic><red>强制撤离常驻商队",
                List.of("<dark_gray>─────────",
                        "<gray>商人卡住、找不到、杀不掉时用这个</gray>",
                        "<gray>会把本岛三种商队全部收回并释放名额</gray>",
                        "<gray>凭证不会被消耗，再右键就能重新放出</gray>",
                        "<dark_gray>─────────",
                        "<yellow>点击撤离</yellow>")));
        holder.bind(42, e -> {
            player.closeInventory();
            SkylliaTrader plugin = SkylliaTrader.getInstance();
            if (plugin == null) return;
            MerchantService service = plugin.getMerchantService();
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    service.recallAllCaravans(player, island);
                } catch (Throwable t) {
                    log.error("玩家 {} 强制撤离商队失败", player.getName(), t);
                    player.sendMessage(Component.text("§c撤离失败：服务器内部错误，请联系管理员。"));
                }
            });
        });

        inv.setItem(49, GuiItem.close());
        holder.bind(49, e -> player.closeInventory());

        player.openInventory(inv);
    }

    private static ItemStack buildLongTrackItem(Material material, String label, String unitLabel,
                                                 long value, List<Long> tierList, String unlockDesc,
                                                 boolean trackAvailable) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>当前" + unitLabel + "：<white>" + value + "</white></gray>");

        if (!trackAvailable) {
            lore.add("<dark_gray>─────────");
            lore.add("<red>未安装 SkylliaIslandLevel，本轨道暂不可用</red>");
            return GuiItem.of(material, "<!italic><gray>" + label, lore);
        }

        int index = TrackTiers.currentTierIndex(value, tierList);
        if (index < 0) {
            lore.add("<yellow>尚未达到第 1 档</yellow>");
            lore.add("<gray>还差 <white>" + (tierList.isEmpty() ? "?" : tierList.get(0) - value) + "</white> 达到第 1 档</gray>");
        } else {
            lore.add("<green>当前档位：第 " + (index + 1) + " / " + tierList.size() + " 档</green>");
            lore.add("<gray>档位门槛：<white>" + tierList.get(index) + "</white></gray>");
            if (index + 1 < tierList.size()) {
                long remain = tierList.get(index + 1) - value;
                lore.add("<gray>距下一档还差：<white>" + remain + "</white></gray>");
            } else {
                lore.add("<green>已达最高档</green>");
            }
        }
        lore.add("<dark_gray>─────────");
        lore.add("<gray>解锁内容：<white>" + unlockDesc + "</white></gray>");
        return GuiItem.of(material, "<!italic><light_purple>" + label, lore);
    }

    /**
     * 岛屿等级轨道的展示物品：在通用的 {@link #buildLongTrackItem} 基础上追加 T6 等级门槛信息
     * （HANDOFF 7.1/9.3）——凭证游商总名额天花板、下界/末地商队召唤权还差几级解锁。
     * 只追加不重写，避免和其余三条轨道共用的通用 lore 逻辑分叉出两份。
     */
    private static ItemStack buildIslandLevelTrackItem(@NotNull TraderIslandData data, @NotNull TrackTiers tiers,
                                                        long islandLevel, boolean trackAvailable) {
        ItemStack item = buildLongTrackItem(Material.GRASS_BLOCK, "岛屿等级", "岛屿等级",
                islandLevel, tiers.islandLevel(), "开放建材大宗商品", trackAvailable);
        if (!trackAvailable) {
            // 轨道本身都读不到（没装 SkylliaIslandLevel），再补等级门槛信息只会误导玩家。
            return item;
        }

        TraderConfigManager cfg = TraderConfigLoader.config;
        List<String> extra = new ArrayList<>();
        extra.add("<dark_gray>─────────");
        extra.add("<gray>凭证游商总名额上限：<white>"
                + data.effectiveCredentialSlots(cfg.defaultCredentialSlotsForLevel(islandLevel)) + "</white></gray>");
        if (data.credentialSlots >= 0) {
            extra.add("<gray>（管理员已为本岛单独设置，不随等级变化）</gray>");
        }
        extra.add(cfg.isCaravanUnlockedAtLevel(CaravanType.NETHER, islandLevel)
                ? "<green>下界商队召唤权：已解锁</green>"
                : "<yellow>下界商队召唤权：还差 <white>"
                        + (cfg.getNetherUnlockLevel() - islandLevel) + "</white> 级解锁</yellow>");
        extra.add(cfg.isCaravanUnlockedAtLevel(CaravanType.END, islandLevel)
                ? "<green>末地商队召唤权：已解锁</green>"
                : "<yellow>末地商队召唤权：还差 <white>"
                        + (cfg.getEndUnlockLevel() - islandLevel) + "</white> 级解锁</yellow>");

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) {
                lore.addAll(meta.lore());
            }
            for (String line : extra) {
                lore.add(MM.deserialize(line));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack buildSpendingTrackItem(double value, List<Double> tierList) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>累计消费：<white>" + GuiFormat.fmt(value) + "</white></gray>");

        int index = TrackTiers.currentTierIndex(value, tierList);
        if (index < 0) {
            lore.add("<yellow>尚未达到第 1 档</yellow>");
        } else {
            lore.add("<green>当前档位：第 " + (index + 1) + " / " + tierList.size() + " 档</green>");
            if (index + 1 < tierList.size()) {
                double remain = tierList.get(index + 1) - value;
                lore.add("<gray>距下一档还差：<white>" + GuiFormat.fmt(remain) + "</white></gray>");
            } else {
                lore.add("<green>已达最高档</green>");
            }
        }
        lore.add("<dark_gray>─────────");
        lore.add("<gray>该轨道<yellow>只提升折扣与限购加成</yellow></gray>");
        lore.add("<gray>不会解锁任何新商品</gray>");
        return GuiItem.of(Material.EMERALD, "<!italic><light_purple>累计消费", lore);
    }

}
