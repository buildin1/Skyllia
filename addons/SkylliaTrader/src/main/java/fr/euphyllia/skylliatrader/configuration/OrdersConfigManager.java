package fr.euphyllia.skylliatrader.configuration;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import fr.euphyllia.skylliatrader.configuration.model.ItemAmount;
import fr.euphyllia.skylliatrader.configuration.model.OrderDefinition;
import fr.euphyllia.skylliatrader.configuration.model.OrderType;
import org.bukkit.Material;
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
 * 显式写的条目，不是"缺项自动补默认值"的键值表，所以本类只做"解析 + 校验 + 丢弃坏条目"，
 * 不做整表回写（连 {@code config-version} 缺失时也只警告不补写，免得整表重写丢注释）。
 * T3 起管理端 GUI 需要新增/编辑/删除订单时，再补写回磁盘的能力（写回时要保留 TOML 注释，
 * 直接用 {@link com.electronwill.nightconfig.toml.TomlWriter} 整表覆盖会丢注释，
 * 到时候需要专门处理，这里先留 TODO）。
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
