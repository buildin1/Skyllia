package fr.euphyllia.skylliatrader.configuration;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import fr.euphyllia.skylliatrader.configuration.model.ShopExtraGate;
import fr.euphyllia.skylliatrader.configuration.model.ShopItemDefinition;
import fr.euphyllia.skylliatrader.configuration.model.ShopPurchaseLimitPeriod;
import fr.euphyllia.skylliatrader.configuration.model.ShopUnlockTrack;
import fr.euphyllia.skylliatrader.merchant.CaravanType;
import org.bukkit.Material;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code shop.toml} 的运行时读取。
 * <p>
 * 分工和校验规则完全照抄 {@link OrdersConfigManager}：显式条目表，不做"缺项自动补默认值"的
 * 整表回写；<b>宁可整条商品不加载，也不能加载半残的商品</b>——材料名拼错、必填字段缺失，
 * 一律丢弃整条并 {@code log.error}，绝不静默降级继续拼装（降级会让一条商品用错误的解锁轨道/
 * 价格上架，比不上架更危险）。id 重复时同样整条丢弃，避免两条商品共用同一个限购计数器。
 * </p>
 */
public class ShopConfigManager implements IConfigurationProvider {

    private static final Logger log = LoggerFactory.getLogger(ShopConfigManager.class);

    private static final int DEFAULT_CONFIG_VERSION = 1;

    /** {@code shop.toml} 里不允许出现的保留 id：说明书由 {@code GuidebookConfig} 单独管理，
     * 购买事务用这个字符串当限购计数的 key，商品表里如果也写了同名 id 会造成计数器撞车。 */
    public static final String GUIDEBOOK_RESERVED_ID = "guidebook";

    private final CommentedFileConfig config;
    private volatile List<ShopItemDefinition> items = List.of();
    private volatile Map<String, ShopItemDefinition> itemsById = Map.of();
    private volatile int configVersion = DEFAULT_CONFIG_VERSION;

    public ShopConfigManager(CommentedFileConfig config) {
        this.config = config;
    }

    @Override
    public void loadConfig() {
        Object rawVersion = config.get("config-version");
        if (rawVersion instanceof Number number) {
            this.configVersion = number.intValue();
        } else {
            this.configVersion = DEFAULT_CONFIG_VERSION;
            if (rawVersion != null) {
                log.warn("shop.toml 的 config-version 不是数字（实际是 {}），按 {} 处理",
                        rawVersion.getClass().getSimpleName(), DEFAULT_CONFIG_VERSION);
            } else {
                log.warn("shop.toml 缺少 config-version，按 {} 处理", DEFAULT_CONFIG_VERSION);
            }
        }

        List<ShopItemDefinition> loaded = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        Object itemsObj = config.get("shop-item");
        if (itemsObj instanceof List<?> rawList) {
            int index = 0;
            for (Object element : rawList) {
                index++;
                if (!(element instanceof Config table)) {
                    log.error("shop.toml 第 {} 条 [[shop-item]] 不是一张表，已丢弃", index);
                    continue;
                }
                try {
                    ShopItemDefinition item = parseItem(table);
                    if (GUIDEBOOK_RESERVED_ID.equals(item.id())) {
                        log.error("shop.toml 第 {} 条 [[shop-item]] 的 id '{}' 是保留字（说明书专用），"
                                        + "已丢弃本条；说明书由 guidebook.* 配置管理，不要在 shop.toml 里重名",
                                index, GUIDEBOOK_RESERVED_ID);
                        continue;
                    }
                    if (!seenIds.add(item.id())) {
                        log.error("shop.toml 第 {} 条 [[shop-item]] 的 id '{}' 与前面的商品重复"
                                        + "（比较时会去空白并转小写），已丢弃本条；重复 id 会让两条商品共用"
                                        + "同一个限购计数器",
                                index, item.id());
                        continue;
                    }
                    loaded.add(item);
                } catch (Exception e) {
                    log.error("shop.toml 第 {} 条 [[shop-item]] 解析失败，已丢弃：{}", index, e.getMessage());
                }
            }
        } else if (itemsObj != null) {
            log.error("shop.toml 顶层 'shop-item' 不是数组，全部商品已忽略");
        }

        ensureFrogspawn(loaded, seenIds);

        this.items = List.copyOf(loaded);
        Map<String, ShopItemDefinition> byId = new HashMap<>();
        for (ShopItemDefinition item : loaded) {
            byId.put(item.id(), item);
        }
        this.itemsById = Map.copyOf(byId);
        log.info("已加载 {} 条商店商品。", items.size());
    }

    /**
     * 存量 shop.toml 不会随 jar 更新。青蛙卵是主世界基础池断链材料，缺了全服卡进度。
     * 只补进内存，不回写文件——回写会把 shop.toml 的注释冲掉。
     */
    private void ensureFrogspawn(List<ShopItemDefinition> loaded, Set<String> seenIds) {
        if (seenIds.contains("frogspawn")) return;
        loaded.add(new ShopItemDefinition(
                "frogspawn",
                Material.FROGSPAWN,
                "<white>青蛙卵",
                4.0,
                ShopUnlockTrack.TRADE_COUNT,
                0L,
                true,
                ShopPurchaseLimitPeriod.NONE,
                0,
                ShopExtraGate.NONE,
                false, // 可再生，不进回收
                null));
        seenIds.add("frogspawn");
        log.warn("shop.toml 缺少青蛙卵，已在内存里补进主世界基础池。请在配置里加一条 id=frogspawn，否则下次手改文件后会再丢");
    }

    private ShopItemDefinition parseItem(Config table) {
        String id = table.getOrElse("id", (String) null);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("缺少 id 字段");
        }
        String normalizedId = ShopItemDefinition.normalizeId(id);

        String rawMaterial = table.getOrElse("material", (String) null);
        if (rawMaterial == null || rawMaterial.isBlank()) {
            throw new IllegalArgumentException("商品 '" + normalizedId + "' 缺少 material 字段");
        }
        Material material = parseMaterial(rawMaterial, normalizedId);

        String displayName = table.getOrElse("display-name", id);

        double price = numberOrElse(table.get("price"), -1.0);
        if (price <= 0.0) {
            throw new IllegalArgumentException("商品 '" + normalizedId + "' 的 price 必须大于 0（实际是 " + price + "）");
        }

        String rawTrack = table.getOrElse("unlock-track", (String) null);
        ShopUnlockTrack unlockTrack = ShopUnlockTrack.parseOrNull(rawTrack);
        if (unlockTrack == null) {
            throw new IllegalArgumentException("商品 '" + normalizedId + "' 的 unlock-track='" + rawTrack
                    + "' 无法识别，可选值：TRADE_COUNT / ISLAND_LEVEL / REPUTATION");
        }

        long unlockTier = Math.max(0L, (long) numberOrElse(table.get("unlock-tier"), 0.0));

        boolean naturalVisible = table.getOrElse("natural-visible", false);
        if (naturalVisible && (unlockTrack != ShopUnlockTrack.TRADE_COUNT || unlockTier != 0L)) {
            // 这不是致命错误（不影响事务正确性——路人商人的商品池由 natural-visible 直接决定，
            // 不会因为轨道搭配得奇怪就出 bug），但违反了"路人商人只卖基础生活池"的设计意图，
            // 警告一下让管理员自己决定要不要改，不丢弃整条商品。
            log.warn("商品 '{}' 的 natural-visible=true，但 unlock-track/unlock-tier 不是"
                    + "「交易次数轨第 0 档」——按设计意图，只有基础生活池才应该给路人商人卖，"
                    + "请检查配置是否写错", normalizedId);
        }

        String rawPeriod = table.getOrElse("purchase-limit-period", "NONE");
        ShopPurchaseLimitPeriod limitPeriod = ShopPurchaseLimitPeriod.parseOrNull(rawPeriod);
        if (limitPeriod == null) {
            throw new IllegalArgumentException("商品 '" + normalizedId + "' 的 purchase-limit-period='"
                    + rawPeriod + "' 无法识别，可选值：NONE / DAILY / WEEKLY / MONTHLY / LIFETIME");
        }

        int limitCount = Math.max(0, (int) numberOrElse(table.get("purchase-limit-count"), 0.0));
        if (limitPeriod.limited() && limitCount <= 0) {
            throw new IllegalArgumentException("商品 '" + normalizedId + "' 的 purchase-limit-period='"
                    + limitPeriod + "' 但 purchase-limit-count 不大于 0，这会让商品永远买不到");
        }

        String rawGate = table.getOrElse("extra-gate", (String) null);
        ShopExtraGate extraGate = ShopExtraGate.parseOrDefault(rawGate);
        if (extraGate == null) {
            throw new IllegalArgumentException("商品 '" + normalizedId + "' 的 extra-gate='" + rawGate
                    + "' 无法识别，目前只支持 NETHERITE_INGOT_DUAL（留空表示无额外门槛）");
        }

        // 可再生物品（树苗/作物/竹子/仙人掌这类能靠农场无限量产的）必须标成 recyclable = false。
        // 理由见 HANDOFF 5.1 的定价不变量：计分体系早就把「*_LEAVES / 作物 / 植被」判成 ×0 分，
        // 因为"树场可无限量产"；回收却是拿真金白银换这些东西，一旦放开就是一个零成本的
        // 无限印钞口（2026-08-21 服主指出：一个自动竹子农场每小时能换到设计日收入的几十倍）。
        // 缺省 false：漏写字段时不进回收。只给钻石/回响/海洋之心/残骸/哭泣黑曜石/镀金黑石显式开 true。
        boolean recyclable = table.getOrElse("recyclable", false);

        // 可选：专供商队。写了就只在该种商队（凭证游商）的货架上出现，留空 = 所有商队通卖。
        String rawCaravan = table.getOrElse("caravan", (String) null);
        CaravanType caravan = null;
        if (rawCaravan != null && !rawCaravan.isBlank()) {
            caravan = CaravanType.parseOrNull(rawCaravan);
            if (caravan == null) {
                throw new IllegalArgumentException("商品 '" + normalizedId + "' 的 caravan='" + rawCaravan
                        + "' 无法识别，可选值：OVERWORLD / NETHER / END（留空表示所有商队通卖）");
            }
        }

        return new ShopItemDefinition(normalizedId, material, displayName, price, unlockTrack, unlockTier,
                naturalVisible, limitPeriod, limitCount, extraGate, recyclable, caravan);
    }

    private Material parseMaterial(String rawName, String itemId) {
        try {
            return Material.valueOf(rawName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("商品 '" + itemId + "' 引用了未知材料 '" + rawName
                    + "'（材料名要用标准 Bukkit Material 枚举名）");
        }
    }

    private double numberOrElse(Object value, double defaultValue) {
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    /** 全部商品定义，顺序与 {@code shop.toml} 一致。 */
    public List<ShopItemDefinition> getItems() {
        return items;
    }

    /** 按规范化 id 查商品，找不到返回 {@code null}。 */
    public ShopItemDefinition findById(String id) {
        return itemsById.get(ShopItemDefinition.normalizeId(id));
    }

    public int getConfigVersion() {
        return configVersion;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrSetDefault(String path, T defaultValue, Class<T> expectedClass) {
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
