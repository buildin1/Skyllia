package fr.euphyllia.skylliachallenge.reward;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliachallenge.api.reward.ChallengeReward;
import fr.euphyllia.skylliachallenge.challenge.Challenge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ItemReward implements ChallengeReward {
    private final Material material;
    private final int count;
    private final String displayName;          // MiniMessage 格式，可为 null
    private final List<String> lore;           // MiniMessage 列表，可为空
    private final Map<Enchantment, Integer> enchantments; // 附魔
    private final int customModelData;         // -1 表示不设置；用于匹配其它 addon 定义的自定义物品（如领地拓展令）

    public ItemReward(Material material, int count) {
        this(material, count, null, List.of(), Map.of(), -1);
    }

    public ItemReward(Material material, int count, String displayName,
                      List<String> lore, Map<Enchantment, Integer> enchantments) {
        this(material, count, displayName, lore, enchantments, -1);
    }

    public ItemReward(Material material, int count, String displayName,
                      List<String> lore, Map<Enchantment, Integer> enchantments, int customModelData) {
        this.material = material;
        this.count = count;
        this.displayName = displayName;
        this.lore = (lore != null) ? new ArrayList<>(lore) : new ArrayList<>();
        this.enchantments = (enchantments != null) ? new LinkedHashMap<>(enchantments) : new LinkedHashMap<>();
        this.customModelData = customModelData;
    }

    @Override
    public void apply(Player player, Island island, Challenge challenge) {
        ItemStack item = new ItemStack(material, count);
        ItemMeta meta = item.getItemMeta();

        // 设置自定义名称和 Lore
        if (displayName != null && !displayName.isEmpty()) {
            meta.displayName(MiniMessage.miniMessage().deserialize(displayName));
        }
        if (!lore.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : lore) {
                if (!line.isEmpty()) {
                    loreComponents.add(MiniMessage.miniMessage().deserialize(line));
                }
            }
            meta.lore(loreComponents);
        }

        if (customModelData >= 0) {
            meta.setCustomModelData(customModelData);
        }

        // 处理附魔：附魔书使用 EnchantmentStorageMeta，其他物品使用普通 addEnchant
        if (!enchantments.isEmpty()) {
            if (material == Material.ENCHANTED_BOOK) {
                if (meta instanceof EnchantmentStorageMeta storageMeta) {
                    enchantments.forEach((ench, level) -> storageMeta.addStoredEnchant(ench, level, true));
                }
            } else {
                enchantments.forEach((ench, level) -> meta.addEnchant(ench, level, true));
            }
        }

        item.setItemMeta(meta);
        player.getInventory().addItem(item).forEach((task, leftover) ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    @Override
    public Component getDisplay(Locale locale) {
        // 构建物品名称部分
        String itemDisplay;
        if (displayName != null && !displayName.isEmpty()) {
            itemDisplay = displayName;
        } else {
            String prefix = material.isBlock() ? "block.minecraft." : "item.minecraft.";
            itemDisplay = "<lang:" + prefix + material.getKey().getKey() + ">";
        }

        // 构建附魔描述部分
        StringBuilder enchantBuilder = new StringBuilder();
        if (!enchantments.isEmpty()) {
            enchantments.forEach((ench, level) -> enchantBuilder.append(" <lang:enchantment.minecraft.")
                    .append(ench.getKey().getKey())
                    .append("> ")
                    .append(level));
        }

        String displayStr = "<gray>+ " + count + " x " + itemDisplay + enchantBuilder;
        return MiniMessage.miniMessage().deserialize(displayStr);
    }
}