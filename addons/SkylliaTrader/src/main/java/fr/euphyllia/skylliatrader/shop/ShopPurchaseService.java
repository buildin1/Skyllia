package fr.euphyllia.skylliatrader.shop;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.bridge.IslandLevelBridge;
import fr.euphyllia.skylliatrader.configuration.ShopConfigLoader;
import fr.euphyllia.skylliatrader.configuration.ShopConfigManager;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.GuidebookConfig;
import fr.euphyllia.skylliatrader.configuration.model.ShopItemDefinition;
import fr.euphyllia.skylliatrader.configuration.model.ShopPurchaseLimitPeriod;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import fr.euphyllia.skylliatrader.data.PurchaseCounter;
import fr.euphyllia.skylliatrader.data.TraderDataService;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import fr.euphyllia.skylliatrader.merchant.MerchantOrigin;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商店购买事务：全系统本轮风险最高的代码（HANDOFF 6.8）。没有运行时测试环境，
 * 每一条失败分支都要在这里能读出"失败了会发生什么"。
 *
 * <h2>严格顺序</h2>
 * <p>
 * 校验解锁资格 → 校验限购余量 → 计算折扣后价格 → 扣款 → 扣款成功后发货 → 发货成功后才落库。
 * </p>
 *
 * <h2>为什么限购计数是"先预留、失败再回滚"，不是字面意义上的"发货成功后才写库"</h2>
 * <p>
 * 硬规则 3 要求"限购判定与扣减必须在同一把岛屿互斥锁的临界区内完成，不能有先读后写的并发窗口"。
 * 但 {@link TraderDataService#compute} 的临界区<b>不允许做任何阻塞 IO</b>（包括 Vault 调用——
 * 它可能是另一个插件的同步数据库操作），所以"判定限购"和"实际扣钱"物理上不可能挤进同一次
 * 临界区调用。
 * </p>
 * <p>
 * 解决办法和 T2 的凭证 CAS 占位是<b>同一个模式</b>（HANDOFF 6.9："CAS 占位 → 生成成功 →
 * 才消耗凭证，任一步失败回滚占位"）：先在一次 {@code compute} 里原子地"预留"这次购买
 * （限购计数 +quantity、交易次数 +1、消费额 +totalPrice，一次写库），<b>再</b>去扣 Vault 的钱、
 * 再发货；扣款失败、发货失败、或者玩家在这中间下线，一律用另一次 {@code mutate} 把预留原样
 * 减回去（必要时还要退款）。这样"限购判定+扣减"仍然是原子的（两名成员不可能都预留到最后一件
 * 限购商品），而玩家最终看到的效果仍然是"先扣钱后发货，失败等于什么都没发生"。
 * </p>
 *
 * <h2>Folia 线程模型</h2>
 * <ol>
 *   <li>GUI 点击回调在 region tick 线程上被调用（{@code SkylliaGuiHolder} 的约定）——
 *       本类的入口 {@link #purchase} 只读一下事件参数就立刻把活派发到 async；</li>
 *   <li>解锁校验 / 限购预留 / Vault 扣款全部在 {@code Bukkit.getAsyncScheduler()} 上
 *       （同步阻塞 JDBC + 可能阻塞的 Vault 调用，绝不能上 region tick）；</li>
 *   <li>背包发货必须回<b>玩家自己的调度器</b>（{@code player.getScheduler().run}）——
 *       {@code EntityScheduler#run} 在玩家已经完全下线时会直接返回 {@code null} 且<b>不调用
 *       retired 回调</b>，见 {@link #deliver} 里对返回值的显式检查（T2 在凭证召唤上踩过这个坑）。</li>
 * </ol>
 */
public final class ShopPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(ShopPurchaseService.class);

    private final SkylliaTrader plugin;
    private final TraderDataService dataService;

    /** 正在走购买流程的玩家，防止连点同一个格子把同一次购买触发两次（同一玩家维度，理由同 {@code MerchantService#summonInFlight}）。 */
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();

    public ShopPurchaseService(@NotNull SkylliaTrader plugin, @NotNull TraderDataService dataService) {
        this.plugin = plugin;
        this.dataService = dataService;
    }

    /**
     * 预留成功/失败的结果，只在 async 线程内部流转，不跨线程共享可变状态。
     * <p>
     * {@code isGuidebook} 必须跟着结果一起传递（而不是在回滚时重新判断）：{@link #reserve} 预留时
     * 说明书不计入 {@code tradeCount}（见该方法文档），{@link #undoReservation} 回滚时必须用
     * <b>同一个判断</b>决定要不要把 {@code tradeCount} 加回去——两处判断逻辑分开写，
     * 任何一处以后改了分类规则都会导致"预留没加、回滚却减了"的账目错位。
     * </p>
     */
    private record Reservation(boolean ok, String failMessage, int quantity, double unitPrice,
                                double basePrice, double totalPrice, boolean isGuidebook) {
        static Reservation fail(String message) {
            return new Reservation(false, message, 0, 0, 0, 0, false);
        }

        static Reservation ok(int quantity, double unitPrice, double basePrice, double totalPrice, boolean isGuidebook) {
            return new Reservation(true, null, quantity, unitPrice, basePrice, totalPrice, isGuidebook);
        }
    }

    /**
     * 玩家在商店 GUI 里点击购买一件商品。<b>必须在玩家线程或 region tick 线程上调用</b>
     * （只读事件参数，不做阻塞操作，随后立刻转 async）。
     *
     * @param player     购买的玩家
     * @param origin     这个游商的来源（决定这件商品是否真的在它的售卖范围内，防御性校验）
     * @param shopItemId 商品 id（{@link ShopConfigManager#GUIDEBOOK_RESERVED_ID} 表示说明书）
     * @param mode       点击语义
     */
    public void purchase(@NotNull Player player, @NotNull MerchantOrigin origin,
                         @NotNull String shopItemId, @NotNull PurchaseMode mode) {
        UUID playerId = player.getUniqueId();
        if (inFlight.putIfAbsent(playerId, Boolean.TRUE) != null) {
            player.sendMessage(Component.text("§e上一次购买还在处理中，请稍等一下。"));
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                purchaseAsyncStage(player, origin, shopItemId, mode);
            } catch (Throwable t) {
                // asyncScheduler 会把异常吞成一段控制台堆栈，玩家侧「点了没反应」——
                // 这里必须自己兜底提示，理由同 TraderProgressGui / MerchantService。
                log.error("玩家 {} 购买商品 '{}' 时发生未预期的异常", player.getName(), shopItemId, t);
                player.sendMessage(Component.text("§c购买失败：服务器内部错误，请联系管理员。"));
                inFlight.remove(playerId);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 1 步（async）：解析商品 + 校验解锁资格 + 校验限购余量 + 计算折扣价 + 预留
    // ══════════════════════════════════════════════════════════════════════

    private void purchaseAsyncStage(Player player, MerchantOrigin origin, String shopItemId, PurchaseMode mode) {
        UUID playerId = player.getUniqueId();

        // Vault 是否可用要最先查——这是和岛屿状态无关的全局前提，先查完可以避免白做一次预留+回滚。
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            fail(player, "§c商店当前不可用：服务器没有安装经济插件（Vault），请联系管理员。");
            return;
        }
        Economy econ = rsp.getProvider();

        Island island = SkylliaAPI.getIslandByPlayerId(playerId);
        if (island == null) {
            fail(player, "§c你还没有空岛，先创建一个空岛吧。");
            return;
        }

        long islandLevel = IslandLevelBridge.isAvailable() ? IslandLevelBridge.getIslandLevel(island) : 0L;
        TrackTiers tiers = TraderConfigLoader.config.getTrackTiers();

        String normalizedId = ShopItemDefinition.normalizeId(shopItemId);
        Reservation reservation;
        String displayName;
        Material material;

        if (ShopConfigManager.GUIDEBOOK_RESERVED_ID.equals(normalizedId)) {
            GuidebookConfig guideConfig = TraderConfigLoader.config.getGuidebook();
            if (guideConfig == null || !guideConfig.enabled()) {
                fail(player, "§c说明书当前未开放购买。");
                return;
            }
            if (origin != MerchantOrigin.NATURAL) {
                // 说明书只在路人商人的货架上出现（MerchantOfferScope.credential().guidebook() == false）。
                // 这里不应该被正常 GUI 触发，属于防御性兜底。
                fail(player, "§c这位商人不卖说明书。");
                return;
            }
            material = guideConfig.material();
            displayName = "说明书";
            ShopPurchaseLimitPeriod period = guideConfig.purchaseLimit() > 0
                    ? ShopPurchaseLimitPeriod.LIFETIME : ShopPurchaseLimitPeriod.NONE;
            reservation = dataService.compute(island, data ->
                    reserve(data, normalizedId, guideConfig.price(), period, guideConfig.purchaseLimit(),
                            material, mode, tiers, true));
        } else {
            ShopItemDefinition item = ShopConfigLoader.config.findById(normalizedId);
            if (item == null) {
                fail(player, "§c这件商品不存在，可能是配置刚刚被重新加载，请重新打开商店。");
                return;
            }
            if (origin == MerchantOrigin.NATURAL && !item.naturalVisible()) {
                fail(player, "§c这位商人不卖这件商品。");
                return;
            }
            material = item.material();
            displayName = item.displayName();
            int baseLimit = ShopEconomics.baseLimitCountFor(item, islandLevel);
            reservation = dataService.compute(island, data -> {
                if (!ShopVisibility.isUnlocked(item, data, islandLevel)) {
                    return TraderDataService.Mutation.readOnly(Reservation.fail("§c你还没有解锁这件商品。"));
                }
                return reserve(data, normalizedId, item.price(), item.purchaseLimitPeriod(), baseLimit,
                        material, mode, tiers, false);
            });
        }

        if (!reservation.ok()) {
            fail(player, reservation.failMessage());
            return;
        }

        // 从这里开始，预留（限购计数 / 交易次数 / 消费额）已经落库了。chargeStage 自己包了
        // try/catch（包括 econ.has/withdrawPlayer 直接抛异常这种没走 EconomyResponse 失败分支、
        // 而是整个方法炸掉的情况），失败时会自己回滚 + 提示，这里只需要看返回值决定要不要发货——
        // deliver() 不放进同一个 try/catch：它内部对"调度失败/玩家下线/发货异常"各自有一套
        // 独立的回滚+退款逻辑（见该方法），混在一起容易出现同一次预留被回滚两次的账目错位。
        if (chargeStage(player, island, normalizedId, displayName, reservation, econ)) {
            deliver(player, island, normalizedId, displayName, material, reservation, econ);
        }
    }

    /**
     * 扣款。<b>预留已经在调用前落库</b>，本方法内部任何失败都必须回滚，所以整个方法体包在
     * try/catch 里——{@code econ.has}/{@code econ.withdrawPlayer} 是另一个插件的代码，
     * 不能假设它只会通过 {@link EconomyResponse} 表达失败、不会直接抛异常。
     *
     * @return {@code true} 表示扣款成功，调用方应该继续发货；{@code false} 表示已经处理完
     * 失败分支（回滚 + 提示玩家），调用方不需要再做任何事
     */
    private boolean chargeStage(Player player, Island island, String normalizedId, String displayName,
                                Reservation reservation, Economy econ) {
        try {
            OfflinePlayer offline = player;
            if (!econ.has(offline, reservation.totalPrice())) {
                rollbackReservationOnly(island, normalizedId, reservation);
                fail(player, "§c金币不足，购买 " + GuiFormat.legacyName(displayName) + " §cx" + reservation.quantity()
                        + " 需要 §f" + GuiFormat.fmt(reservation.totalPrice()) + " §c金币。");
                return false;
            }
            EconomyResponse response = econ.withdrawPlayer(offline, reservation.totalPrice());
            if (response == null || !response.transactionSuccess()) {
                rollbackReservationOnly(island, normalizedId, reservation);
                String reason = response == null ? "经济插件未返回结果" : response.errorMessage;
                log.error("玩家 {} 购买商品 '{}' 扣款失败：{}", player.getName(), normalizedId, reason);
                fail(player, "§c扣款失败，请稍后重试或联系管理员。");
                return false;
            }
            return true;
        } catch (Throwable t) {
            log.error("玩家 {} 购买商品 '{}' 扣款阶段抛出异常（预留已回滚）", player.getName(), normalizedId, t);
            rollbackReservationOnly(island, normalizedId, reservation);
            fail(player, "§c购买失败：经济插件出错，请稍后重试或联系管理员。");
            return false;
        }
    }

    /**
     * 在一次 {@code compute} 临界区内完成："校验限购余量 → 计算折扣价 → 预留（限购计数 + 交易次数
     * + 消费额）"。<b>只做纯内存操作</b>，不碰 Vault、不发消息（{@code mutator} 的硬性要求，见
     * {@code TraderDataService} 类文档）。
     *
     * @param isGuidebook 说明书不计入"累计交易次数"——它是新手引导道具，不是一次"交易"，
     *                    理由同 T2 对说明书购买路径的定位（HANDOFF：说明书是防"不知道怎么玩"的兜底）
     */
    private TraderDataService.Mutation<Reservation> reserve(TraderIslandData data, String normalizedId,
                                                             double basePrice, ShopPurchaseLimitPeriod period,
                                                             int baseLimitCount, Material material,
                                                             PurchaseMode mode, TrackTiers tiers,
                                                             boolean isGuidebook) {
        Integer remaining = null;
        PurchaseCounter counter = null;

        if (period.limited()) {
            counter = data.getOrCreatePurchaseCounter(normalizedId);
            long now = System.currentTimeMillis();
            if (period.resets()) {
                if (counter.windowStartAt == 0L || now - counter.windowStartAt > period.windowMillis()) {
                    counter.count = 0;
                    counter.windowStartAt = now;
                }
            } else if (counter.windowStartAt == 0L) {
                // LIFETIME：只是把窗口起点记一下（纯记录用途，判定不依赖它），不参与重置。
                counter.windowStartAt = now;
            }

            int bonus = ShopEconomics.limitBonus(data.totalSpent, tiers.spending());
            int effectiveLimit = baseLimitCount + bonus;
            remaining = Math.max(0, effectiveLimit - counter.count);
            if (remaining <= 0) {
                return TraderDataService.Mutation.readOnly(Reservation.fail("§c这件商品本周期的限购额度已经用完了。"));
            }
        }

        int quantity = resolveQuantity(mode, material, remaining);
        if (quantity <= 0) {
            return TraderDataService.Mutation.readOnly(Reservation.fail("§c限购余量不足，无法购买。"));
        }

        double discount = ShopEconomics.discountRate(data.totalSpent, tiers.spending());
        double unitPrice = ShopEconomics.discountedUnitPrice(basePrice, discount);
        double totalPrice = ShopEconomics.round2(unitPrice * quantity);

        // ── 预留：同一临界区内直接落库，回滚见 rollbackReservationOnly ──
        if (counter != null) {
            counter.count += quantity;
        }
        if (!isGuidebook) {
            data.tradeCount += 1;
        }
        data.totalSpent = ShopEconomics.round2(data.totalSpent + totalPrice);

        Reservation ok = Reservation.ok(quantity, unitPrice, basePrice, totalPrice, isGuidebook);
        return TraderDataService.Mutation.commit(ok, Reservation.fail("§c数据保存失败，请稍后重试。"));
    }

    /**
     * 按点击语义算购买数量。
     *
     * @param remaining 限购剩余额度；{@code null} 表示这件商品不限购
     */
    private int resolveQuantity(PurchaseMode mode, Material material, Integer remaining) {
        int maxStack = Math.max(1, material.getMaxStackSize());
        return switch (mode) {
            case SINGLE -> remaining != null ? Math.min(1, remaining) : 1;
            case QUINTUPLE -> remaining != null ? Math.min(5, remaining) : Math.min(5, maxStack);
            case FILL -> remaining != null ? remaining : Math.min(64, maxStack);
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 2 步（玩家线程）：发货
    // ══════════════════════════════════════════════════════════════════════

    private void deliver(Player player, Island island, String normalizedId,
                         String displayName, Material material, Reservation reservation, Economy econ) {
        UUID playerId = player.getUniqueId();

        // 玩家在「扣款成功」和「发货」之间的这段异步间隙里下线：EntityScheduler#run 在玩家
        // 已经完全离线时会直接返回 null 且不调用 retired 回调（T2 在凭证召唤上踩过的坑，见类注释）。
        // 钱已经真的从玩家账户扣走了，这种情况必须当成"发货失败"一样退款 + 回滚限购计数，
        // 不能既扣钱又不给东西。
        Runnable cancelBecauseOffline = () -> {
            log.warn("玩家 {} 在购买商品 '{}' 扣款后、发货前下线，已退款并回滚限购计数",
                    player.getName(), normalizedId);
            Bukkit.getAsyncScheduler().runNow(plugin, at ->
                    refundAndRollback(island, normalizedId, reservation, econ, player, "玩家在发货前下线"));
            inFlight.remove(playerId);
        };

        var scheduled = player.getScheduler().run(plugin, t -> {
            // 发货本体（造物品 + 塞背包 + 溢出掉落）单独一个 try：这一段抛异常才是真正的
            // "发货失败"，需要退款回滚。播报结果的 sendMessage 故意放在这个 try 之外——
            // 物品已经到玩家背包/脚下之后，播报消息本身再出问题不该被当成发货失败去误退款，
            // 那样会出现"东西给了、钱也退了"的账目错位（哪怕触发概率极低也不该有这条路）。
            boolean delivered;
            try {
                ItemStack stack = buildDeliveryItem(normalizedId, material, displayName, reservation.quantity());
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
                // 背包放不下：溢出部分掉在玩家脚下，这不算发货失败（见 UpgradeAdminCommand#giveToken
                // 的既有写法），玩家东西一点没少，只是不在背包里而已。
                for (ItemStack over : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), over);
                }
                delivered = true;
            } catch (Throwable ex) {
                // 真正的发货失败（材质非法、ItemMeta 构建异常……）——退款 + 回滚 + log，
                // 绝不能让玩家钱没了却什么都没拿到。
                delivered = false;
                log.error("玩家 {} 购买商品 '{}' 发货失败，已退款并回滚限购计数",
                        player.getName(), normalizedId, ex);
                Bukkit.getAsyncScheduler().runNow(plugin, at ->
                        refundAndRollback(island, normalizedId, reservation, econ, player, "发货异常"));
                player.sendMessage(Component.text("§c发货异常，金币已退还，请联系管理员。"));
            }
            inFlight.remove(playerId);

            if (delivered) {
                try {
                    String discountNote = reservation.unitPrice() < reservation.basePrice()
                            ? "（原价 §f" + GuiFormat.fmt(reservation.basePrice()) + " §a已享折扣）" : "";
                    player.sendMessage(Component.text("§a购买成功：§f" + GuiFormat.legacyName(displayName) + " §fx" + reservation.quantity()
                            + " §a，花费 §f" + GuiFormat.fmt(reservation.totalPrice()) + " §a金币" + discountNote));
                } catch (Throwable ex) {
                    // 物品已经真的发出去了，这里出错只记日志，绝不能再触发退款/回滚。
                    log.error("玩家 {} 购买商品 '{}' 发货成功但播报结果消息时出错（不影响交易本身）",
                            player.getName(), normalizedId, ex);
                }
            }
        }, cancelBecauseOffline);

        if (scheduled == null) {
            // 调度当场失败：玩家在「购买事务开始」到「这一跳」之间已经彻底下线了，
            // 两个回调都不会跑，必须自己触发和 retired 一样的回滚路径。
            cancelBecauseOffline.run();
        }
    }

    private ItemStack buildDeliveryItem(String normalizedId, Material material, String displayName, int quantity) {
        if (ShopConfigManager.GUIDEBOOK_RESERVED_ID.equals(normalizedId)) {
            GuidebookConfig config = TraderConfigLoader.config.getGuidebook();
            // config 在这里理论上不该是 null（购买路径一开始就检查过），但 async→玩家线程之间
            // 可能又发生了一次 /skyllia reload；防御性兜底成"按 material 造一个裸物品"，
            // 而不是让 NPE 在玩家线程上把这次购买变成一次"真正的发货失败"。
            ItemStack book = config != null ? GuidebookItem.build(config) : new ItemStack(material);
            // ⚠️ GuidebookItem.build 固定造 1 本（数量不是它的职责）。绝大多数情况下 quantity 也
            // 确实是 1（成书 maxStackSize=1，resolveQuantity 会自动退化成买 1），但服主如果把
            // guidebook.material 换成可堆叠材质（比如 PAPER），quantity 就可能大于 1——
            // 这里必须显式 setAmount，否则会出现「按 quantity 份的钱扣了，只发 1 份货」的账目错位。
            book.setAmount(Math.max(1, quantity));
            return book;
        }
        // ⚠️ 这里<b>刻意不给商品打自定义名字</b>。
        // 之前会把 shop.toml 的 display-name 写进物品的 displayName，两个后果都是实打实的坑：
        //   ① 买来的沙子带自定义名、挖来的沙子不带，两者在背包里<b>不能互相堆叠</b>；
        //   ② 回收流程用 `new ItemStack(material, n)`（裸物品）去扣，而 Inventory#removeItem
        //      是按<b>完整组件</b>匹配的，带名字的物品永远匹配不上——也就是
        //      <b>凡是从商店买来的东西一件都回收不掉</b>，还会报一句莫名其妙的
        //      "带有耐久损耗、附魔或自定义属性"（2026-08-21 服主实测反馈）。
        // 而这些 display-name 的内容本来就只是物品的中文名（"<white>沙子"），
        // 和客户端自带的原版名称完全重复，打上去没有任何信息增量。
        // 显示名只在<b>商店 GUI 的货架上</b>用（见 MerchantShopGui#buildItem），不进实物。
        return new ItemStack(material, Math.max(1, quantity));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 回滚
    // ══════════════════════════════════════════════════════════════════════

    /** 只回滚预留（限购计数 / 交易次数 / 消费额），不退款——用于「预留成功但还没真正扣到钱」的失败分支。 */
    private void rollbackReservationOnly(Island island, String normalizedId, Reservation reservation) {
        boolean ok = dataService.mutate(island, data -> undoReservation(data, normalizedId, reservation));
        if (!ok) {
            log.error("岛屿 {} 的购买预留回滚写库失败（商品 '{}'，数量 {}），限购计数可能不准确，"
                    + "请管理员手动核实", island.getId(), normalizedId, reservation.quantity());
        }
    }

    /** 退款 + 回滚预留——用于「钱已经真的从玩家账户扣走，但东西没能发出去」的失败分支。 */
    private void refundAndRollback(Island island, String normalizedId, Reservation reservation,
                                   Economy econ, Player player, String reasonForLog) {
        EconomyResponse refund = econ.depositPlayer(player, reservation.totalPrice());
        if (refund == null || !refund.transactionSuccess()) {
            log.error("岛屿 {} 玩家 {} 购买商品 '{}'（{}）退款失败！金额 {}，需要管理员手动补偿：{}",
                    island.getId(), player.getName(), normalizedId, reasonForLog,
                    reservation.totalPrice(), refund == null ? "经济插件未返回结果" : refund.errorMessage);
        }
        rollbackReservationOnly(island, normalizedId, reservation);
    }

    private void undoReservation(TraderIslandData data, String normalizedId, Reservation reservation) {
        PurchaseCounter counter = data.shopPurchaseCounts.get(normalizedId);
        if (counter != null) {
            counter.count = Math.max(0, counter.count - reservation.quantity());
        }
        // 说明书预留时不计入 tradeCount（见 reserve 的 isGuidebook 分支），回滚必须对称。
        if (!reservation.isGuidebook() && data.tradeCount > 0) {
            data.tradeCount -= 1;
        }
        data.totalSpent = ShopEconomics.round2(Math.max(0.0, data.totalSpent - reservation.totalPrice()));
    }

    // ══════════════════════════════════════════════════════════════════════

    private void fail(Player player, String message) {
        player.sendMessage(Component.text(message));
        inFlight.remove(player.getUniqueId());
    }
}
