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

/**
 * 管理员「挑战」配置菜单：创建/编辑/删除挑战，需求与奖励通过 {@link RequirementListGui} /
 * {@link RewardListGui} 以 DSL 文本行的形式增删——文本行直接复用
 * {@link ChallengeYamlLoader#parseRequirements} / {@link ChallengeYamlLoader#parseRewards}
 * 校验，保证写回磁盘的格式与 {@code ChallengeYamlLoader} 能正确解析回来的格式完全一致。
 * <p>
 * 所有编辑都直接读写 {@code challenges/*.yml} 文件本身（而不是内存中已解析的 {@code Challenge}
 * 对象），每次保存后调用 {@link SkylliaChallenge#reload()} 让运行中的管理器与磁盘同步。
 * </p>
 */
public final class ChallengeAdminGui {

    private static final Logger log = LoggerFactory.getLogger(ChallengeAdminGui.class);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ChallengeAdminGui() {
    }

    /** package-visible: 也被 {@link ChallengeLevelAdminGui} 的挑战归属选择器复用。 */
    static File challengesFolder() {
        return new File(SkylliaChallenge.getInstance().getDataFolder(), "challenges");
    }

    // ═══════════════════════════════════════════════════════════
    //  主菜单
    // ═══════════════════════════════════════════════════════════

    public static void openMain(@NotNull Player player) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 27, MM.deserialize("<light_purple>📜 挑战管理"));
        for (int i : new int[]{0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26}) {
            inv.setItem(i, GuiItem.filler());
        }

        inv.setItem(10, GuiItem.of(Material.CHEST, "<!italic><light_purple>挑战列表",
                List.of("<dark_gray>─────────", "<gray>浏览 / 编辑 / 删除挑战</gray>")));
        holder.bind(10, e -> openChallengeList(player, 0));

        inv.setItem(11, GuiItem.of(Material.EMERALD, "<!italic><green>+ 新建挑战",
                List.of("<dark_gray>─────────", "<gray>输入唯一 ID 即可创建</gray>")));
        holder.bind(11, e -> promptCreateChallenge(player));

        inv.setItem(15, GuiItem.of(Material.IRON_BLOCK, "<!italic><light_purple>挑战等级列表",
                List.of("<dark_gray>─────────", "<gray>浏览 / 编辑 / 删除挑战等级</gray>")));
        holder.bind(15, e -> ChallengeLevelAdminGui.openList(player, 0));

        inv.setItem(16, GuiItem.of(Material.EMERALD, "<!italic><green>+ 新建挑战等级",
                List.of("<dark_gray>─────────", "<gray>输入唯一 ID 即可创建</gray>")));
        holder.bind(16, e -> ChallengeLevelAdminGui.promptCreateLevel(player));

        inv.setItem(13, GuiItem.of(Material.REDSTONE_TORCH, "<!italic><yellow>重新加载配置",
                List.of("<dark_gray>─────────", "<gray>从磁盘重新读取所有挑战与等级</gray>")));
        holder.bind(13, e -> {
            SkylliaChallenge.getInstance().reload();
            player.sendMessage(Component.text("§a[SkylliaChallenge] 配置已重新加载。"));
            openMain(player);
        });

        inv.setItem(22, GuiItem.close());
        holder.bind(22, e -> player.closeInventory());

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  挑战列表
    // ═══════════════════════════════════════════════════════════

    static void openChallengeList(@NotNull Player player, int page) {
        File[] files = challengesFolder().listFiles((dir, name) -> name.endsWith(".yml"));
        List<File> list = files == null ? List.of() : Arrays.stream(files)
                .sorted(Comparator.comparing(File::getName)).toList();

        int totalPages = Math.max(1, (int) Math.ceil(list.size() / (double) AdminGuiUtil.PAGE_SIZE));
        int clamped = Math.max(0, Math.min(page, totalPages - 1));

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>挑战列表"));
        AdminGuiUtil.applyBorder(inv);

        int from = clamped * AdminGuiUtil.PAGE_SIZE;
        int to = Math.min(from + AdminGuiUtil.PAGE_SIZE, list.size());
        for (int i = from; i < to; i++) {
            File f = list.get(i);
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            String idStr = yml.getString("id", f.getName());
            String name = yml.getString("name", idStr);
            int level = yml.getInt("level", 1);
            int reqCount = yml.getStringList("requirements").size();
            int rewCount = yml.getStringList("rewards").size();

            ItemStack icon = ChallengeYamlLoader.resolveGuiItem(yml.getString("item", "STONE"), 1, idStr);
            List<String> lore = List.of(
                    "<dark_gray>─────────",
                    "<gray>ID: " + idStr + "</gray>",
                    "<gray>等级: Lv." + level + "</gray>",
                    "<gray>需求: " + reqCount + "  奖励: " + rewCount + "</gray>",
                    "<dark_gray>─────────",
                    "<yellow>点击编辑</yellow>"
            );

            int slot = AdminGuiUtil.contentSlot(i - from);
            inv.setItem(slot, AdminGuiUtil.decorate(icon, name, lore));
            holder.bind(slot, e -> openChallengeEditor(player, f));
        }

        if (clamped > 0) {
            inv.setItem(45, GuiItem.prevPage());
            holder.bind(45, e -> openChallengeList(player, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(53, GuiItem.nextPage());
            holder.bind(53, e -> openChallengeList(player, clamped + 1));
        }

        inv.setItem(4, GuiItem.of(Material.EMERALD, "<!italic><green>+ 新建挑战",
                List.of("<dark_gray>─────────", "<gray>点击创建</gray>")));
        holder.bind(4, e -> promptCreateChallenge(player));

        inv.setItem(49, GuiItem.back());
        holder.bind(49, e -> openMain(player));

        player.openInventory(inv);
    }

    private static void promptCreateChallenge(@NotNull Player player) {
        GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入新挑战的唯一 ID（命名空间:名称，仅小写字母/数字/下划线），例如 skyllia:my_challenge：",
                idStr -> {
                    NamespacedKey id = NamespacedKey.fromString(idStr.trim().toLowerCase(java.util.Locale.ROOT));
                    if (id == null) {
                        player.sendMessage(Component.text("§cID 格式不正确，请重新输入。"));
                        promptCreateChallenge(player);
                        return;
                    }
                    File file = fileForId(id);
                    if (file.exists()) {
                        player.sendMessage(Component.text("§c该 ID 已存在对应的挑战文件，请换一个 ID。"));
                        promptCreateChallenge(player);
                        return;
                    }
                    YamlConfiguration yml = new YamlConfiguration();
                    yml.set("id", id.asString());
                    yml.set("name", id.asString());
                    yml.set("lore", new ArrayList<String>());
                    yml.set("level", 1);
                    yml.set("maxTimes", -1);
                    yml.set("broadcast", false);
                    yml.set("showInGui", true);
                    yml.set("gui.row", 1);
                    yml.set("gui.column", 1);
                    yml.set("gui.page", 1);
                    yml.set("item", "STONE");
                    yml.set("amount", 1);
                    yml.set("itemLore", new ArrayList<String>());
                    yml.set("requirements", new ArrayList<String>());
                    yml.set("rewards", new ArrayList<String>());
                    if (!save(yml, file)) {
                        player.sendMessage(Component.text("§c创建失败，请查看控制台日志。"));
                        openChallengeList(player, 0);
                        return;
                    }
                    SkylliaChallenge.getInstance().reload();
                    player.sendMessage(Component.text("§a挑战 " + id.asString() + " 已创建。"));
                    openChallengeEditor(player, file);
                },
                () -> openChallengeList(player, 0));
    }

    private static File fileForId(@NotNull NamespacedKey id) {
        String safe = (id.getNamespace() + "_" + id.getKey()).replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(challengesFolder(), safe + ".yml");
    }

    // ═══════════════════════════════════════════════════════════
    //  单个挑战编辑器
    // ═══════════════════════════════════════════════════════════

    static void openChallengeEditor(@NotNull Player player, @NotNull File file) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String idStr = yml.getString("id", file.getName());
        String name = yml.getString("name", idStr);
        int level = yml.getInt("level", 1);
        int maxTimes = yml.getInt("maxTimes", -1);
        String cooldown = yml.getString("cooldown", "");
        boolean broadcast = yml.getBoolean("broadcast", false);
        boolean showInGui = yml.getBoolean("showInGui", true);
        String item = yml.getString("item", "STONE");
        int amount = Math.max(1, yml.getInt("amount", 1));
        int reqCount = yml.getStringList("requirements").size();
        int rewCount = yml.getStringList("rewards").size();
        int guiRow = yml.getInt("gui.row", 1);
        int guiColumn = yml.getInt("gui.column", 1);
        int guiPage = yml.getInt("gui.page", 1);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 45, MM.deserialize("<light_purple>编辑挑战 - " + idStr));
        for (int i = 0; i < 45; i++) {
            inv.setItem(i, GuiItem.filler());
        }

        inv.setItem(4, GuiItem.of(Material.WRITTEN_BOOK, "<!italic><light_purple>" + idStr,
                List.of("<dark_gray>─────────",
                        "<gray>等级: Lv." + level + "</gray>",
                        "<gray>需求: " + reqCount + "  奖励: " + rewCount + "</gray>")));

        inv.setItem(10, GuiItem.of(Material.NAME_TAG, "<!italic><light_purple>名称：" + name,
                List.of("<dark_gray>─────────", "<gray>点击输入新名称（支持 MiniMessage）</gray>")));
        holder.bind(10, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入新的挑战名称（支持 MiniMessage 颜色代码）：",
                text -> {
                    setField(file, "name", text);
                    reloadAndReopen(player, file);
                },
                () -> openChallengeEditor(player, file)));

        inv.setItem(11, GuiItem.of(Material.PAPER, "<!italic><light_purple>等级：Lv." + level,
                List.of("<dark_gray>─────────", "<gray>左键 +1 / 右键 -1（1~5 循环）</gray>")));
        holder.bind(11, e -> {
            int delta = e.isRightClick() ? -1 : 1;
            int newLevel = ((level - 1 + delta) % 5 + 5) % 5 + 1;
            setField(file, "level", newLevel);
            reloadAndReopen(player, file);
        });

        ItemStack iconPreview = ChallengeYamlLoader.resolveGuiItem(item, 1, idStr);
        inv.setItem(12, AdminGuiUtil.decorate(iconPreview, "<light_purple>图标材质：" + item,
                List.of("<dark_gray>─────────", "<gray>点击输入新的材质ID（原版材质或 nexo:/oraxen: 自定义物品ID）</gray>")));
        holder.bind(12, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入图标材质ID（原版材质，如 DIAMOND；或自定义物品，如 nexo:custom_wheat）：",
                text -> {
                    String trimmed = text.trim();
                    if (Material.matchMaterial(trimmed) == null && !HookManager.isCustomItemRef(trimmed)) {
                        player.sendMessage(Component.text("§c无法识别的材质/自定义物品ID，请重新输入。"));
                        openChallengeEditor(player, file);
                        return;
                    }
                    setField(file, "item", trimmed);
                    reloadAndReopen(player, file);
                },
                () -> openChallengeEditor(player, file)));

        inv.setItem(13, GuiItem.of(Material.HOPPER, "<!italic><light_purple>GUI 显示数量：" + amount,
                List.of("<dark_gray>─────────", "<gray>点击输入新的数量</gray>")));
        holder.bind(13, e -> GuiTextInput.promptNumber(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入 GUI 图标显示数量（整数）：",
                value -> {
                    setField(file, "amount", Math.max(1, (int) Math.round(value)));
                    reloadAndReopen(player, file);
                },
                () -> openChallengeEditor(player, file)));

        inv.setItem(14, GuiItem.of(Material.BOOK, "<!italic><light_purple>说明 (lore)",
                List.of("<dark_gray>─────────", "<gray>点击整体重新输入，多行用 ; 分隔</gray>")));
        holder.bind(14, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入挑战说明，多行用 ; 分隔（支持 MiniMessage），输入 空 清空：",
                text -> {
                    setStringList(file, "lore", splitLines(text));
                    reloadAndReopen(player, file);
                },
                () -> openChallengeEditor(player, file)));

        inv.setItem(15, GuiItem.of(Material.WRITABLE_BOOK, "<!italic><light_purple>GUI 额外说明 (itemLore)",
                List.of("<dark_gray>─────────", "<gray>点击整体重新输入，多行用 ; 分隔</gray>")));
        holder.bind(15, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入 GUI 图标额外说明，多行用 ; 分隔（支持 MiniMessage），输入 空 清空：",
                text -> {
                    setStringList(file, "itemLore", splitLines(text));
                    reloadAndReopen(player, file);
                },
                () -> openChallengeEditor(player, file)));

        inv.setItem(19, GuiItem.of(Material.CLOCK, "<!italic><light_purple>最大完成次数：" + (maxTimes < 0 ? "无限" : String.valueOf(maxTimes)),
                List.of("<dark_gray>─────────", "<gray>点击输入新的次数（-1 表示无限）</gray>")));
        holder.bind(19, e -> GuiTextInput.promptNumber(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入最大完成次数（整数，-1 表示无限）：",
                value -> {
                    setField(file, "maxTimes", (int) Math.round(value));
                    reloadAndReopen(player, file);
                },
                () -> openChallengeEditor(player, file)));

        inv.setItem(20, GuiItem.of(Material.REPEATER, "<!italic><light_purple>冷却时间：" + (cooldown.isEmpty() ? "无" : cooldown),
                List.of("<dark_gray>─────────", "<gray>格式如 24h / 1d / 1w，输入 无 清空</gray>")));
        holder.bind(20, e -> GuiTextInput.promptText(SkylliaChallenge.getInstance(), player,
                "<yellow>请输入冷却时间（例如 24h、1d、1w），输入 无 清空：",
                text -> {
                    String trimmed = text.trim();
                    if (trimmed.equalsIgnoreCase("无") || trimmed.equalsIgnoreCase("none")) {
                        removeField(file, "cooldown");
                    } else {
                        setField(file, "cooldown", trimmed);
                    }
                    reloadAndReopen(player, file);
                },
                () -> openChallengeEditor(player, file)));

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
                        openChallengeEditor(player, file);
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
                        openChallengeEditor(player, file);
                        return;
                    }
                    reloadAndReopen(player, file);
                },
                () -> openChallengeEditor(player, file)));

        inv.setItem(28, GuiItem.of(Material.CHEST, "<!italic><light_purple>需求列表 (" + reqCount + ")",
                List.of("<dark_gray>─────────", "<gray>点击查看 / 增删需求</gray>")));
        holder.bind(28, e -> RequirementListGui.open(player, file, 0, () -> openChallengeEditor(player, file)));

        inv.setItem(29, GuiItem.of(Material.DIAMOND, "<!italic><light_purple>奖励列表 (" + rewCount + ")",
                List.of("<dark_gray>─────────", "<gray>点击查看 / 增删奖励</gray>")));
        holder.bind(29, e -> RewardListGui.open(player, file, 0, () -> openChallengeEditor(player, file)));

        inv.setItem(33, GuiItem.danger(Material.BARRIER, "删除该挑战",
                List.of("<gray>删除对应的 YAML 文件</gray>")));
        holder.bind(33, e -> ConfirmGui.open(player, "删除挑战 " + idStr,
                List.of("<gray>将删除文件 " + file.getName() + "</gray>", "<gray>此操作不可撤销</gray>"),
                () -> {
                    if (file.delete()) {
                        SkylliaChallenge.getInstance().reload();
                        player.sendMessage(Component.text("§a挑战 " + idStr + " 已删除。"));
                    } else {
                        player.sendMessage(Component.text("§c删除失败，请查看控制台日志。"));
                    }
                    openChallengeList(player, 0);
                },
                () -> openChallengeEditor(player, file)));

        inv.setItem(36, GuiItem.back());
        holder.bind(36, e -> openChallengeList(player, 0));

        inv.setItem(44, GuiItem.close());
        holder.bind(44, e -> player.closeInventory());

        player.openInventory(inv);
    }

    private static void reloadAndReopen(@NotNull Player player, @NotNull File file) {
        SkylliaChallenge.getInstance().reload();
        openChallengeEditor(player, file);
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
            log.error("Failed to save challenge file {}", file.getName(), e);
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
