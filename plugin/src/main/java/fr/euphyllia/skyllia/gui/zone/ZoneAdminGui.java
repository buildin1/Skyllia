package fr.euphyllia.skyllia.gui.zone;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.zone.ActivityZone;
import fr.euphyllia.skyllia.gui.ConfirmGui;
import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiTextInput;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skyllia.managers.zone.ActivityZoneManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 管理员「活动区」配置菜单：列表 → 逐个编辑半径 / 权限标记 / 删除。
 * <p>
 * 只能通过 {@code /skylliadmin zone gui} 打开，不接入 {@code GuiExtensionRegistry}
 * 的「扩展功能」玩家菜单——跟本次会话的 SkylliaUpgrade/SkylliaChallenge 管理员菜单
 * 保持同样的「命令直达，不对普通玩家可见」的约定。
 * </p>
 */
public final class ZoneAdminGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 28;

    private ZoneAdminGui() {}

    // ═══════════════════════════════════════════════════════════
    //  活动区列表
    // ═══════════════════════════════════════════════════════════

    public static void openList(@NotNull Player player, int page) {
        List<ActivityZone> zones = new ArrayList<>(
                Skyllia.getInstance().getInterneAPI().getActivityZoneManager().getAll());
        zones.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

        int totalPages = Math.max(1, (int) Math.ceil(zones.size() / (double) PAGE_SIZE));
        int clampedPage = Math.max(0, Math.min(page, totalPages - 1));

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>🏬 活动区管理"));

        for (int i : new int[]{0, 1, 2, 3, 5, 6, 8, 9, 17, 18, 26, 27, 35, 36, 44, 46, 47, 48, 50, 51, 52}) {
            inv.setItem(i, GuiItem.filler());
        }

        int fromIndex = clampedPage * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, zones.size());

        int slot = 10;
        int col = 0;
        for (int i = fromIndex; i < toIndex; i++) {
            ActivityZone zone = zones.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>─────────");
            lore.add("<gray>中心：(" + zone.centerX() + ", " + zone.centerZ() + ")</gray>");
            lore.add("<gray>内容半径：" + fmt(zone.contentRadius()) + " 格</gray>");
            lore.add("<gray>缓冲半径：" + fmt(zone.bufferRadius()) + " 格</gray>");
            lore.add("<gray>破坏：" + onOff(zone.allowBreak()) + "  放置：" + onOff(zone.allowPlace()) + "</gray>");
            lore.add("<gray>PVP：" + onOff(zone.allowPvp()) + "  生物攻击：" + onOff(zone.allowMobAttack()) + "</gray>");
            lore.add("<dark_gray>─────────");
            lore.add("<yellow>点击编辑</yellow>");

            inv.setItem(slot, GuiItem.of(Material.ITEM_FRAME, "<!italic><light_purple>" + zone.name(), lore));
            String zoneName = zone.name();
            holder.bind(slot, e -> openEditor(player, zoneName));

            col++;
            slot++;
            if (col == 7) {
                slot += 2;
                col = 0;
            }
        }

        if (clampedPage > 0) {
            inv.setItem(45, GuiItem.prevPage());
            holder.bind(45, e -> openList(player, clampedPage - 1));
        }
        if (clampedPage < totalPages - 1) {
            inv.setItem(53, GuiItem.nextPage());
            holder.bind(53, e -> openList(player, clampedPage + 1));
        }

        inv.setItem(49, GuiItem.close());
        holder.bind(49, e -> player.closeInventory());

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  单个活动区编辑器
    // ═══════════════════════════════════════════════════════════

    public static void openEditor(@NotNull Player player, @NotNull String zoneName) {
        ActivityZoneManager manager = Skyllia.getInstance().getInterneAPI().getActivityZoneManager();
        Optional<ActivityZone> optZone = manager.getByName(zoneName);
        if (optZone.isEmpty()) {
            player.sendMessage(Component.text("§c该活动区不存在（可能已被删除）。"));
            openList(player, 0);
            return;
        }
        ActivityZone zone = optZone.get();

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 45, MM.deserialize("<light_purple>⚙ 编辑活动区：" + zone.name()));

        for (int i : new int[]{0, 1, 2, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44}) {
            inv.setItem(i, GuiItem.filler());
        }

        // 内容半径
        inv.setItem(10, GuiItem.of(Material.MAP, "<!italic><light_purple>内容半径：" + fmt(zone.contentRadius()) + " 格",
                List.of("<dark_gray>─────────",
                        "<gray>玩家在区域内看到的可见边境</gray>",
                        "<gray>点击输入新的数值</gray>")));
        holder.bind(10, e -> GuiTextInput.promptNumber(Skyllia.getInstance(), player,
                "<yellow>请输入活动区 <white>" + zone.name() + "</white> 的新内容半径（数字）：",
                value -> {
                    ActivityZone current = manager.getByName(zoneName).orElse(zone);
                    manager.updateRadii(zoneName, value, current.bufferRadius());
                    openEditor(player, zoneName);
                },
                () -> openEditor(player, zoneName)));

        // 缓冲半径
        inv.setItem(12, GuiItem.of(Material.BARRIER, "<!italic><light_purple>缓冲半径：" + fmt(zone.bufferRadius()) + " 格",
                List.of("<dark_gray>─────────",
                        "<gray>只用于阻止新岛屿在附近生成</gray>",
                        "<gray>不会作为可见边境展示给玩家</gray>",
                        "<gray>点击输入新的数值</gray>")));
        holder.bind(12, e -> GuiTextInput.promptNumber(Skyllia.getInstance(), player,
                "<yellow>请输入活动区 <white>" + zone.name() + "</white> 的新缓冲半径（数字）：",
                value -> {
                    ActivityZone current = manager.getByName(zoneName).orElse(zone);
                    manager.updateRadii(zoneName, current.contentRadius(), value);
                    openEditor(player, zoneName);
                },
                () -> openEditor(player, zoneName)));

        // 四个开关标记：破坏 / 放置 / PVP / 生物攻击
        inv.setItem(19, buildFlagItem(zone.allowBreak(), "破坏"));
        holder.bind(19, e -> toggleFlag(player, zoneName, e.isRightClick(), 0));

        inv.setItem(21, buildFlagItem(zone.allowPlace(), "放置"));
        holder.bind(21, e -> toggleFlag(player, zoneName, e.isRightClick(), 1));

        inv.setItem(23, buildFlagItem(zone.allowPvp(), "PVP"));
        holder.bind(23, e -> toggleFlag(player, zoneName, e.isRightClick(), 2));

        inv.setItem(25, buildFlagItem(zone.allowMobAttack(), "生物攻击"));
        holder.bind(25, e -> toggleFlag(player, zoneName, e.isRightClick(), 3));

        // 删除（二次确认）
        inv.setItem(31, GuiItem.danger(Material.TNT, "删除该活动区",
                List.of("<dark_gray>─────────", "<gray>需要二次确认</gray>")));
        holder.bind(31, e -> ConfirmGui.open(player, "删除活动区 " + zone.name(),
                List.of("<gray>删除后该活动区的独立边境、", "<gray>玩家阻挡设置将全部失效。</gray>"),
                () -> {
                    boolean ok = manager.deleteZone(zoneName);
                    if (ok) {
                        player.sendMessage(Component.text("§a✔ 活动区 " + zoneName + " 已删除。"));
                    } else {
                        player.sendMessage(Component.text("§c✘ 删除失败，请查看控制台日志。"));
                    }
                    openList(player, 0);
                },
                () -> openEditor(player, zoneName)));

        inv.setItem(36, GuiItem.back());
        holder.bind(36, e -> openList(player, 0));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  标记切换
    // ═══════════════════════════════════════════════════════════

    /**
     * @param flagIndex 0=破坏 1=放置 2=PVP 3=生物攻击 —— 对应 {@code updateFlags} 的四个参数位置
     */
    private static void toggleFlag(@NotNull Player player, @NotNull String zoneName, boolean rightClick, int flagIndex) {
        ActivityZoneManager manager = Skyllia.getInstance().getInterneAPI().getActivityZoneManager();
        Optional<ActivityZone> optZone = manager.getByName(zoneName);
        if (optZone.isEmpty()) {
            player.sendMessage(Component.text("§c该活动区不存在（可能已被删除）。"));
            openList(player, 0);
            return;
        }
        ActivityZone zone = optZone.get();
        boolean newValue = !rightClick; // 左键开启，右键关闭

        boolean allowBreak = flagIndex == 0 ? newValue : zone.allowBreak();
        boolean allowPlace = flagIndex == 1 ? newValue : zone.allowPlace();
        boolean allowPvp = flagIndex == 2 ? newValue : zone.allowPvp();
        boolean allowMobAttack = flagIndex == 3 ? newValue : zone.allowMobAttack();

        manager.updateFlags(zoneName, allowBreak, allowPlace, allowPvp, allowMobAttack);
        openEditor(player, zoneName);
    }

    private static @NotNull ItemStack buildFlagItem(boolean enabled, @NotNull String label) {
        List<String> lore = List.of(
                "<dark_gray>─────────",
                "<gray>当前状态：</gray>" + (enabled ? "<green>✔ 已开启</green>" : "<red>✘ 已关闭</red>"),
                "<dark_gray>─────────",
                "<yellow>左键</yellow> <green>开启</green>",
                "<yellow>右键</yellow> <red>关闭</red>");
        return enabled ? GuiItem.enabled(label, lore) : GuiItem.disabled(label, lore);
    }

    // ═══════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════

    private static @NotNull String onOff(boolean value) {
        return value ? "<green>✔</green>" : "<red>✘</red>";
    }

    private static @NotNull String fmt(double value) {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
