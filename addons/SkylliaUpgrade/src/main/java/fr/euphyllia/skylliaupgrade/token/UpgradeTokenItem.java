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

    private UpgradeTokenItem() {}

    private static NamespacedKey tokenKey() {
        NamespacedKey key = TOKEN_KEY;
        if (key == null) {
            key = new NamespacedKey(SkylliaUpgrade.getInstance(), "territory_token");
            TOKEN_KEY = key;
        }
        return key;
    }

    public static @NotNull ItemStack build(int amount) {
        ItemStack item = new ItemStack(UpgradeConfigLoader.config.getTokenMaterial(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MM.deserialize(UpgradeConfigLoader.config.getTokenDisplayName()));
            List<Component> lore = new ArrayList<>();
            for (String line : UpgradeConfigLoader.config.getTokenLore()) {
                lore.add(MM.deserialize(line));
            }
            meta.lore(lore);
            meta.setCustomModelData(UpgradeConfigLoader.config.getTokenCustomModelData());
            meta.getPersistentDataContainer().set(tokenKey(), PersistentDataType.BYTE, (byte) 1);
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

    /** 统计玩家背包中的令牌数量 */
    public static int countInInventory(org.bukkit.inventory.PlayerInventory inventory) {
        int count = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (matches(stack)) count += stack.getAmount();
        }
        return count;
    }

    /** 从玩家背包扣除指定数量的令牌，返回是否成功（数量不足则不做任何修改） */
    public static boolean consume(org.bukkit.inventory.PlayerInventory inventory, int amount) {
        if (amount <= 0) return true;
        if (countInInventory(inventory) < amount) return false;

        int remaining = amount;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (!matches(stack)) continue;
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) contents[i] = null;
            remaining -= take;
        }
        inventory.setContents(contents);
        return true;
    }
}
