package fr.euphyllia.skylliachallenge.requirement;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.hook.HookManager;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Map;

public record ItemRequirement(int requirementId, NamespacedKey challengeKey, Material material, int count,
                              String itemName, int customModelData,
                              NamespacedKey itemModel,
                              String customNamespace, String customId) implements ChallengeRequirement {

    private static final boolean HAS_ITEM_MODEL_METHOD;

    static {
        boolean hasMethod;
        try {
            ItemMeta.class.getMethod("getItemModel");
            hasMethod = true;
        } catch (NoSuchMethodException e) {
            hasMethod = false;
        }
        HAS_ITEM_MODEL_METHOD = hasMethod;
    }

    public ItemRequirement(int requirementId, NamespacedKey challengeKey, Material material, int count,
                           String itemName, int customModelData, NamespacedKey itemModel) {
        this(requirementId, challengeKey, material, count, itemName, customModelData, itemModel, null, null);
    }

    public boolean isCustom() {
        return customNamespace != null && customId != null;
    }

    /**
     * 获取用于 GUI 显示的当前总进度（已上交 + 背包中符合条件物品数量）
     */
    public long getDisplayProgress(Player player, Island island) {
        long already = ProgressStoragePartial.getPartial(island.getId(), challengeKey, requirementId);
        int have = 0;
        for (ItemStack is : player.getInventory().getContents()) {
            if (matchesItem(is)) {
                have += is.getAmount();
            }
        }
        return already + have;
    }

    @Override
    public boolean isMet(Player player, Island island) {
        long already = ProgressStoragePartial.getPartial(island.getId(), challengeKey, requirementId);
        int have = 0;
        for (ItemStack is : player.getInventory().getContents()) {
            if (is == null) continue;
            if (!matchesItem(is)) continue;
            have += is.getAmount();
        }
        return (already + have) >= count;
    }

    @Override
    public boolean consume(Player player, Island island) {
        long already = ProgressStoragePartial.getPartial(island.getId(), challengeKey, requirementId);
        long needed = count - already;
        if (needed <= 0) return true;

        long deposited = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack is = contents[i];
            if (is == null) continue;
            if (!matchesItem(is)) continue;

            int take = (int) Math.min(is.getAmount(), needed - deposited);
            if (take <= 0) continue;

            is.setAmount(is.getAmount() - take);
            if (is.getAmount() <= 0) contents[i] = null;
            deposited += take;
        }

        if (deposited > 0) {
            ProgressStoragePartial.addPartial(island.getId(), challengeKey, requirementId, deposited);
            player.getInventory().setContents(contents);
        }

        return deposited == needed;
    }

    /**
     * 检查 ItemStack 是否符合本需求定义的物品（包括自定义物品、Material、customModelData 或 itemModel）
     */
    public boolean matchesItem(ItemStack is) {
        if (is == null || is.getType().isAir()) return false;

        if (isCustom()) {
            return HookManager.matches(is, customNamespace, customId);
        }

        if (is.getType() != material) return false;

        ItemMeta meta = is.getItemMeta();
        if (meta == null) return false;

        if (itemModel != null) {
            if (!HAS_ITEM_MODEL_METHOD) return false;
            NamespacedKey key = meta.getItemModel();
            return key != null && key.equals(itemModel);
        } else if (customModelData != -1) {
            return meta.hasCustomModelData() && meta.getCustomModelData() == customModelData;
        }
        return true;
    }

    @Override
    public Component getDisplay(Locale locale) {
        String displayName;
        if (isCustom()) {
            displayName = itemName;
        } else if (material != null) {
            String prefix = material.isBlock() ? "block.minecraft." : "item.minecraft.";
            displayName = "<lang:" + prefix + material.getKey().getKey() + ">";
        } else {
            displayName = itemName;
        }
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.item.display", Map.of(
                "%item_name%", displayName,
                "%amount%", String.valueOf(count)
        ), false);
    }
}