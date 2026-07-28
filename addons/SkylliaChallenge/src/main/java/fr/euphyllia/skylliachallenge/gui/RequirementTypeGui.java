package fr.euphyllia.skylliachallenge.gui;

import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiTextInput;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import fr.euphyllia.skylliachallenge.loader.ChallengeYamlLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 需求类型选择器：点击一种需求类型后，用 {@link GuiTextInput} 提示管理员输入该类型的参数，
 * 拼接出与 {@code ChallengeYamlLoader} 解析规则完全一致的 DSL 行，并复用
 * {@link ChallengeYamlLoader#parseRequirements} 校验合法性，校验通过才回调 {@code onAccepted}。
 * <p>
 * 管理员只需输入「参数」而不是完整 DSL 前缀，避免手写前缀拼写错误（例如 POTION 类型在解析器里
 * 要求冒号后带一个空格，这里直接由代码固定拼接，管理员不需要知道这个细节）。
 * </p>
 */
final class RequirementTypeGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record TypeDef(Material icon, String label, List<String> lore, String prefix, String exampleParams,
                            String paramHint) {
    }

    private RequirementTypeGui() {
    }

    static void open(@NotNull Player player, @NotNull NamespacedKey challengeId,
                      @NotNull Consumer<String> onAccepted, @NotNull Runnable onCancel) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>选择需求类型"));
        AdminGuiUtil.applyBorder(inv);

        List<TypeDef> types = buildTypes();
        for (int i = 0; i < types.size(); i++) {
            TypeDef def = types.get(i);
            int slot = AdminGuiUtil.contentSlot(i);
            inv.setItem(slot, GuiItem.of(def.icon(), "<!italic><light_purple>" + def.label(), def.lore()));
            holder.bind(slot, e -> promptParams(player, challengeId, def, onAccepted, onCancel));
        }

        inv.setItem(49, GuiItem.back());
        holder.bind(49, e -> onCancel.run());

        player.openInventory(inv);
    }

    private static void promptParams(@NotNull Player player, @NotNull NamespacedKey challengeId, @NotNull TypeDef def,
                                      @NotNull Consumer<String> onAccepted, @NotNull Runnable onCancel) {
        String prompt = "<yellow>请输入「" + def.label() + "」的参数：" + def.paramHint()
                + "\n<gray>例如：" + def.exampleParams();
        GuiTextInput.promptText(SkylliaChallenge.getInstance(), player, prompt,
                params -> {
                    String line = def.prefix() + params.trim();
                    if (!validate(challengeId, line)) {
                        player.sendMessage(Component.text("§c参数格式不正确，请重新输入。"));
                        promptParams(player, challengeId, def, onAccepted, onCancel);
                        return;
                    }
                    onAccepted.accept(line);
                },
                onCancel);
    }

    private static boolean validate(@NotNull NamespacedKey challengeId, @NotNull String line) {
        try {
            return ChallengeYamlLoader.parseRequirements(SkylliaChallenge.getInstance(), challengeId, List.of(line)).size() == 1;
        } catch (Exception ex) {
            return false;
        }
    }

    private static List<String> lore(String... lines) {
        List<String> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            result.add("<gray>" + line);
        }
        return result;
    }

    private static List<TypeDef> buildTypes() {
        List<TypeDef> list = new ArrayList<>();
        list.add(new TypeDef(Material.WHEAT, "上交物品 (ITEM)",
                lore("玩家需上交指定数量的物品到挑战。", "支持自定义物品 (Nexo/Oraxen)。"),
                "ITEM:", "DIAMOND 64", "<gray>材质ID 数量"));
        list.add(new TypeDef(Material.CRAFTING_TABLE, "合成物品 (CRAFT)",
                lore("玩家需合成指定数量的物品。", "支持自定义物品 (Nexo/Oraxen)。"),
                "CRAFT:", "IRON_PICKAXE 5", "<gray>材质ID 数量"));
        list.add(new TypeDef(Material.ZOMBIE_HEAD, "岛内实体存在 (ENTITY)",
                lore("岛屿范围内存在指定数量的实体。", "可选：加第三个数字改为「玩家周围指定半径内」。"),
                "ENTITY:", "COW 20", "<gray>实体类型 数量 [半径]"));
        list.add(new TypeDef(Material.LEAD, "玩家附近实体 (NEAR)",
                lore("玩家站在指定半径内，附近存在指定数量的实体。"),
                "NEAR:", "ZOMBIE 5 10", "<gray>实体类型 数量 半径"));
        list.add(new TypeDef(Material.DIAMOND_BLOCK, "玩家附近方块 (BLOCKNEAR)",
                lore("玩家站在指定半径内，附近存在指定数量的方块。"),
                "BLOCKNEAR:", "DIAMOND_BLOCK 3 10", "<gray>材质ID 数量 半径"));
        list.add(new TypeDef(Material.IRON_PICKAXE, "破坏方块 (BLOCKBREAK)",
                lore("玩家需累计破坏指定数量的方块。", "支持自定义方块 (Nexo/Oraxen)。"),
                "BLOCKBREAK:", "STONE 100", "<gray>材质ID 数量"));
        list.add(new TypeDef(Material.ENCHANTED_BOOK, "使用附魔 (ENCHANTMENT)",
                lore("玩家需累计使用指定等级以上的附魔次数。", "可选第三个参数 true/false 表示是否要求等级完全一致 (strict)。"),
                "ENCHANTMENT:", "minecraft:sharpness 3 10", "<gray>附魔命名空间键 等级 数量 [strict]"));
        list.add(new TypeDef(Material.FISHING_ROD, "钓鱼 (FISH)",
                lore("玩家需累计钓上指定数量的物品。"),
                "FISH:", "SALMON 20", "<gray>物品材质ID 数量"));
        list.add(new TypeDef(Material.IRON_SWORD, "击杀实体 (KILL)",
                lore("玩家需累计击杀指定数量的实体。"),
                "KILL:", "ZOMBIE 20", "<gray>实体类型 数量"));
        list.add(new TypeDef(Material.APPLE, "消耗物品 (CONSUME)",
                lore("玩家需累计吃/喝指定数量的物品。", "支持药水：potion[type=STRENGTH,level=1]", "支持自定义物品 (Nexo/Oraxen)。"),
                "CONSUME:", "GOLDEN_APPLE 5", "<gray>材质ID 数量"));
        list.add(new TypeDef(Material.MAGENTA_DYE, "喝下药水效果 (POTION)",
                lore("玩家当前身上需具有指定数量的对应药水效果。"),
                "POTION: ", "STRENGTH 1 5", "<gray>药水类型 效果等级 数量"));
        if (Bukkit.getPluginManager().getPlugin("SkylliaBank") != null) {
            list.add(new TypeDef(Material.GOLD_INGOT, "岛屿银行余额 (BANK)",
                    lore("岛屿银行 (SkylliaBank) 余额需达到指定数值。"),
                    "BANK:", "5000", "<gray>金额"));
        }
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            list.add(new TypeDef(Material.EMERALD, "玩家经济余额 (ECO)",
                    lore("玩家的 Vault 经济余额需达到指定数值。"),
                    "ECO:", "5000", "<gray>金额"));
        }
        return list;
    }
}
