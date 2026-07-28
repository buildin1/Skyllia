package fr.euphyllia.skylliachallenge.gui;

import fr.euphyllia.skyllia.gui.ConfirmGui;
import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiTextInput;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import fr.euphyllia.skylliachallenge.hook.HookManager;
import fr.euphyllia.skylliachallenge.loader.ChallengeYamlLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 管理员「挑战等级」配置菜单，结构与 {@link ChallengeAdminGui} 对称，操作的是
 * {@code levels/*.yml} 文件。奖励列表复用 {@link RewardListGui}（挑战与等级共用同一个
 * "rewards" DSL 格式）；「包含的挑战」通过勾选已存在的挑战 ID 来维护。
 */
final class ChallengeLevelAdminGui {

    private static final Logger log = LoggerFactory.getLogger(ChallengeLevelAdminGui.class);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ChallengeLevelAdminGui() {
    }

    private static File levelsFolder() {
        return new File(SkylliaChallenge.getInstance().getDataFolder(), "levels");
    }

    // ═══════════════════════════════════════════════════════════
    //  等级列表
    // ═══════════════════════════════════════════════════════════

    static void openList(@NotNull Player player, int page) {
        File[] files = levelsFolder().listFiles((dir, name) -> name.endsWith(".yml"));
        List<File> list = files == null ? List.of() : Arrays.stream(files)
                .sorted(Comparator.comparing(File::getName)).toList();

        int totalPages = Math.max(1, (int) Math.ceil(list.size() / (double) AdminGuiUtil.PAGE_SIZE));
        int clamped = Math.max(0, Math.min(page, totalPages - 1));

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>挑战等级列表"));
        AdminGuiUtil.applyBorder(inv);

        int from = clamped * AdminGuiUtil.PAGE_SIZE;
        int to = Math.min(from + AdminGuiUtil.PAGE_SIZE, list.size());
        for (int i = from; i < to; i++) {
            File f = list.get(i);
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            String idStr = yml.getString("id", f.getName());
            String name = yml.getString("name", idStr);
            int challengeCount = yml.getStringList("challenges").size();
            int maxSkip = yml.getInt("max-skip", 0);
            String nextLevel = yml.getString("next-level", "无");

            ItemStack icon = ChallengeYamlLoader.resolveGuiItem(yml.getString("item", "STONE"), 1, idStr);
            List<String> lore = List.of(
                    "<dark_gray>─────────",
                    "<gray>ID: " + idStr + "</gray>",
                    "<gray>包含挑战: " + challengeCount + "  可跳过: " + maxSkip + "</gray>",
                    "<gray>下一级: " + nextLevel + "</gray>",
                    "<dark_gray>─────────",
                    "<yellow>点击编辑</yellow>"
            );

            int slot = AdminGuiUtil.contentSlot(i - from);
            inv.setItem(slot, AdminGuiUtil.decorate(icon, name, lore));
            holder.bind(slot, e -> openLevelEditor(player, f));
        }

        if (clamped > 0) {
            inv.setItem(45, GuiItem.prevPage());
            holder.bind(45, e -> openList(player, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(53, GuiItem.nextPage());
            holder.bind(53, e -> openList(player, clamped + 1));
        }

        inv.setItem(4, GuiItem.of(Material.EMERALD, "<!italic><green>+ 新建挑战等级",
                List.of("<dark_gray>─────────", "<gray>点击创建</gray>")));
        holder.bind(4, e -> promptCreateLevel(player));

        inv.setItem(49, GuiItem.back());
        holder.bind(49, e -> ChallengeAdminGui.openMain(player));

        player.openInventory(inv);
    }

    static void promptCreateLevel(@NotNull Player player) {
        GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入新等级的唯一 ID（命名空间:名称，仅小写字母/数字/下划线），例如 skyllia:challenge_level_3：",
                idStr -> {
                    NamespacedKey id = NamespacedKey.fromString(idStr.trim().toLowerCase(Locale.ROOT));
                    if (id == null) {
                        player.sendMessage(Component.text("§cID 格式不正确，请重新输入。"));
                        promptCreateLevel(player);
                        return;
                    }
                    File file = fileForId(id);
                    if (file.exists()) {
                        player.sendMessage(Component.text("§c该 ID 已存在对应的等级文件，请换一个 ID。"));
                        promptCreateLevel(player);
                        return;
                    }
                    YamlConfiguration yml = new YamlConfiguration();
                    yml.set("id", id.asString());
                    yml.set("name", id.asString());
                    yml.set("lore", new ArrayList<String>());
                    yml.set("challenges", new ArrayList<String>());
                    yml.set("max-skip", 0);
                    yml.set("rewards", new ArrayList<String>());
                    yml.set("broadcast", false);
                    yml.set("showInGui", true);
                    yml.set("gui.row", 1);
                    yml.set("gui.column", 1);
                    yml.set("gui.page", 1);
                    yml.set("item", "STONE");
                    yml.set("icon-locked", "BARRIER");
                    yml.set("amount", 1);
                    yml.set("itemLore", new ArrayList<String>());
                    if (!save(yml, file)) {
                        player.sendMessage(Component.text("§c创建失败，请查看控制台日志。"));
                        openList(player, 0);
                        return;
                    }
                    SkylliaChallenge.getInstance().reload();
                    player.sendMessage(Component.text("§a等级 " + id.asString() + " 已创建。"));
                    openLevelEditor(player, file);
                },
                () -> openList(player, 0));
    }

    private static File fileForId(@NotNull NamespacedKey id) {
        String safe = (id.getNamespace() + "_" + id.getKey()).replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(levelsFolder(), safe + ".yml");
    }

    // ═══════════════════════════════════════════════════════════
    //  单个等级编辑器
    // ═══════════════════════════════════════════════════════════

    static void openLevelEditor(@NotNull Player player, @NotNull File file) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String idStr = yml.getString("id", file.getName());
        String name = yml.getString("name", idStr);
        int challengeCount = yml.getStringList("challenges").size();
        int maxSkip = yml.getInt("max-skip", 0);
        String nextLevel = yml.getString("next-level", "");
        boolean broadcast = yml.getBoolean("broadcast", false);
        boolean showInGui = yml.getBoolean("showInGui", true);
        String item = yml.getString("item", "STONE");
        String iconLocked = yml.getString("icon-locked", "BARRIER");
        int amount = Math.max(1, yml.getInt("amount", 1));
        int rewCount = yml.getStringList("rewards").size();
        int guiRow = yml.getInt("gui.row", 1);
        int guiColumn = yml.getInt("gui.column", 1);
        int guiPage = yml.getInt("gui.page", 1);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 45, MM.deserialize("<light_purple>编辑等级 - " + idStr));
        for (int i = 0; i < 45; i++) {
            inv.setItem(i, GuiItem.filler());
        }

        inv.setItem(4, GuiItem.of(Material.WRITTEN_BOOK, "<!italic><light_purple>" + idStr,
                List.of("<dark_gray>─────────",
                        "<gray>包含挑战: " + challengeCount + "  奖励: " + rewCount + "</gray>")));

        inv.setItem(10, GuiItem.of(Material.NAME_TAG, "<!italic><light_purple>名称：" + name,
                List.of("<dark_gray>─────────", "<gray>点击输入新名称（支持 MiniMessage）</gray>")));
        holder.bind(10, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入新的等级名称（支持 MiniMessage 颜色代码）：",
                text -> {
                    setField(file, "name", text);
                    reloadAndReopen(player, file);
                },
                () -> openLevelEditor(player, file)));

        ItemStack iconPreview = ChallengeYamlLoader.resolveGuiItem(item, 1, idStr);
        inv.setItem(11, AdminGuiUtil.decorate(iconPreview, "<light_purple>图标材质：" + item,
                List.of("<dark_gray>─────────", "<gray>点击输入新的材质ID（原版材质或 nexo:/oraxen: 自定义物品ID）</gray>")));
        holder.bind(11, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入解锁后图标材质ID（原版材质，如 DIAMOND_BLOCK；或自定义物品）：",
                text -> {
                    String trimmed = text.trim();
                    if (Material.matchMaterial(trimmed) == null && !HookManager.isCustomItemRef(trimmed)) {
                        player.sendMessage(Component.text("§c无法识别的材质/自定义物品ID，请重新输入。"));
                        openLevelEditor(player, file);
                        return;
                    }
                    setField(file, "item", trimmed);
                    reloadAndReopen(player, file);
                },
                () -> openLevelEditor(player, file)));

        ItemStack lockedPreview = ChallengeYamlLoader.resolveGuiItem(iconLocked, 1, idStr);
        inv.setItem(12, AdminGuiUtil.decorate(lockedPreview, "<light_purple>未解锁图标：" + iconLocked,
                List.of("<dark_gray>─────────", "<gray>点击输入新的材质ID</gray>")));
        holder.bind(12, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入未解锁时显示的图标材质ID（例如 BARRIER）：",
                text -> {
                    String trimmed = text.trim();
                    if (Material.matchMaterial(trimmed) == null && !HookManager.isCustomItemRef(trimmed)) {
                        player.sendMessage(Component.text("§c无法识别的材质/自定义物品ID，请重新输入。"));
                        openLevelEditor(player, file);
                        return;
                    }
                    setField(file, "icon-locked", trimmed);
                    reloadAndReopen(player, file);
                },
                () -> openLevelEditor(player, file)));

        inv.setItem(13, GuiItem.of(Material.HOPPER, "<!italic><light_purple>GUI 显示数量：" + amount,
                List.of("<dark_gray>─────────", "<gray>点击输入新的数量</gray>")));
        holder.bind(13, e -> GuiTextInput.promptNumber(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入 GUI 图标显示数量（整数）：",
                value -> {
                    setField(file, "amount", Math.max(1, (int) Math.round(value)));
                    reloadAndReopen(player, file);
                },
                () -> openLevelEditor(player, file)));

        inv.setItem(14, GuiItem.of(Material.BOOK, "<!italic><light_purple>说明 (lore)",
                List.of("<dark_gray>─────────", "<gray>点击整体重新输入，多行用 ; 分隔</gray>")));
        holder.bind(14, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入等级说明，多行用 ; 分隔（支持 MiniMessage），输入 空 清空：",
                text -> {
                    setStringList(file, "lore", splitLines(text));
                    reloadAndReopen(player, file);
                },
                () -> openLevelEditor(player, file)));

        inv.setItem(15, GuiItem.of(Material.WRITABLE_BOOK, "<!italic><light_purple>GUI 额外说明 (itemLore)",
                List.of("<dark_gray>─────────", "<gray>点击整体重新输入，多行用 ; 分隔</gray>")));
        holder.bind(15, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入 GUI 图标额外说明，多行用 ; 分隔（支持 MiniMessage），输入 空 清空：",
                text -> {
                    setStringList(file, "itemLore", splitLines(text));
                    reloadAndReopen(player, file);
                },
                () -> openLevelEditor(player, file)));

        inv.setItem(19, GuiItem.of(Material.HOPPER_MINECART, "<!italic><light_purple>可跳过挑战数：" + maxSkip,
                List.of("<dark_gray>─────────", "<gray>本等级内允许未完成的挑战数量</gray>", "<gray>点击输入新的数值</gray>")));
        holder.bind(19, e -> GuiTextInput.promptNumber(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入允许跳过的挑战数量（整数，0 表示必须全部完成）：",
                value -> {
                    setField(file, "max-skip", Math.max(0, (int) Math.round(value)));
                    reloadAndReopen(player, file);
                },
                () -> openLevelEditor(player, file)));

        inv.setItem(20, GuiItem.of(Material.ENDER_EYE, "<!italic><light_purple>下一级：" + (nextLevel.isEmpty() ? "无" : nextLevel),
                List.of("<dark_gray>─────────", "<gray>完成本等级后解锁的下一级 ID</gray>", "<gray>点击输入，或输入 无 清空</gray>")));
        holder.bind(20, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入下一级的等级 ID（例如 skyllia:challenge_level_2），输入 无 清空：",
                text -> {
                    String trimmed = text.trim();
                    if (trimmed.equalsIgnoreCase("无") || trimmed.equalsIgnoreCase("none")) {
                        removeField(file, "next-level");
                    } else {
                        NamespacedKey key = NamespacedKey.fromString(trimmed.toLowerCase(Locale.ROOT));
                        if (key == null) {
                            player.sendMessage(Component.text("§cID 格式不正确，请重新输入。"));
                            openLevelEditor(player, file);
                            return;
                        }
                        setField(file, "next-level", key.asString());
                    }
                    reloadAndReopen(player, file);
                },
                () -> openLevelEditor(player, file)));

        inv.setItem(21, broadcast
                ? GuiItem.enabled("广播完成", List.of("<gray>点击切换为关闭</gray>"))
                : GuiItem.disabled("广播完成", List.of("<gray>点击切换为开启</gray>")));
        holder.bind(21, e -> {
            setField(file, "broadcast", !broadcast);
            reloadAndReopen(player, file);
        });

        inv.setItem(22, showInGui
                ? GuiItem.enabled("显示在玩家 GUI", List.of("<gray>点击切换为隐藏</gray>"))
                : GuiItem.disabled("显示在玩家 GUI", List.of("<gray>点击切换为显示</gray>")));
        holder.bind(22, e -> {
            setField(file, "showInGui", !showInGui);
            reloadAndReopen(player, file);
        });

        inv.setItem(23, GuiItem.of(Material.COMPASS, "<!italic><light_purple>GUI 位置：行" + guiRow + " 列" + guiColumn + " 页" + guiPage,
                List.of("<dark_gray>─────────", "<gray>点击输入 行,列,页，例如 2,5,1</gray>")));
        holder.bind(23, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入 GUI 位置，格式 行,列,页，例如 2,5,1：",
                text -> {
                    String[] parts = text.trim().split(",");
                    if (parts.length != 3) {
                        player.sendMessage(Component.text("§c格式不正确，请重新输入。"));
                        openLevelEditor(player, file);
                        return;
                    }
                    try {
                        int row = Integer.parseInt(parts[0].trim());
                        int column = Integer.parseInt(parts[1].trim());
                        int page = Integer.parseInt(parts[2].trim());
                        setField(file, "gui.row", row);
                        setField(file, "gui.column", column);
                        setField(file, "gui.page", page);
                    } catch (NumberFormatException ex) {
                        player.sendMessage(Component.text("§c格式不正确，请重新输入。"));
                        openLevelEditor(player, file);
                        return;
                    }
                    reloadAndReopen(player, file);
                },
                () -> openLevelEditor(player, file)));

        inv.setItem(28, GuiItem.of(Material.CHEST, "<!italic><light_purple>包含的挑战 (" + challengeCount + ")",
                List.of("<dark_gray>─────────", "<gray>点击勾选此等级包含哪些挑战</gray>")));
        holder.bind(28, e -> openChallengeMembership(player, file, 0));

        inv.setItem(29, GuiItem.of(Material.DIAMOND, "<!italic><light_purple>奖励列表 (" + rewCount + ")",
                List.of("<dark_gray>─────────", "<gray>点击查看 / 增删奖励</gray>")));
        holder.bind(29, e -> RewardListGui.open(player, file, 0, () -> openLevelEditor(player, file)));

        inv.setItem(33, GuiItem.danger(Material.BARRIER, "删除该等级",
                List.of("<gray>删除对应的 YAML 文件</gray>")));
        holder.bind(33, e -> ConfirmGui.open(player, "删除等级 " + idStr,
                List.of("<gray>将删除文件 " + file.getName() + "</gray>", "<gray>此操作不可撤销</gray>"),
                () -> {
                    if (file.delete()) {
                        SkylliaChallenge.getInstance().reload();
                        player.sendMessage(Component.text("§a等级 " + idStr + " 已删除。"));
                    } else {
                        player.sendMessage(Component.text("§c删除失败，请查看控制台日志。"));
                    }
                    openList(player, 0);
                },
                () -> openLevelEditor(player, file)));

        inv.setItem(36, GuiItem.back());
        holder.bind(36, e -> openList(player, 0));

        inv.setItem(44, GuiItem.close());
        holder.bind(44, e -> player.closeInventory());

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  挑战归属勾选器
    // ═══════════════════════════════════════════════════════════

    private static void openChallengeMembership(@NotNull Player player, @NotNull File levelFile, int page) {
        File[] files = ChallengeAdminGui.challengesFolder().listFiles((dir, name) -> name.endsWith(".yml"));
        List<File> list = files == null ? List.of() : Arrays.stream(files)
                .sorted(Comparator.comparing(File::getName)).toList();

        List<String> current = YamlConfiguration.loadConfiguration(levelFile).getStringList("challenges");

        int totalPages = Math.max(1, (int) Math.ceil(list.size() / (double) AdminGuiUtil.PAGE_SIZE));
        int clamped = Math.max(0, Math.min(page, totalPages - 1));

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>勾选包含的挑战"));
        AdminGuiUtil.applyBorder(inv);

        int from = clamped * AdminGuiUtil.PAGE_SIZE;
        int to = Math.min(from + AdminGuiUtil.PAGE_SIZE, list.size());
        for (int i = from; i < to; i++) {
            File f = list.get(i);
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            String idStr = yml.getString("id", f.getName());
            String name = yml.getString("name", idStr);
            boolean included = current.contains(idStr);

            List<String> lore = List.of(
                    "<dark_gray>─────────",
                    "<gray>ID: " + idStr + "</gray>",
                    included ? "<green>✔ 已包含，点击移除</green>" : "<gray>点击加入本等级</gray>"
            );
            ItemStack base = new ItemStack(included ? Material.LIME_DYE : Material.GRAY_DYE);
            int slot = AdminGuiUtil.contentSlot(i - from);
            inv.setItem(slot, AdminGuiUtil.decorate(base, name, lore));
            holder.bind(slot, e -> {
                List<String> updated = new ArrayList<>(YamlConfiguration.loadConfiguration(levelFile).getStringList("challenges"));
                if (updated.contains(idStr)) {
                    updated.remove(idStr);
                } else {
                    updated.add(idStr);
                }
                setStringList(levelFile, "challenges", updated);
                SkylliaChallenge.getInstance().reload();
                openChallengeMembership(player, levelFile, clamped);
            });
        }

        if (clamped > 0) {
            inv.setItem(45, GuiItem.prevPage());
            holder.bind(45, e -> openChallengeMembership(player, levelFile, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(53, GuiItem.nextPage());
            holder.bind(53, e -> openChallengeMembership(player, levelFile, clamped + 1));
        }

        inv.setItem(49, GuiItem.back());
        holder.bind(49, e -> openLevelEditor(player, levelFile));

        player.openInventory(inv);
    }

    private static void reloadAndReopen(@NotNull Player player, @NotNull File file) {
        SkylliaChallenge.getInstance().reload();
        openLevelEditor(player, file);
    }

    // ═══════════════════════════════════════════════════════════
    //  YAML 读写辅助
    // ═══════════════════════════════════════════════════════════

    private static void setField(@NotNull File file, @NotNull String path, @NotNull Object value) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        yml.set(path, value);
        save(yml, file);
    }

    private static void removeField(@NotNull File file, @NotNull String path) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        yml.set(path, null);
        save(yml, file);
    }

    private static void setStringList(@NotNull File file, @NotNull String path, @NotNull List<String> values) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        yml.set(path, values);
        save(yml, file);
    }

    private static boolean save(@NotNull YamlConfiguration yml, @NotNull File file) {
        try {
            yml.save(file);
            return true;
        } catch (IOException e) {
            log.error("Failed to save challenge level file {}", file.getName(), e);
            return false;
        }
    }

    private static List<String> splitLines(@NotNull String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("none") || trimmed.equals("空")) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String part : trimmed.split(";")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }
}
