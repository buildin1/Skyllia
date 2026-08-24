package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 向其他空岛提出加入申请的二级菜单：在线且有岛的玩家头像，点一下发出申请。
 */
public final class JoinRequestGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record Entry(UUID playerId, String name) {
    }

    private JoinRequestGui() {
    }

    public static void open(@NotNull Player player) {
        Skyllia plugin = Skyllia.getInstance();
        // 在线玩家列表必须在 region 线程拍快照；岛屿查询再丢到 async。
        List<Entry> online = new ArrayList<>();
        UUID self = player.getUniqueId();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(self)) continue;
            online.add(new Entry(other.getUniqueId(), other.getName()));
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                List<Entry> entries = filterOwners(player.getUniqueId(), online);
                player.getScheduler().run(plugin, t -> render(player, entries, 0), null);
            } catch (Throwable t) {
                player.sendMessage(net.kyori.adventure.text.Component.text("§c读取玩家列表失败，请稍后重试。"));
            }
        });
    }

    private static List<Entry> filterOwners(UUID self, List<Entry> online) {
        Island own = SkylliaAPI.getIslandByPlayerId(self);
        UUID ownIslandId = own != null ? own.getId() : null;

        List<Entry> entries = new ArrayList<>();
        for (Entry candidate : online) {
            Island island = SkylliaAPI.getIslandByPlayerId(candidate.playerId);
            if (island == null) continue;
            if (ownIslandId != null && ownIslandId.equals(island.getId())) continue;
            Players owner = island.getOwner();
            if (owner == null || !owner.getMojangId().equals(candidate.playerId)) continue;
            entries.add(candidate);
        }
        entries.sort(Comparator.comparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private static void render(Player player, List<Entry> entries, int page) {
        int totalPages = GuiPageLayout.totalPages(entries.size());
        int clamped = GuiPageLayout.clampPage(page, totalPages);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.JOIN_REQUEST);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize(
                "<light_purple>申请加入空岛" + (totalPages > 1 ? " <gray>(" + (clamped + 1) + "/" + totalPages + ")" : "")));

        GuiPageLayout.fillBorder(inv);
        inv.setItem(GuiPageLayout.SLOT_HEADER, GuiItem.of(Material.OAK_DOOR,
                "<!italic><light_purple>申请加入空岛",
                List.of("<dark_gray>─────────",
                        "<gray>点击一名岛主，向他的空岛提出加入申请</gray>",
                        "<gray>岛主不在线会在下次上线时收到提醒</gray>")));

        if (entries.isEmpty()) {
            inv.setItem(31, GuiItem.of(Material.BARRIER,
                    "<!italic><red>没有可申请的空岛",
                    List.of("<dark_gray>─────────",
                            "<gray>当前没有其他在线岛主</gray>")));
        } else {
            int from = clamped * GuiPageLayout.PAGE_SIZE;
            int to = Math.min(from + GuiPageLayout.PAGE_SIZE, entries.size());
            for (int i = from; i < to; i++) {
                Entry entry = entries.get(i);
                int slot = GuiPageLayout.contentSlot(i - from);
                inv.setItem(slot, GuiItem.playerHead(entry.playerId,
                        "<!italic><light_purple>" + entry.name,
                        List.of("<dark_gray>─────────",
                                "<gray>向 " + entry.name + " 的空岛提出加入申请</gray>",
                                "<dark_gray>─────────",
                                "<yellow>点击申请</yellow>")));
                holder.bind(slot, e -> {
                    player.closeInventory();
                    Bukkit.getAsyncScheduler().runNow(Skyllia.getInstance(), t ->
                            fr.euphyllia.skyllia.join.JoinRequestService.apply(player, entry.name));
                });
            }
        }

        if (clamped > 0) {
            inv.setItem(GuiPageLayout.SLOT_PREV_PAGE, GuiItem.prevPage());
            holder.bind(GuiPageLayout.SLOT_PREV_PAGE, e -> render(player, entries, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(GuiPageLayout.SLOT_NEXT_PAGE, GuiItem.nextPage());
            holder.bind(GuiPageLayout.SLOT_NEXT_PAGE, e -> render(player, entries, clamped + 1));
        }

        inv.setItem(GuiPageLayout.SLOT_CLOSE, GuiItem.back());
        holder.bind(GuiPageLayout.SLOT_CLOSE, e -> ExtensionGui.open(player, 0));

        player.openInventory(inv);
    }
}
