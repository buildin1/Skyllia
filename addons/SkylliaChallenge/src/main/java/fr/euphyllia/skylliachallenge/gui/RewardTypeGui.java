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
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 奖励类型选择器：与 {@link RequirementTypeGui} 同样的思路，管理员只输入参数，代码拼出完整
 * DSL 行并用 {@link ChallengeYamlLoader#parseRewards} 校验。挑战与挑战等级的奖励复用同一套逻辑。
 */
final class RewardTypeGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record TypeDef(Material icon, String label, List<String> lore, String prefix, String exampleParams,
                            String paramHint) {
    }

    private RewardTypeGui() {
    }

    static void open(@NotNull Player player, @NotNull Consumer<String> onAccepted, @NotNull Runnable onCancel) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 27, MM.deserialize("<light_purple>选择奖励类型"));
        for (int i : new int[]{0, 1, 2, 6, 7, 8, 9, 17, 18, 26}) {
            inv.setItem(i, GuiItem.filler());
        }

        List<TypeDef> types = buildTypes();
        int slot = 10;
        for (TypeDef def : types) {
            inv.setItem(slot, GuiItem.of(def.icon(), "<!italic><light_purple>" + def.label(), def.lore()));
            holder.bind(slot, e -> promptParams(player, def, onAccepted, onCancel));
            slot++;
        }

        inv.setItem(22, GuiItem.back());
        holder.bind(22, e -> onCancel.run());

        player.openInventory(inv);
    }

    private static void promptParams(@NotNull Player player, @NotNull TypeDef def,
                                      @NotNull Consumer<String> onAccepted, @NotNull Runnable onCancel) {
        String prompt = "<yellow>请输入「" + def.label() + "」的参数：" + def.paramHint()
                + "\n<gray>例如：" + def.exampleParams();
        GuiTextInput.promptText(SkylliaChallenge.getInstance(), player, prompt,
                params -> {
                    String line = def.prefix() + params.trim();
                    if (!validate(line)) {
                        player.sendMessage(Component.text("§c参数格式不正确，请重新输入。"));
                        promptParams(player, def, onAccepted, onCancel);
                        return;
                    }
                    onAccepted.accept(line);
                },
                onCancel);
    }

    private static boolean validate(@NotNull String line) {
        try {
            return ChallengeYamlLoader.parseRewards(List.of(line)).size() == 1;
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
        list.add(new TypeDef(Material.CHEST, "给予物品 (ITEM)",
                lore("给予玩家指定材质和数量的物品。", "可附加 NAME:/LORE:/MODEL:/ENCHANT: 关键字。"),
                "ITEM:", "DIAMOND 3 NAME:<gold>钻石奖励 LORE:感谢完成挑战 ENCHANT:sharpness;5",
                "<gray>材质ID [数量] [NAME:名称] [LORE:描述;描述2] [MODEL:自定义模型数据] [ENCHANT:附魔;等级;附魔2;等级2]"));
        list.add(new TypeDef(Material.COMMAND_BLOCK, "执行命令 (CMD)",
                lore("以控制台身份执行一条命令。", "可用占位符 %player% / %island%。"),
                "CMD:", "broadcast %player% 完成了一个挑战！", "<gray>完整的控制台命令"));
        if (Bukkit.getPluginManager().getPlugin("SkylliaBank") != null) {
            list.add(new TypeDef(Material.GOLD_INGOT, "存入岛屿银行 (BANK)",
                    lore("给岛屿银行 (SkylliaBank) 存入指定金额。"),
                    "BANK:", "5000", "<gray>金额"));
        }
        return list;
    }
}
