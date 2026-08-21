package fr.euphyllia.skylliatrader.shop;

import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.ShopConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.ShopItemDefinition;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回收事务（HANDOFF 9.3 T5）：真实经济，和 {@code ShopPurchaseService} / {@code OrderBoardService}
 * 同等级的风险，设计思路直接照抄它们已经走过审查的"先扣、后发、失败必回滚"范式，只是方向相反——
 * 购买是"先扣钱后发货"，回收是"先扣物品后付钱"，这正好和 {@code OrderBoardService}
 * （先扣材料、再发放订单奖励）方向完全一致，本类照抄它的时序。
 *
 * <h2>只回收游商自己有卖的商品，不接受任意材质</h2>
 * <p>
 * 判定依据是 {@code shop.toml}（{@link ShopConfigLoader}）——该材质必须存在对应的
 * {@link ShopItemDefinition}，否则拒绝（"这件东西游商不收"）。这条规则和商品是否已解锁无关：
 * 回收不受解锁进度约束（见 {@code MerchantRecycleGui} 类文档），玩家背包里任何一件游商在卖的商品
 * 都能拿来换钱，哪怕这座岛还没解锁到能买它。
 * </p>
 *
 * <h2>回收价：基础原价的 40%，不是折扣后的价格</h2>
 * <p>
 * 严格按 HANDOFF 9.3 拍板：{@code ShopItemDefinition#price()}（折扣前原价）× 0.4，四舍五入到分
 * （{@link #recyclePriceFor}）。<b>刻意不调用 {@code ShopEconomics#discountedUnitPrice}</b>——
 * 用玩家当前的消费折扣价格来算回收价，会让"先攒够消费折扣、再回收套一层折扣的折扣"变成一层
 * 不该有的额外套利，这是明确拍板的规则。同理，回收也<b>不消耗/不参考</b>限购计数、
 * 不影响 {@code tradeCount}/{@code reputation}/{@code totalSpent}——它是"卖"，不是"买"，
 * 不该反向计入消费额统计口径。
 * </p>
 *
 * <h2>为什么没有 {@code TraderDataService#compute} 临界区</h2>
 * <p>
 * {@code ShopPurchaseService}/{@code OrderBoardService} 都要在临界区里做限购余量判定和记账
 * （限购计数/交易次数/消费额/声望/每日收入——全是<b>整座岛屿共享</b>的状态，必须靠岛屿维度的
 * 互斥锁裁决并发）。回收<b>完全不碰任何这类共享状态</b>：不限购、不计入 tradeCount/reputation/
 * totalSpent，唯一需要"权威判定"的只有"这件商品现在的回收单价是多少"——而这直接来自
 * {@code shop.toml} 的内存快照，不是数据库状态，两名玩家同时回收同一种材质也不存在互相抢占的
 * 资源（不像限购商品的"最后一件"）。所以本类<b>刻意省掉了 compute 临界区</b>，物品扣除成功后
 * 直接进 async 重新从配置取一次"当下"的商品定价（不信任玩家点击那一刻的价格快照，理由同
 * {@code OrderBoardService} 的"临界区重新从配置取活订单定义"），随后同步走 Vault 发钱——
 * 这是本轮唯一偏离"照抄 OrderBoardService 六步时序"模板的地方，偏离的理由就是上面这段。
 * </p>
 *
 * <h2>Folia 线程模型</h2>
 * <ol>
 *   <li>GUI 点击回调在 region tick 线程上被调用——入口 {@link #recycle} 只读事件参数就立刻
 *       转 async；</li>
 *   <li>商品定义校验（是否在 {@code shop.toml} 里）、Vault 存款全部在
 *       {@code Bukkit.getAsyncScheduler()} 上；</li>
 *   <li>背包操作（扫描持有量、扣除物品、失败时退还）必须回<b>玩家自己的调度器</b>
 *       （{@code player.getScheduler().run}）——{@code EntityScheduler#run} 在玩家已经完全
 *       下线时会直接返回 {@code null} 且<b>不调用 retired 回调</b>，本类每一次背包操作都
 *       显式检查了这个返回值（T2/T3/T4 已经踩过的坑）。</li>
 * </ol>
 */
public final class RecycleService {

    private static final Logger log = LoggerFactory.getLogger(RecycleService.class);

    /** 回收价折算比例：基础单价（折扣前原价）的 40%，HANDOFF 9.3 已拍板，不要改成读配置。 */
    private static final double RECYCLE_RATE = 0.40;

    private final SkylliaTrader plugin;

    /** 正在走回收流程的玩家，防止连点同一个格子把同一次回收触发两次（理由同 ShopPurchaseService#inFlight）。 */
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();

    public RecycleService(@NotNull SkylliaTrader plugin) {
        this.plugin = plugin;
    }

    /**
     * 商品的回收单价：基础原价 × 40%，四舍五入到分。{@code MerchantRecycleGui} 展示的价格和本类
     * 事务实际结算的价格必须走<b>同一个</b>方法，避免两处各写一份除以 0.4 的算式、改一处忘另一处。
     */
    public static double recyclePriceFor(@NotNull ShopItemDefinition item) {
        return ShopEconomics.round2(item.price() * RECYCLE_RATE);
    }

    /**
     * 玩家在回收 GUI 里点击一件商品。<b>必须在玩家线程或 region tick 线程上调用</b>
     * （只读事件参数，随后立刻转 async）。
     *
     * @param shopItemId 商品 id（必须存在于 {@code shop.toml}，说明书不可回收，
     *                   见类文档"只回收游商自己有卖的商品"）
     * @param mode       点击语义
     */
    public void recycle(@NotNull Player player, @NotNull String shopItemId, @NotNull RecycleMode mode) {
        UUID playerId = player.getUniqueId();
        if (inFlight.putIfAbsent(playerId, Boolean.TRUE) != null) {
            player.sendMessage(Component.text("§e上一次回收还在处理中，请稍等一下。"));
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                recycleAsyncStage(player, shopItemId, mode);
            } catch (Throwable t) {
                // asyncScheduler 会把异常吞成一段控制台堆栈，玩家侧「点了没反应」——
                // 这里必须自己兜底提示，理由同 ShopPurchaseService/OrderBoardService。
                log.error("玩家 {} 回收商品 '{}' 时发生未预期的异常", player.getName(), shopItemId, t);
                player.sendMessage(Component.text("§c回收失败：服务器内部错误，请联系管理员。"));
                inFlight.remove(playerId);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 1 步（async）：校验商品是否可回收 + 确认 Vault 可用
    // ══════════════════════════════════════════════════════════════════════

    private void recycleAsyncStage(Player player, String shopItemId, RecycleMode mode) {
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            fail(player, "§c游商当前不可用：服务器没有安装经济插件（Vault），请联系管理员。");
            return;
        }
        Economy econ = rsp.getProvider();

        String normalizedId = ShopItemDefinition.normalizeId(shopItemId);
        ShopItemDefinition item = ShopConfigLoader.config.findById(normalizedId);
        if (item == null) {
            fail(player, "§c这件东西游商不收。");
            return;
        }

        var scheduled = player.getScheduler().run(plugin,
                t -> materialScanAndDeductStage(player, normalizedId, item.material(), item.displayName(), mode, econ),
                () -> {
                    // 从这一步开始才会真正碰背包，玩家在此之前下线的话什么都还没发生，
                    // 直接释放 inFlight 即可，不需要任何回滚（理由同 OrderBoardService）。
                    inFlight.remove(player.getUniqueId());
                });
        if (scheduled == null) {
            inFlight.remove(player.getUniqueId());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 2 步（玩家线程）：扫描背包 → 确定实际可回收数量 → 扣除
    // ══════════════════════════════════════════════════════════════════════

    private void materialScanAndDeductStage(Player player, String normalizedId, Material material,
                                             String displayName, RecycleMode mode, Economy econ) {
        PlayerInventory inv = player.getInventory();
        int owned = countMaterial(inv, material);
        if (owned <= 0) {
            fail(player, "§c你的背包里没有 " + displayName + "。");
            return;
        }

        int quantity = switch (mode) {
            case SINGLE -> 1;
            case QUINTUPLE -> Math.min(5, owned);
            case ALL -> owned;
        };

        Map<Integer, ItemStack> leftover = inv.removeItem(new ItemStack(material, quantity));
        if (!leftover.isEmpty()) {
            // ⚠️ 不完全是"理论上不该发生"：Inventory#removeItem 内部按 ItemStack#isSimilar
            // （材质 + 完整组件，含耐久/附魔/自定义名字）匹配，而这里传的是一个没有任何组件的
            // 裸物品；countMaterial 只按材质统计持有量，两边口径不一致。已用 Shiroha 源码核实
            // （2026-08-21 审查）：当前商品目录里 ELYTRA（鞘翅）一旦被使用过（耐久非满），
            // 就会确定性地、每次都命中这条分支——不是并发异常，是可复现的已知限制。
            // 防御性处理：把已经扣掉的部分（quantity 减去扣不动的部分）退回去，这一步的计算
            // 本身是安全的（不会退多退少），只是"扣不干净"这件事本身目前拿这类物品没办法。
            int notRemoved = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int actuallyRemoved = quantity - notRemoved;
            if (actuallyRemoved > 0) {
                giveBack(player, material, actuallyRemoved);
            }
            if (actuallyRemoved == 0) {
                // 一件都没扣到：多半就是上面说的"耐久/附魔/改名等组件不匹配"，不是并发异常，
                // 给玩家一个准确的解释而不是"请重试"（重试对这种情况永远没用），
                // 日志降级成 warn 且措辞明确，避免和真正的并发/背包异常混在一起污染日志。
                log.warn("玩家 {} 回收商品 '{}' 时完全没能匹配到对应物品（可能因为耐久/附魔/"
                        + "自定义名字等属性和全新状态不一致），已跳过", player.getName(), normalizedId);
                fail(player, "§c这件 " + displayName + " 带有耐久损耗、附魔或自定义属性，"
                        + "游商只收全新状态的同款物品。");
            } else {
                // 只扣到了一部分：这种情况更像是背包在扣除过程中真的被并发改动了，
                // 维持原有的"请重试"提示和 error 级别日志。
                log.error("玩家 {} 回收商品 '{}' 时物品扣除中途只扣到部分（已退还已扣部分），"
                        + "可能是背包在扣除过程中被其它代码并发改动", player.getName(), normalizedId);
                fail(player, "§c物品扣除失败，请重试。");
            }
            return;
        }

        // 物品已经真的从背包里拿走了——从这里开始任何失败都必须"退物品"，见 refundItems。
        Bukkit.getAsyncScheduler().runNow(plugin,
                at -> payStage(player, normalizedId, material, displayName, quantity, econ));
    }

    private int countMaterial(PlayerInventory inv, Material material) {
        int total = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack == null || stack.getType() != material) continue;
            total += stack.getAmount();
        }
        return total;
    }

    private void giveBack(Player player, Material material, int amount) {
        if (amount <= 0) return;
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, amount));
        for (ItemStack over : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), over);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 3 步（async）：重新从配置取活商品定价 → 发钱（不经过 compute 临界区，见类文档）
    // ══════════════════════════════════════════════════════════════════════

    private void payStage(Player player, String normalizedId, Material material, String displayName,
                          int quantity, Economy econ) {
        UUID playerId = player.getUniqueId();

        // 重新从配置里取一份"当下"的商品定义（不信任玩家点击那一刻的价格快照，理由同
        // OrderBoardService「临界区重新从配置取活订单定义」）：物品扣除这段时间里配置可能被
        // /skyllia reload 改过价格，甚至整条商品被管理员删掉——按硬规则 3，商品一旦不在
        // shop.toml 里就不该继续回收，这里必须重新校验一次。
        ShopItemDefinition liveItem = ShopConfigLoader.config.findById(normalizedId);
        if (liveItem == null) {
            log.warn("玩家 {} 回收商品 '{}' 时该商品已从 shop.toml 移除，已退还物品",
                    player.getName(), normalizedId);
            refundItems(player, material, quantity, "商品已下架");
            fail(player, "§c这件东西游商刚刚不收了，物品已退还。");
            return;
        }

        double unitPrice = recyclePriceFor(liveItem);
        double totalPrice = ShopEconomics.round2(unitPrice * quantity);

        try {
            EconomyResponse response = econ.depositPlayer(player, totalPrice);
            if (response == null || !response.transactionSuccess()) {
                String reason = response == null ? "经济插件未返回结果" : response.errorMessage;
                log.error("玩家 {} 回收商品 '{}' 发钱失败：{}，已退还物品",
                        player.getName(), normalizedId, reason);
                refundItems(player, material, quantity, "发钱失败");
                fail(player, "§c回收失败：发钱异常，物品已退还，请稍后重试或联系管理员。");
                return;
            }
        } catch (Throwable t) {
            // econ 是另一个插件的代码，不能假设它只会通过 EconomyResponse 表达失败、
            // 不会直接抛异常（理由同 ShopPurchaseService#chargeStage）。
            log.error("玩家 {} 回收商品 '{}' 发钱阶段抛出异常，已退还物品", player.getName(), normalizedId, t);
            refundItems(player, material, quantity, "发钱异常");
            fail(player, "§c回收失败：经济插件出错，物品已退还，请稍后重试或联系管理员。");
            return;
        }

        // player.sendMessage 是线程安全的（ShopPurchaseService/OrderBoardService 已有同样的用法），
        // 不需要跳回玩家线程。
        player.sendMessage(Component.text("§a回收成功：§f" + displayName + " x" + quantity
                + " §a，获得 §f" + GuiFormat.fmt(totalPrice) + " §a金币"));
        inFlight.remove(playerId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 回滚
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 把物品退还给玩家，用于"物品已经真的被扣掉，但钱没能发出去"这个最危险的分支。
     * <b>必须回玩家线程</b>；玩家已经彻底下线时没有安全的方式把物品塞回一个离线玩家的背包，
     * 只能把细节完整记进错误日志，交给管理员手动补偿——这条路径和
     * {@code ShopPurchaseService}/{@code OrderBoardService} 对失败退还的处理是同一个思路。
     */
    private void refundItems(Player player, Material material, int quantity, String reasonForLog) {
        var scheduled = player.getScheduler().run(plugin, t -> giveBack(player, material, quantity),
                () -> logRefundFailure(player, material, quantity, reasonForLog));
        if (scheduled == null) {
            logRefundFailure(player, material, quantity, reasonForLog);
        }
    }

    private void logRefundFailure(Player player, Material material, int quantity, String reasonForLog) {
        log.error("玩家 {} 的回收物品退还失败：玩家已下线（原因：{}），需要管理员手动补偿 {} x{}",
                player.getName(), reasonForLog, material, quantity);
    }

    private void fail(Player player, String message) {
        player.sendMessage(Component.text(message));
        inFlight.remove(player.getUniqueId());
    }
}
