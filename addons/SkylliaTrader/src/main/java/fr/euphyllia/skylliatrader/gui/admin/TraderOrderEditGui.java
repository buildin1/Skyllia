package fr.euphyllia.skylliatrader.gui.admin;

import fr.euphyllia.skyllia.gui.ConfirmGui;
import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiTextInput;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.OrdersConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.ItemAmount;
import fr.euphyllia.skylliatrader.configuration.model.OrderDefinition;
import fr.euphyllia.skylliatrader.configuration.model.OrderType;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理端「订单详情编辑页」：{@link TraderOrderListGui} 点开一条已有订单，或点"+ 新增订单"，
 * 都会落到这里。T3 第二波把 T1 的只读列表升级成完整的增删改工具，交互规格是服主拍板的：
 * 类型二选一（金币/物物）→ 用物品格子摆材料 → 非物品字段走 {@link GuiTextInput} 聊天栏输入。
 *
 * <h2>物品格子只是"指认材料"的输入方式，不是真实交易</h2>
 * <p>
 * 管理员往格子里放一组铁块，只是告诉配置"这个订单收铁块"——物品本身<b>永远不会被消耗</b>，
 * 点击"确认"时只读取格子里的 {@code Material}（barter 还读堆叠数量当 amount），
 * 物品该怎么处置完全交给下面这套统一机制。
 * </p>
 *
 * <h2>Folia 安全：退物品与写配置严格互斥</h2>
 * <p>
 * 本类<b>没有自己写任何"退还物品"的代码</b>——那件事完全下沉到核心
 * {@code fr.euphyllia.skyllia.gui.GuiListener#onClose}：只要 {@code SkylliaGuiHolder} 被
 * {@link SkylliaGuiHolder#markEditableSlots(int...)} 标记过编辑格，菜单一关闭（不管是
 * ESC、切物品栏、退出游戏、点"返回"、点"删除"、切换类型触发的重开，还是聊天栏输入
 * 组件 {@link GuiTextInput} 自己调用的 {@code closeInventory()}），核心监听器就会把这些
 * 槽位里<b>当前的</b>物品原样退还给玩家，且只退这一次（退还发生在
 * {@code InventoryCloseEvent} 里，这个事件天然只触发一次）。
 * </p>
 * <p>
 * 本类里所有"确认"按钮的处理函数（{@link #handleMoneyConfirm}/{@link #handleBarterConfirm}）
 * 都只<b>读取</b>（{@code Inventory#getItem}，不清空）格子内容来拼 {@link ItemAmount} 列表，
 * 然后要么原地报错留在当前菜单等管理员修正（这种情况菜单没关闭，什么都不会被退还，
 * 物品还在格子里），要么导航离开当前菜单（返回列表 / 弹出价格聊天框）——导航离开必然
 * 触发一次 {@code InventoryCloseEvent}，核心监听器随即把刚刚读到的这些物品退还给管理员。
 * 写配置（{@code OrdersConfigManager#upsertOrder}/{@code deleteOrder}）只发生在"读取
 * 校验通过、决定导航离开"这条分支里，和退物品是同一次导航触发的<b>两件独立的事</b>：
 * 前者由本类调度到 {@code Bukkit.getAsyncScheduler()} 异步落盘，后者由核心监听器同步执行——
 * 两者都只发生一次，不会漏也不会重复，因此天然互斥又都不会缺失。
 * </p>
 *
 * <h2>编辑已有订单时"不放物品 = 沿用原值"</h2>
 * <p>
 * 打开一条已有订单时，材料槽<b>不会</b>预先摆上代表现有材料的物品——如果摆了，那些物品
 * 一旦被核心监听器当成"编辑格里的东西"退还给管理员，等于凭空发放了从来不属于他背包的
 * 物品（这类物品一旦出现在可点击的编辑格里，管理员靠正常箱子操作就能把它捡进背包，
 * GUI 层面拦不住）。所以现有材料只在提示文案（{@link #render} 里 31 号槽的说明物品）里
 * 用只读 lore 展示，材料槽本身留空；确认时如果某一侧材料槽全空，就直接沿用
 * {@link OrderDraft#originalTakeItems}/{@link OrderDraft#originalGiveItems}
 * （编辑现有订单时从原订单复制来的快照）。只要放了任意一件物品，那一侧就按格子里
 * 实际摆的内容整体替换，不做"合并旧值"。
 * </p>
 *
 * <h2>草稿与磁盘的关系</h2>
 * <p>
 * {@link OrderDraft} 是纯内存对象，管理员在菜单间跳转、切类型、输入聊天栏，改的都是这个
 * 对象的字段；只有点击"确认"且校验通过，才会拼出一份 {@link OrderDefinition} 交给
 * {@code OrdersConfigLoader.config}（也就是 {@code OrdersConfigManager}）
 * 的 {@code upsertOrder}/{@code deleteOrder}——那两个方法负责整表重建 + 通过
 * {@code ConfigFileWriter} 原子落盘 + 落盘成功后才更新内存态订单列表，不需要
 * {@code /skyllia reload} 就对后续读取生效。磁盘 IO 全程在
 * {@code Bukkit.getAsyncScheduler()} 上，GUI 相关操作（关菜单/开菜单/发消息）
 * 全程在玩家自己的 {@code EntityScheduler} 上，符合 Folia 线程模型。
 * </p>
 */
public final class TraderOrderEditGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // ── 槽位版式（54 格）──────────────────────────────────────────────
    private static final int SLOT_HEADER = 4;
    private static final int SLOT_NAME = 9;
    private static final int SLOT_ENABLED = 10;
    private static final int SLOT_WEIGHT = 11;
    private static final int SLOT_REDEEM_LIMIT = 12;
    private static final int SLOT_LEVEL_MIN = 13;
    private static final int SLOT_REP_MIN = 14;
    private static final int SLOT_REWARD_REP = 15;
    private static final int SLOT_ID_INFO = 17;
    private static final int SLOT_TYPE_MONEY = 20;
    private static final int SLOT_TYPE_BARTER = 24;
    private static final int SLOT_ITEM_HINT = 31;
    private static final int MONEY_ITEM_SLOT = 28;
    private static final int[] TAKE_SLOTS = {28, 29, 37, 38};
    private static final int[] GIVE_SLOTS = {33, 34, 42, 43};
    private static final int SLOT_DELETE = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_CONFIRM = 53;

    private static final int[] BORDER = {
            0, 1, 2, 3, 5, 6, 7, 8,
            16,
            18, 19, 21, 22, 23, 25, 26,
            27, 30, 32, 35,
            36, 39, 40, 41, 44,
            46, 47, 48, 50, 51, 52
    };

    private TraderOrderEditGui() {
    }

    public static void open(@NotNull Player player, @NotNull OrderDraft draft) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;
        player.getScheduler().run(plugin, t -> render(player, draft), null);
    }

    private static void render(@NotNull Player player, @NotNull OrderDraft draft) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        String title = (draft.isNew ? "<green>+ 新增订单 " : "<light_purple>✎ 编辑订单 ")
                + "<gray>- " + (draft.type == OrderType.MONEY ? "金币交易" : "物物交易");
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize(title));

        for (int slot : BORDER) {
            inv.setItem(slot, GuiItem.filler());
        }

        inv.setItem(SLOT_HEADER, buildHeaderItem(draft));

        // ── 非物品字段：全部走 GuiTextInput，输入完成后重绘详情页 ──────────
        inv.setItem(SLOT_NAME, GuiItem.of(Material.NAME_TAG, "<!italic><yellow>✎ 展示名称",
                List.of("<dark_gray>─────────",
                        "<gray>当前：<white>" + draft.displayName + "</white></gray>",
                        "<gray>支持 MiniMessage 颜色标签</gray>",
                        "<yellow>点击</yellow> <gray>在聊天栏输入新名称</gray>")));
        holder.bind(SLOT_NAME, e -> GuiTextInput.promptText(plugin, player,
                "<yellow>请输入新的展示名称（支持 MiniMessage 颜色标签，比如 <aqua>钻石收购），或输入 取消 放弃修改：",
                value -> {
                    draft.displayName = value;
                    open(player, draft);
                },
                () -> open(player, draft)));

        inv.setItem(SLOT_ENABLED, draft.enabled
                ? GuiItem.enabled("是否启用", List.of("<dark_gray>─────────",
                        "<gray>停用的订单不会进入刷新池</gray>", "<yellow>点击</yellow> <gray>切换为停用</gray>"))
                : GuiItem.disabled("是否启用", List.of("<dark_gray>─────────",
                        "<gray>停用的订单不会进入刷新池</gray>", "<yellow>点击</yellow> <gray>切换为启用</gray>")));
        holder.bind(SLOT_ENABLED, e -> {
            draft.enabled = !draft.enabled;
            open(player, draft);
        });

        inv.setItem(SLOT_WEIGHT, buildNumberButton(Material.HOPPER, "刷新权重", draft.weight,
                "自然刷新时的抽取权重，最小 1"));
        holder.bind(SLOT_WEIGHT, e -> GuiTextInput.promptNumber(plugin, player,
                "<yellow>请输入新的刷新权重（整数，最小 1），或输入 取消 放弃修改：",
                value -> {
                    draft.weight = Math.max(1, (int) Math.round(value));
                    open(player, draft);
                },
                () -> open(player, draft)));

        inv.setItem(SLOT_REDEEM_LIMIT, buildNumberButton(Material.CHEST, "终身限购(按岛屿)", draft.redeemLimitPerIsland,
                "这条订单在同一座岛屿终身最多能完成几单，0 = 不限"));
        holder.bind(SLOT_REDEEM_LIMIT, e -> GuiTextInput.promptNumber(plugin, player,
                "<yellow>请输入新的终身限购次数（整数，0 = 不限），或输入 取消 放弃修改：",
                value -> {
                    draft.redeemLimitPerIsland = Math.max(0, (int) Math.round(value));
                    open(player, draft);
                },
                () -> open(player, draft)));

        inv.setItem(SLOT_LEVEL_MIN, buildNumberButton(Material.EXPERIENCE_BOTTLE, "解锁所需岛屿等级", draft.requiredLevelMin,
                "岛屿等级达到这个数才会看到这条订单"));
        holder.bind(SLOT_LEVEL_MIN, e -> GuiTextInput.promptNumber(plugin, player,
                "<yellow>请输入新的岛屿等级门槛（整数），或输入 取消 放弃修改：",
                value -> {
                    draft.requiredLevelMin = Math.max(0, (int) Math.round(value));
                    open(player, draft);
                },
                () -> open(player, draft)));

        inv.setItem(SLOT_REP_MIN, buildNumberButton(Material.NETHER_STAR, "解锁所需商会声望", draft.requiredReputationMin,
                "商会声望达到这个数才会看到这条订单"));
        holder.bind(SLOT_REP_MIN, e -> GuiTextInput.promptNumber(plugin, player,
                "<yellow>请输入新的商会声望门槛（整数），或输入 取消 放弃修改：",
                value -> {
                    draft.requiredReputationMin = Math.max(0, (int) Math.round(value));
                    open(player, draft);
                },
                () -> open(player, draft)));

        inv.setItem(SLOT_REWARD_REP, buildNumberButton(Material.EMERALD, "声望奖励", draft.rewardReputation,
                "完成一次订单获得的商会声望，填 0 就是纯交易不换声望"));
        holder.bind(SLOT_REWARD_REP, e -> GuiTextInput.promptNumber(plugin, player,
                "<yellow>请输入新的声望奖励（整数，不小于 0），或输入 取消 放弃修改：",
                value -> {
                    draft.rewardReputation = Math.max(0, (int) Math.round(value));
                    open(player, draft);
                },
                () -> open(player, draft)));

        inv.setItem(SLOT_ID_INFO, GuiItem.of(Material.PAPER, "<!italic><gray>订单 ID（不可编辑）",
                List.of("<dark_gray>─────────",
                        "<white>" + draft.id + "</white>",
                        "<dark_gray>终身限购计数按这个 id 归属，</dark_gray>",
                        "<dark_gray>不开放编辑以免计数器失联</dark_gray>")));

        // ── 类型切换：点击直接换成对应类型重绘，未确认的物品由核心监听器统一退还 ──
        inv.setItem(SLOT_TYPE_MONEY, buildTypeButton(Material.GOLD_INGOT, "金币交易", draft.type == OrderType.MONEY));
        holder.bind(SLOT_TYPE_MONEY, e -> switchType(player, draft, OrderType.MONEY));

        inv.setItem(SLOT_TYPE_BARTER, buildTypeButton(Material.CHEST_MINECART, "物物交易", draft.type == OrderType.BARTER));
        holder.bind(SLOT_TYPE_BARTER, e -> switchType(player, draft, OrderType.BARTER));

        // ── 物品区提示：现有材料只读展示在 lore 里，材料槽本身永远从空的开始 ──
        List<String> hintLore = new ArrayList<>();
        hintLore.add("<dark_gray>─────────");
        if (draft.type == OrderType.MONEY) {
            hintLore.add("<gray>放材料 = 岛屿要交给商队的东西，堆叠数量就是收购数量</gray>");
            hintLore.add("<gray>（上限 64），价格在确认后另外输入</gray>");
            hintLore.add("<gray>不放物品直接确认 = 整体沿用原有材料和数量</gray>");
            hintLore.add("<white>原有材料：" + GuiFormat.describeItems(draft.originalTakeItems) + "</white>");
        } else {
            hintLore.add("<gray>← 收货区(最多4格) ・ 回礼区(最多4格) →</gray>");
            hintLore.add("<gray>堆叠数量就是数量，操作方式和箱子一样</gray>");
            hintLore.add("<gray>某一侧不放物品直接确认 = 那一侧沿用原有材料</gray>");
            hintLore.add("<white>原收货区：" + GuiFormat.describeItems(draft.originalTakeItems) + "</white>");
            hintLore.add("<white>原回礼区：" + GuiFormat.describeItems(draft.originalGiveItems) + "</white>");
        }
        hintLore.add("<dark_gray>─────────");
        hintLore.add("<yellow>⚠ 点其它字段按钮会先退还已摆放但未确认的材料，</yellow>");
        hintLore.add("<yellow>  重新打开后材料槽会变回空的，建议材料放在最后一步。</yellow>");
        inv.setItem(SLOT_ITEM_HINT, GuiItem.of(Material.ARROW, "<!italic><aqua>收货区 → 回礼区", hintLore));

        if (draft.type == OrderType.MONEY) {
            holder.markEditableSlots(MONEY_ITEM_SLOT);
            for (int slot : TAKE_SLOTS) {
                if (slot != MONEY_ITEM_SLOT) inv.setItem(slot, GuiItem.filler());
            }
            for (int slot : GIVE_SLOTS) {
                inv.setItem(slot, GuiItem.filler());
            }
        } else {
            holder.markEditableSlots(TAKE_SLOTS);
            holder.markEditableSlots(GIVE_SLOTS);
        }

        // ── 删除 / 返回 / 确认 ──────────────────────────────────────────
        if (draft.isNew) {
            // 这个按钮是可点击的正常功能（返回列表＝放弃这次新增），不能用 GuiItem.placeholder()——
            // 它会自动追加"开发中，敬请期待"，会让管理员误以为按钮没反应（2026-08-21 审查发现）。
            inv.setItem(SLOT_DELETE, GuiItem.of(Material.BARRIER, "<gray>放弃新增",
                    List.of("<dark_gray>─────────", "<gray>还没保存过，点击直接返回列表即可放弃。</gray>")));
            holder.bind(SLOT_DELETE, e -> TraderOrderListGui.open(player, 0));
        } else {
            inv.setItem(SLOT_DELETE, GuiItem.danger(Material.LAVA_BUCKET, "删除本条订单",
                    List.of("<dark_gray>─────────", "<red>不可撤销，需要二次确认</red>")));
            holder.bind(SLOT_DELETE, e -> ConfirmGui.open(player, "删除订单：" + draft.id,
                    List.of("<gray>即将删除订单：<white>" + draft.displayName + "</white></gray>",
                            "<gray>这个操作不可撤销。</gray>"),
                    () -> handleDelete(player, draft),
                    () -> TraderOrderListGui.open(player, 0)));
        }

        inv.setItem(SLOT_BACK, GuiItem.back());
        holder.bind(SLOT_BACK, e -> TraderOrderListGui.open(player, 0));

        inv.setItem(SLOT_CONFIRM, buildConfirmItem(draft));
        if (draft.type == OrderType.MONEY) {
            holder.bind(SLOT_CONFIRM, e -> handleMoneyConfirm(player, inv, draft));
        } else {
            holder.bind(SLOT_CONFIRM, e -> handleBarterConfirm(player, inv, draft));
        }

        player.openInventory(inv);
    }

    /**
     * 切换类型只是改内存里的字段然后重绘——不需要手动退还旧格子里的物品：
     * {@link #open} 会打开一个新 Inventory，这会隐式关闭当前这个，触发核心
     * {@code GuiListener#onClose} 的编辑格退还逻辑，把旧类型格子里的东西还给管理员。
     */
    private static void switchType(Player player, OrderDraft draft, OrderType newType) {
        if (draft.type == newType) return;
        draft.type = newType;
        open(player, draft);
    }

    /**
     * 金币交易确认：两段式。第一次点击只读材料槽（或沿用原有材料）+ 弹聊天框问价格；
     * 物品的退还完全交给"关闭当前菜单去弹聊天框"这个动作触发的核心退还逻辑，本方法不
     * 手动碰玩家背包。
     */
    private static void handleMoneyConfirm(Player player, Inventory inv, OrderDraft draft) {
        if (!draft.confirming.compareAndSet(false, true)) return;

        ItemStack placed = inv.getItem(MONEY_ITEM_SLOT);
        boolean hasPlacement = placed != null && !placed.getType().isAir();

        // 材料槽没放东西＝"沿用原有材料"，这时必须整体复用原始的 (material, amount) 组合，
        // 不能只挑 material 出来重新拼一个 amount=1 的新对象——那会把"收 64 个圆石"的订单
        // 静默改写成"收 1 个圆石"，价格却没变，订单经济含义整个错掉（2026-08-21 审查发现）。
        List<ItemAmount> takeItems;
        Material material;
        if (hasPlacement) {
            material = placed.getType();
            takeItems = List.of(new ItemAmount(material, Math.max(1, Math.min(64, placed.getAmount()))));
        } else if (!draft.originalTakeItems.isEmpty()) {
            material = draft.originalTakeItems.get(0).material();
            takeItems = draft.originalTakeItems;
        } else {
            material = null;
            takeItems = List.of();
        }

        if (material == null) {
            player.sendMessage(Component.text("§c请先在材料槽里放入要收购的材料。"));
            draft.confirming.set(false);
            return;
        }

        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;

        GuiTextInput.promptNumber(plugin, player,
                "<yellow>请输入商队收购这批 <white>" + material + "</white> 支付的总价（金币，整单总价），或输入 取消 放弃本次保存：",
                priceValue -> {
                    if (priceValue <= 0) {
                        player.sendMessage(Component.text("§c价格必须大于 0，已放弃本次保存（材料已退还，本次编辑未写入 orders.toml）。"));
                        TraderOrderListGui.open(player, 0);
                        return;
                    }
                    draft.price = priceValue;
                    finalizeSave(player, draft, takeItems, List.of());
                },
                () -> {
                    player.sendMessage(Component.text("§7已取消保存（材料已退还，本次编辑未写入 orders.toml）。"));
                    TraderOrderListGui.open(player, 0);
                });
    }

    /**
     * 物物交易确认：单段式，两侧都读完就直接落盘（不需要额外的聊天栏输入）。
     * 校验失败时<b>不导航离开、不落盘</b>——菜单留在原地，管理员放的物品原封不动等在
     * 格子里，可以直接补上缺的那一侧材料再点一次确认。
     */
    private static void handleBarterConfirm(Player player, Inventory inv, OrderDraft draft) {
        if (!draft.confirming.compareAndSet(false, true)) return;

        List<ItemAmount> placedTake = toItemAmounts(readSlots(inv, TAKE_SLOTS));
        List<ItemAmount> placedGive = toItemAmounts(readSlots(inv, GIVE_SLOTS));
        List<ItemAmount> finalTake = placedTake.isEmpty() ? draft.originalTakeItems : placedTake;
        List<ItemAmount> finalGive = placedGive.isEmpty() ? draft.originalGiveItems : placedGive;

        if (finalTake.isEmpty() || finalGive.isEmpty()) {
            player.sendMessage(Component.text("§c收货区/回礼区至少要放一件物品，请检查后再点击确认。"));
            draft.confirming.set(false);
            return;
        }

        finalizeSave(player, draft, finalTake, finalGive);
    }

    /**
     * 拼出完整 {@link OrderDefinition}、调度异步落盘、导航回列表。
     * <p>
     * 导航（{@link TraderOrderListGui#open}）会隐式关闭当前详情页，触发核心退还逻辑把
     * 材料槽里的物品还给管理员——这一步与下面的异步落盘是两件独立的事，互不等待。
     * </p>
     */
    private static void finalizeSave(Player player, OrderDraft draft,
                                      List<ItemAmount> takeItems, List<ItemAmount> giveItems) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;

        OrderDefinition order = new OrderDefinition(draft.id, draft.enabled, draft.displayName, draft.type,
                draft.type == OrderType.MONEY ? draft.price : 0.0,
                giveItems, takeItems, draft.rewardReputation, draft.weight,
                draft.redeemLimitPerIsland, draft.requiredLevelMin, draft.requiredReputationMin);
        String previousId = draft.isNew ? null : draft.id;

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean ok = OrdersConfigLoader.config.upsertOrder(order, previousId);
            player.getScheduler().run(plugin, t -> {
                if (ok) {
                    player.sendMessage(Component.text("§a订单已保存：" + draft.displayName));
                } else {
                    player.sendMessage(Component.text(
                            "§c订单保存失败（id 冲突或磁盘写入出错），请查看控制台日志，orders.toml 未被修改。"));
                }
            }, null);
        });

        TraderOrderListGui.open(player, 0);
    }

    private static void handleDelete(Player player, OrderDraft draft) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean ok = OrdersConfigLoader.config.deleteOrder(draft.id);
            player.getScheduler().run(plugin, t -> {
                if (ok) {
                    player.sendMessage(Component.text("§a订单已删除：" + draft.displayName));
                } else {
                    player.sendMessage(Component.text("§c删除失败（订单可能已经被删过，或磁盘写入出错），请查看控制台日志。"));
                }
            }, null);
        });
    }

    // ── 物品槽读取（只读，不清空——清空/退还统一由核心 GuiListener#onClose 负责）──

    private static ItemStack[] readSlots(Inventory inv, int[] slots) {
        ItemStack[] result = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) {
            result[i] = inv.getItem(slots[i]);
        }
        return result;
    }

    /** 同材料的多个槽位会被合并成一项（amount 相加），最终不超过 4 组（槽位数本来就是 4）。 */
    private static List<ItemAmount> toItemAmounts(ItemStack[] stacks) {
        Map<Material, Integer> merged = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) continue;
            merged.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        List<ItemAmount> result = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : merged.entrySet()) {
            result.add(new ItemAmount(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    // ── 展示构建 ──────────────────────────────────────────────────────

    private static ItemStack buildHeaderItem(OrderDraft draft) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>id：<white>" + draft.id + "</white></gray>");
        lore.add("<gray>状态：" + (draft.enabled ? "<green>启用</green>" : "<red>停用</red>"));
        lore.add("<gray>类型：<white>" + (draft.type == OrderType.MONEY ? "金币收购" : "物物交换") + "</white></gray>");
        if (draft.type == OrderType.MONEY) {
            lore.add("<gray>价格：<white>" + GuiFormat.fmt(draft.price) + " 金币</white></gray>");
        }
        lore.add("<dark_gray>─────────");
        lore.add(draft.isNew ? "<green>尚未保存，退出即放弃本次新增</green>" : "<gray>正在编辑一条已有订单</gray>");
        return GuiItem.of(draft.isNew ? Material.EMERALD : Material.WRITTEN_BOOK,
                "<!italic><light_purple>" + draft.displayName, lore);
    }

    private static ItemStack buildNumberButton(Material material, String label, double currentValue, String description) {
        return GuiItem.of(material, "<!italic><yellow>✎ " + label,
                List.of("<dark_gray>─────────",
                        "<gray>当前值：<white>" + GuiFormat.fmt(currentValue) + "</white></gray>",
                        "<gray>" + description + "</gray>",
                        "<yellow>点击</yellow> <gray>在聊天栏输入新数值</gray>"));
    }

    private static ItemStack buildTypeButton(Material material, String label, boolean selected) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        if (selected) {
            lore.add("<green>✔ 当前选中</green>");
            lore.add("<dark_gray>（切换类型会把格子里未确认的物品退还给你）</dark_gray>");
        } else {
            lore.add("<gray>点击切换为此类型</gray>");
        }
        return GuiItem.of(material, (selected ? "<!italic><green>✔ " : "<!italic><gray>") + label, lore);
    }

    private static ItemStack buildConfirmItem(OrderDraft draft) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        if (draft.type == OrderType.MONEY) {
            lore.add("<gray>确认后会先退还材料槽里的物品，</gray>");
            lore.add("<gray>然后在聊天栏输入商队支付的总价</gray>");
        } else {
            lore.add("<gray>确认后立即保存整条订单，</gray>");
            lore.add("<gray>材料槽里的物品会原样退还给你</gray>");
        }
        return GuiItem.of(Material.LIME_DYE, "<!italic><green>✔ 确认保存", lore);
    }

    private static String generateId() {
        Set<String> existing = new HashSet<>();
        for (OrderDefinition o : OrdersConfigLoader.config.getOrders()) {
            existing.add(o.id());
        }
        String candidate;
        do {
            candidate = "order-" + Long.toHexString(System.nanoTime())
                    + "-" + ThreadLocalRandom.current().nextInt(1000);
        } while (existing.contains(OrderDefinition.normalizeId(candidate)));
        return candidate;
    }

    /**
     * 编辑会话的内存态草稿。菜单间跳转（切类型、编辑字段、切页）改的都是这个对象，
     * 只有点击"确认"且校验通过才会落盘，见类顶部说明。
     */
    public static final class OrderDraft {
        final String id;
        final boolean isNew;
        /** 编辑现有订单时，从原订单复制来的快照——材料槽为空时用于"沿用原值"。新草稿恒为空列表。 */
        final List<ItemAmount> originalTakeItems;
        final List<ItemAmount> originalGiveItems;

        boolean enabled;
        String displayName;
        OrderType type;
        double price;
        int rewardReputation;
        int weight;
        int redeemLimitPerIsland;
        int requiredLevelMin;
        int requiredReputationMin;

        /** 防止管理员连点"确认"导致同一次保存被处理两次；校验失败的分支会复位，允许重试。 */
        final AtomicBoolean confirming = new AtomicBoolean(false);

        private OrderDraft(String id, boolean isNew, List<ItemAmount> originalTakeItems, List<ItemAmount> originalGiveItems) {
            this.id = id;
            this.isNew = isNew;
            this.originalTakeItems = originalTakeItems;
            this.originalGiveItems = originalGiveItems;
        }

        public static OrderDraft fresh() {
            OrderDraft draft = new OrderDraft(generateId(), true, List.of(), List.of());
            draft.enabled = true;
            draft.displayName = "<white>新订单";
            draft.type = OrderType.MONEY;
            draft.price = 1.0;
            draft.rewardReputation = 0;
            draft.weight = 1;
            draft.redeemLimitPerIsland = 0;
            draft.requiredLevelMin = 0;
            draft.requiredReputationMin = 0;
            return draft;
        }

        public static OrderDraft from(OrderDefinition order) {
            OrderDraft draft = new OrderDraft(order.id(), false, order.takeItems(), order.giveItems());
            draft.enabled = order.enabled();
            draft.displayName = order.displayName();
            draft.type = order.type();
            draft.price = order.price();
            draft.rewardReputation = order.rewardReputation();
            draft.weight = order.weight();
            draft.redeemLimitPerIsland = order.redeemLimitPerIsland();
            draft.requiredLevelMin = order.requiredLevelMin();
            draft.requiredReputationMin = order.requiredReputationMin();
            return draft;
        }
    }
}
