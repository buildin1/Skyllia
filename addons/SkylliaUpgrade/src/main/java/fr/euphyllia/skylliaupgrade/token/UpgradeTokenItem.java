package fr.euphyllia.skylliaupgrade.token;

import fr.euphyllia.skylliaupgrade.configuration.UpgradeConfigLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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

    private UpgradeTokenItem() {}

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
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean matches(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        if (stack.getType() != UpgradeConfigLoader.config.getTokenMaterial()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasCustomModelData()) return false;
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
