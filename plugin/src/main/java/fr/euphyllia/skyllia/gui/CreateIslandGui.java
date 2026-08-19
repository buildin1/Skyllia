package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.configuration.manager.IslandTypeGuiConfigManager.IslandTypeEntry;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
 * 建岛选择菜单：列出所有可选的岛屿类型，点击后执行 {@code /is create <id>}。
 *
 * <p>在此之前，主菜单的建岛入口只会派发一个不带参数的 {@code is create}，玩家永远只能拿到
 * 配置里的第一个类型 —— 多岛屿类型形同虚设。</p>
 *
 * <p>外观（图标 / 名称 / 描述 / 排序）全部来自 {@code config/island-types.toml}，服主可直接改，
 * 改完 {@code /isadmin reload} 即生效。</p>
 */
public final class CreateIslandGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** 每页可放置的条目数（第 1~4 行去掉左右边框）。 */
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int SLOT_PREV = 48;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_NEXT = 50;

    private CreateIslandGui() {}

    public static void open(@NotNull Player player) {
        open(player, 0);
    }

    public static void open(@NotNull Player player, int page) {
        List<IslandTypeEntry> entries = ConfigLoader.islandTypeGui == null
                ? List.of()
                : ConfigLoader.islandTypeGui.getEntries();

        // 一个类型都没有：不要弹一个空菜单让玩家一头雾水，直接走原来的无参建岛，
        // 由命令侧给出「没有可用的岛屿模板」的提示。
        if (entries.isEmpty()) {
            dispatch(player, "is create");
            return;
        }

        // 只有一个类型时，选择菜单没有意义，直接创建。
        if (entries.size() == 1) {
            dispatch(player, "is create " + entries.getFirst().id());
            return;
        }

        int perPage = CONTENT_SLOTS.length;
        int maxPage = (entries.size() - 1) / perPage;
        int current = Math.max(0, Math.min(page, maxPage));

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.CREATE_ISLAND);
        Inventory inv = Bukkit.createInventory(holder, 54,
                MM.deserialize("<!italic><dark_purple>选择岛屿类型 <gray>(" + (current + 1) + "/" + (maxPage + 1) + ")"));

        for (int slot = 0; slot < 54; slot++) {
            inv.setItem(slot, GuiItem.filler());
        }

        int start = current * perPage;
        int end = Math.min(start + perPage, entries.size());
        for (int i = start; i < end; i++) {
            IslandTypeEntry entry = entries.get(i);
            int slot = CONTENT_SLOTS[i - start];

            // 与 CreateSubCommand 同一条权限节点。这里提前判定，是为了让没权限的类型
            // 在菜单里就显示成灰色不可选，而不是玩家点下去才收到一条报错。
            boolean allowed = PlayerUtils.hasPermission(
                    player, "skyllia.island.command.create.%s".formatted(entry.id()));

            List<String> lore = new ArrayList<>(entry.lore());
            lore.add("<dark_gray>─────────");
            lore.add("<dark_gray>类型 id：" + entry.id());

            if (allowed) {
                inv.setItem(slot, GuiItem.of(entry.icon(), entry.displayName(), lore));
                holder.bind(slot, e -> dispatch(player, "is create " + entry.id()));
            } else {
                lore.add("<red>✘ 你没有创建该类型的权限");
                inv.setItem(slot, GuiItem.of(Material.GRAY_STAINED_GLASS_PANE,
                        "<!italic><dark_gray>" + entry.id(), lore));
                // 刻意不绑定回调：点击无反应即表示不可选。
            }
        }

        if (current > 0) {
            inv.setItem(SLOT_PREV, GuiItem.prevPage());
            holder.bind(SLOT_PREV, e -> open(player, current - 1));
        }
        if (current < maxPage) {
            inv.setItem(SLOT_NEXT, GuiItem.nextPage());
            holder.bind(SLOT_NEXT, e -> open(player, current + 1));
        }

        inv.setItem(SLOT_CLOSE, GuiItem.close());
        holder.bind(SLOT_CLOSE, e -> player.closeInventory());

        player.openInventory(inv);
    }

    /**
     * 关闭菜单并以玩家身份执行命令。必须在玩家自己的 region 线程上执行（Folia）。
     */
    private static void dispatch(@NotNull Player player, @NotNull String command) {
        Consumer<ScheduledTask> task = t -> {
            player.closeInventory();
            player.performCommand(command);
        };
        player.getScheduler().run(Skyllia.getInstance(), task, null);
    }
}
