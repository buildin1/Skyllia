package fr.euphyllia.skylliaupgrade.token;

import fr.euphyllia.skylliaupgrade.SkylliaUpgrade;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeConfigLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 「领地拓展令」道具：默认命名牌图标，靠 Material + customModelData 识别（两者都不是
 * 生存模式下玩家能自行设置的属性，所以不用担心玩家改名字冒充）。
 * <p>
 * 只能通过配置管理员在 SkylliaChallenge 里把某个任务的奖励设成同款 Material +
 * customModelData 来发放（见 {@code ItemReward} 的 customModelData 字段）。
 * </p>
 */
public final class UpgradeTokenItem {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /**
     * 插件自己打在令牌上的持久化标记。玩家无论用铁砧改名、丢在地上再捡、还是塞进箱子拿出来，
     * 这个标记都不会丢；而生存模式下玩家没有任何途径给物品写 PDC，所以也伪造不出来。
     * 延迟初始化是因为构造 NamespacedKey 需要插件实例，类加载时机可能早于 onEnable。
     */
    private static volatile NamespacedKey TOKEN_KEY;

    /**
     * 令牌的<b>等级</b>标记。Lv.N 的升级只认第 N 级的令牌，这样每一级都有专属的挑战目标，
     * 而不是攒一堆通用令牌一路点上去（2026-08-22 服主拍板）。
     */
    private static volatile NamespacedKey TIER_KEY;

    private UpgradeTokenItem() {}

    private static NamespacedKey tierKey() {
        NamespacedKey key = TIER_KEY;
        if (key == null) {
            key = new NamespacedKey(SkylliaUpgrade.getInstance(), "territory_token_tier");
            TIER_KEY = key;
        }
        return key;
    }

    private static NamespacedKey tokenKey() {
        NamespacedKey key = TOKEN_KEY;
        if (key == null) {
            key = new NamespacedKey(SkylliaUpgrade.getInstance(), "territory_token");
            TOKEN_KEY = key;
        }
        return key;
    }

    /**
     * 造一张<b>指定等级</b>的领地拓展令。
     *
     * @param tier   这张令牌对应的升级等级（Lv.N 的升级只吃第 N 级的令牌）
     * @param amount 数量
     */
    public static @NotNull ItemStack build(int tier, int amount) {
        ItemStack item = new ItemStack(UpgradeConfigLoader.config.getTokenMaterial(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // 名字里带上等级，玩家一眼能看出这张是给哪一级用的，避免攒了一堆分不清。
            meta.displayName(MM.deserialize(
                    UpgradeConfigLoader.config.getTokenDisplayName() + " <gray>· Lv." + tier + "</gray>"));
            List<Component> lore = new ArrayList<>();
            for (String line : UpgradeConfigLoader.config.getTokenLore()) {
                lore.add(MM.deserialize(line));
            }
            lore.add(MM.deserialize("<dark_gray>─────────"));
            lore.add(MM.deserialize("<gray>可用于：<white>岛屿升级至 Lv." + tier + "</white></gray>"));
            meta.lore(lore);
            meta.setCustomModelData(UpgradeConfigLoader.config.getTokenCustomModelData());
            meta.getPersistentDataContainer().set(tokenKey(), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(tierKey(), PersistentDataType.INTEGER, tier);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean matches(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        if (stack.getType() != UpgradeConfigLoader.config.getTokenMaterial()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;

        // 优先认 PDC 标记：这是本插件自己发出去的令牌，改名不会失效，也无法被伪造。
        Byte tag = meta.getPersistentDataContainer().get(tokenKey(), PersistentDataType.BYTE);
        if (tag != null && tag == (byte) 1) return true;

        // 回退到「材质 + customModelData」：SkylliaChallenge 的 ItemReward 只能配 customModelData，
        // 写不了 PDC，所以挑战奖励发出去的令牌只有这一个特征。删掉这条分支会让已发放的令牌全部作废。
        // 这条同样不怕改名（压根不看名字），customModelData 生存模式下玩家也设不了。
        if (!meta.hasCustomModelData()) return false;
        return meta.getCustomModelData() == UpgradeConfigLoader.config.getTokenCustomModelData();
    }

    /**
     * 读取一张令牌的等级。
     *
     * @return 令牌等级；<b>{@code 0} 表示"没有等级标记"</b>——那是分级机制上线之前发出去的
     * 老令牌，按<b>万能令牌</b>处理（见 {@link #usableFor}），不能因为一次机制变更就把玩家
     * 手里已有的东西作废。
     */
    public static int readTier(ItemStack stack) {
        if (stack == null) return 0;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return 0;
        Integer tier = meta.getPersistentDataContainer().get(tierKey(), PersistentDataType.INTEGER);
        return tier == null ? 0 : tier;
    }

    /**
     * 这张令牌能不能用于升到 {@code targetTier} 级。
     * <p>
     * 规则：等级完全相等才行；等级为 0 的老令牌（分级上线前发出去的）当万能令牌，任何级都能用。
     * </p>
     */
    public static boolean usableFor(ItemStack stack, int targetTier) {
        if (!matches(stack)) return false;
        int tier = readTier(stack);
        return tier == 0 || tier == targetTier;
    }

    /** 统计玩家背包中<b>可用于指定等级</b>的令牌数量。 */
    public static int countInInventory(org.bukkit.inventory.PlayerInventory inventory, int targetTier) {
        int count = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (usableFor(stack, targetTier)) count += stack.getAmount();
        }
        return count;
    }

    /**
     * 从玩家背包扣除指定数量、<b>可用于该等级</b>的令牌，返回是否成功（数量不足则不做任何修改）。
     * <p>
     * 扣除时<b>优先消耗等级完全匹配的</b>，把万能的老令牌留到最后——老令牌哪一级都能用，
     * 先花掉它等于浪费了它的通用性。
     * </p>
     */
    public static boolean consume(org.bukkit.inventory.PlayerInventory inventory, int targetTier, int amount) {
        if (amount <= 0) return true;
        if (countInInventory(inventory, targetTier) < amount) return false;

        int remaining = amount;
        ItemStack[] contents = inventory.getContents();
        // 第一轮：只扣等级精确匹配的
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            boolean exactOnly = (pass == 0);
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                ItemStack stack = contents[i];
                if (!usableFor(stack, targetTier)) continue;
                boolean exact = readTier(stack) == targetTier;
                if (exactOnly != exact) continue;
                int take = Math.min(stack.getAmount(), remaining);
                stack.setAmount(stack.getAmount() - take);
                if (stack.getAmount() <= 0) contents[i] = null;
                remaining -= take;
            }
        }
        inventory.setContents(contents);
        return true;
    }
}
