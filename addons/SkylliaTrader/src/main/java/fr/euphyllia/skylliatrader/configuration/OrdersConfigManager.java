package fr.euphyllia.skylliatrader.configuration;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import fr.euphyllia.skyllia.utils.ConfigFileWriter;
import fr.euphyllia.skylliatrader.configuration.model.ItemAmount;
import fr.euphyllia.skylliatrader.configuration.model.OrderDefinition;
import fr.euphyllia.skylliatrader.configuration.model.OrderType;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * orders.toml 的运行时读取。
 * <p>
 * 和 {@link TraderConfigManager} 不一样：orders.toml 里的每一条 {@code [[order]]} 都是管理员
 * 显式写的条目，不是"缺项自动补默认值"的键值表，{@link #loadConfig()} 只做"解析 + 校验 + 丢弃坏
 * 条目"，不做整表回写（连 {@code config-version} 缺失时也只警告不补写，免得整表重写丢注释）。
 * </p>
 * <p>
 * <b>T3 第二波</b>：管理端 GUI（{@code TraderOrderEditGui}）新增/编辑/删除订单时，
 * 走 {@link #upsertOrder(OrderDefinition, String)} / {@link #deleteOrder(String)} 整表回写
 * 磁盘——这两个方法会把内存里当前的订单列表 + 本次改动，重新拼成一份 nightconfig 的
 * {@code order} 数组整体替换掉 {@code config} 里的旧值，再走
 * {@link ConfigFileWriter#writeAtomically} 原子落盘。<b>注意：这个重建过程会丢失
 * orders.toml 里手写的行内注释</b>（比如每条订单上面那句"交 64 个圆石..."的说明）——
 * nightconfig 的注释是挂在具体 key 路径上的，而这里是把整个 {@code order} 数组换成一批
 * 全新构造的 subConfig，不是"原地改值"，没有旧注释可继承。这是可以接受的取舍：
 * 一旦订单开始走 GUI 编辑，文件里那些手写注释本来就会随时间失去参考价值（描述的是
 * 编辑前的数值），比起额外做一套"注释迁移"的复杂机制，不如接受它们在第一次 GUI 保存后
 * 消失。{@code config-version} 等顶层键没有被整表替换，理论上其上的头部注释不受影响。
 * </p>
 * <p>
 * <b>校验的底线是"宁可整条订单不加载，也不能加载半残的订单"</b>：一条 take-items 为空的
 * 订单在 T2 结算时意味着"什么都不用交就能拿走 give-items 和声望"，而声望是不能用金钱替代的
 * 稀缺资源，一个材料名拼写错误就能捅穿经济命脉。所以材料名解析失败、必填列表为空这些情况
 * 一律抛异常丢弃整条订单并 {@code log.error} 报出原因，绝不静默跳过某一项继续拼装。
 * </p>
 */
public class OrdersConfigManager implements IConfigurationProvider {

    private static final Logger log = LoggerFactory.getLogger(OrdersConfigManager.class);

    /** orders.toml 的结构版本；将来改动订单表结构时靠它判断要不要迁移。 */
    private static final int DEFAULT_CONFIG_VERSION = 1;

    private final CommentedFileConfig config;
    private volatile List<OrderDefinition> orders = List.of();
    private volatile int configVersion = DEFAULT_CONFIG_VERSION;

    public OrdersConfigManager(CommentedFileConfig config) {
        this.config = config;
    }

    @Override
    public void loadConfig() {
        // 版本锚点：本表不做整表回写，所以缺失时只警告 + 用默认值，不改磁盘。
        Object rawVersion = config.get("config-version");
        if (rawVersion instanceof Number number) {
            this.configVersion = number.intValue();
        } else {
            this.configVersion = DEFAULT_CONFIG_VERSION;
            if (rawVersion != null) {
                log.warn("orders.toml 的 config-version 不是数字（实际是 {}），按 {} 处理",
                        rawVersion.getClass().getSimpleName(), DEFAULT_CONFIG_VERSION);
            } else {
                log.warn("orders.toml 缺少 config-version，按 {} 处理；建议补回该键，将来做结构迁移时要靠它",
                        DEFAULT_CONFIG_VERSION);
            }
        }

        List<OrderDefinition> loaded = new ArrayList<>();
        // 按规范化后的 id 去重：管理员复制粘贴订单忘了改 id、或者写成 "Iron-Buy" 与 "iron-buy"，
        // 都会变成两条独立订单却共用同一个终身完成次数计数器 —— 做满 5 次 A 之后 B 也被锁死。
        Set<String> seenIds = new HashSet<>();

        Object ordersObj = config.get("order");
        if (ordersObj instanceof List<?> rawList) {
            int index = 0;
            for (Object element : rawList) {
                index++;
                if (!(element instanceof Config table)) {
                    log.error("orders.toml 第 {} 条 [[order]] 不是一张表，已丢弃", index);
                    continue;
                }
                try {
                    OrderDefinition order = parseOrder(table);
                    if (!seenIds.add(order.id())) {
                        log.error("orders.toml 第 {} 条 [[order]] 的 id '{}' 与前面的订单重复"
                                        + "（id 比较时会去空白并转小写），已丢弃本条；"
                                        + "重复 id 会让两条订单共用同一个终身限购计数器",
                                index, order.id());
                        continue;
                    }
                    loaded.add(order);
                } catch (Exception e) {
                    log.error("orders.toml 第 {} 条 [[order]] 解析失败，已丢弃：{}", index, e.getMessage());
                }
            }
        } else if (ordersObj != null) {
            log.error("orders.toml 顶层 'order' 不是数组，全部订单已忽略");
        }

        this.orders = List.copyOf(loaded);
        log.info("已加载 {} 条商队订单。", orders.size());
    }

    private OrderDefinition parseOrder(Config table) {
        String id = table.getOrElse("id", (String) null);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("缺少 id 字段");
        }
        String normalizedId = OrderDefinition.normalizeId(id);

        boolean enabled = table.getOrElse("enabled", true);
        String displayName = table.getOrElse("display-name", id);

        String rawType = table.getOrElse("type", "money");
        OrderType type = OrderType.parseOrNull(rawType);
        if (type == null) {
            throw new IllegalArgumentException("订单 '" + normalizedId + "' 的 type='" + rawType
                    + "' 无法识别，可选值：money / barter");
        }

        // take-items 两种类型都必须有：它定义了"岛屿交出去什么"，也就是"完成一单"的含义。
        List<ItemAmount> takeItems = parseItemAmounts(table.get("take-items"), normalizedId, "take-items");
        if (takeItems.isEmpty()) {
            throw new IllegalArgumentException("订单 '" + normalizedId
                    + "' 的 take-items 为空：这会变成「什么都不用交就能领奖励」，已丢弃整条订单");
        }

        double price = 0.0;
        List<ItemAmount> giveItems = List.of();

        if (type == OrderType.MONEY) {
            price = numberOrElse(table.get("price"), 0.0);
            if (price <= 0.0) {
                throw new IllegalArgumentException("订单 '" + normalizedId
                        + "' 是 money 类型但 price 不大于 0（price 是完成这一整单支付的总金额）");
            }
        } else {
            giveItems = parseItemAmounts(table.get("give-items"), normalizedId, "give-items");
            if (giveItems.isEmpty()) {
                throw new IllegalArgumentException("订单 '" + normalizedId
                        + "' 是 barter 类型但 give-items 为空：岛屿交了东西却什么都拿不到");
            }
        }

        int rewardReputation = Math.max(0, (int) numberOrElse(table.get("reward-reputation"), 0));
        int weight = Math.max(1, (int) numberOrElse(table.get("weight"), 1));
        // 负数的限购次数语义未定义，统一夹成 0（= 不限），避免 T2 拿它去做减法判定。
        int redeemLimitPerIsland = Math.max(0, (int) numberOrElse(table.get("redeem-limit-per-island"), 0));
        int requiredLevelMin = Math.max(0, (int) numberOrElse(table.get("required-level-min"), 0));
        int requiredReputationMin = Math.max(0, (int) numberOrElse(table.get("required-reputation-min"), 0));

        return new OrderDefinition(normalizedId, enabled, displayName, type, price, giveItems, takeItems,
                rewardReputation, weight, redeemLimitPerIsland, requiredLevelMin, requiredReputationMin);
    }

    /**
     * 解析一个 {@code [{material = "...", amount = N}, ...]} 列表。
     * <p>
     * <b>任何一项解析不出来都直接抛异常</b>（材料名拼错、不是表、缺 material、amount 非正），
     * 让调用方丢弃整条订单。这里绝不能"跳过坏项继续拼"：管理员把 NETHERITE_INGOT 写成
     * NETHERITE_INGOTS，跳过之后 take-items 就空了，订单变成白嫖。超过 4 组则是纯粹的
     * 配置冗余，截断 + 警告即可，不影响交易的正确性。
     * </p>
     */
    private List<ItemAmount> parseItemAmounts(Object rawValue, String orderId, String fieldName) {
        if (rawValue == null) return List.of();
        if (!(rawValue instanceof List<?> rawList)) {
            throw new IllegalArgumentException("订单 '" + orderId + "' 的 " + fieldName + " 不是数组");
        }

        List<ItemAmount> result = new ArrayList<>();
        for (Object element : rawList) {
            if (result.size() >= OrderDefinition.MAX_ITEMS_PER_SIDE) {
                log.warn("订单 '{}' 的 {} 超过 {} 组，多余部分已忽略", orderId, fieldName, OrderDefinition.MAX_ITEMS_PER_SIDE);
                break;
            }
            if (!(element instanceof Config itemTable)) {
                throw new IllegalArgumentException("订单 '" + orderId + "' 的 " + fieldName + " 里有一项不是表");
            }
            String matName = itemTable.getOrElse("material", (String) null);
            if (matName == null) {
                throw new IllegalArgumentException("订单 '" + orderId + "' 的 " + fieldName + " 里有一项缺少 material");
            }
            Material material = parseMaterial(matName, orderId, fieldName);
            int amount = (int) numberOrElse(itemTable.get("amount"), 1);
            if (amount <= 0) {
                throw new IllegalArgumentException("订单 '" + orderId + "' 的 " + fieldName + " 里 '"
                        + matName + "' 的 amount 必须大于 0（实际是 " + amount + "）");
            }
            result.add(new ItemAmount(material, amount));
        }
        return List.copyOf(result);
    }

    private Material parseMaterial(String rawName, String orderId, String fieldName) {
        try {
            return Material.valueOf(rawName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("订单 '" + orderId + "' 的 " + fieldName
                    + " 引用了未知材料 '" + rawName + "'（材料名要用标准 Bukkit Material 枚举名）");
        }
    }

    private double numberOrElse(Object value, double defaultValue) {
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    public List<OrderDefinition> getOrders() {
        return orders;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    /**
     * 新增或替换一条订单，校验通过并原子落盘成功后才更新内存态。
     * <p>
     * <b>磁盘写入失败时内存中的订单列表原封不动</b>——调用方（GUI）必须把 {@code false}
     * 当成"这次保存没生效"处理，提示管理员重试，绝不能因为"内存里已经加进去了"就误报成功。
     * </p>
     *
     * @param order      要保存的完整订单定义；id 已经在构造时被规范化（去空白+转小写）
     * @param previousId 编辑既有订单时传入原 id（本方法据此找到并替换那一条）；
     *                   新增订单传 {@code null}（直接追加）
     * @return 是否成功落盘；{@code false} 还可能是因为 {@code order.id()} 与<b>另一条</b>
     * 订单（不是本次正在编辑的这条）冲突
     */
    public synchronized boolean upsertOrder(@NotNull OrderDefinition order, @Nullable String previousId) {
        String normalizedPrevId = previousId == null ? null : OrderDefinition.normalizeId(previousId);

        List<OrderDefinition> current = new ArrayList<>(this.orders);
        for (OrderDefinition existing : current) {
            if (existing.id().equals(order.id()) && !existing.id().equals(normalizedPrevId)) {
                log.error("保存订单失败：id '{}' 已被其它订单占用，未写入磁盘", order.id());
                return false;
            }
        }

        List<OrderDefinition> updated = new ArrayList<>(current.size() + 1);
        boolean replaced = false;
        for (OrderDefinition existing : current) {
            if (normalizedPrevId != null && existing.id().equals(normalizedPrevId)) {
                updated.add(order);
                replaced = true;
            } else {
                updated.add(existing);
            }
        }
        if (!replaced) {
            updated.add(order);
        }

        if (!writeOrdersToDisk(updated)) {
            return false;
        }
        this.orders = List.copyOf(updated);
        return true;
    }

    /**
     * 删除一条订单并原子落盘。
     *
     * @return {@code false} 表示这个 id 本来就不存在，或者落盘失败——两种情况下内存态都不变
     */
    public synchronized boolean deleteOrder(@NotNull String id) {
        String normalized = OrderDefinition.normalizeId(id);
        List<OrderDefinition> current = new ArrayList<>(this.orders);
        boolean removed = current.removeIf(o -> o.id().equals(normalized));
        if (!removed) return false;

        if (!writeOrdersToDisk(current)) {
            return false;
        }
        this.orders = List.copyOf(current);
        return true;
    }

    /**
     * 把整份订单列表重建成 nightconfig 的 {@code order} 数组并原子落盘。
     * <b>不更新 {@link #orders} 内存态</b>——调用方（{@link #upsertOrder}/{@link #deleteOrder}）
     * 落盘成功后自己更新，这样"落盘失败时内存态不变"这条规则只需要在一处保证。
     */
    private boolean writeOrdersToDisk(List<OrderDefinition> newOrders) {
        List<Config> tables = new ArrayList<>(newOrders.size());
        for (OrderDefinition order : newOrders) {
            tables.add(toTomlTable(order));
        }
        config.set("order", tables);
        if (!(config.get("config-version") instanceof Number)) {
            config.set("config-version", this.configVersion);
        }
        return ConfigFileWriter.writeAtomically(config);
    }

    /** 把一条 {@link OrderDefinition} 拼成一张 nightconfig 表，字段名和 {@link #parseOrder} 一一对应。 */
    private Config toTomlTable(OrderDefinition order) {
        Config table = config.createSubConfig();
        table.set("id", order.id());
        table.set("enabled", order.enabled());
        table.set("display-name", order.displayName());
        table.set("type", order.type() == OrderType.MONEY ? "money" : "barter");
        table.set("take-items", toItemTableList(order.takeItems()));
        if (order.type() == OrderType.MONEY) {
            table.set("price", order.price());
        } else {
            table.set("give-items", toItemTableList(order.giveItems()));
        }
        table.set("reward-reputation", order.rewardReputation());
        table.set("weight", order.weight());
        table.set("redeem-limit-per-island", order.redeemLimitPerIsland());
        table.set("required-level-min", order.requiredLevelMin());
        table.set("required-reputation-min", order.requiredReputationMin());
        return table;
    }

    private List<Config> toItemTableList(List<ItemAmount> items) {
        List<Config> list = new ArrayList<>(items.size());
        for (ItemAmount item : items) {
            Config itemTable = config.createSubConfig();
            itemTable.set("material", item.material().name());
            itemTable.set("amount", item.amount());
            list.add(itemTable);
        }
        return list;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrSetDefault(String path, T defaultValue, Class<T> expectedClass) {
        // orders.toml 是显式条目表，不做"缺项自动补默认值"的整表回写；这里只做只读透传，
        // 满足 IConfigurationProvider 接口即可参与 Skyllia 的全局重载流程。
        Object value = config.get(path);
        if (expectedClass.isInstance(value)) return (T) value;
        return defaultValue;
    }

    @Override
    public boolean canReloadFromDisk() {
        return true;
    }

    @Override
    public void reloadFromDisk() {
        config.load();
    }
}
