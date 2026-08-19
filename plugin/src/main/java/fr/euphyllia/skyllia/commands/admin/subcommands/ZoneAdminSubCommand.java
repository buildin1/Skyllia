package fr.euphyllia.skyllia.commands.admin.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.zone.ActivityZone;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.gui.zone.ZoneAdminGui;
import fr.euphyllia.skyllia.managers.zone.ActivityZoneManager;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /skylliadmin zone <pos1|pos2|create|delete|gui>} —— 活动区（商店/PVP 场地等）管理。
 * <p>
 * {@code pos1}/{@code pos2} 是纯内存的临时选点工具（不持久化），用于 {@code create}
 * 的「画框」入口；也支持不选点直接 {@code create <name> <content-radius> [buffer-add]}
 * 站在中心点上直接给半径的入口。两种入口的消歧规则见 {@link #handleCreate}。
 * </p>
 */
public class ZoneAdminSubCommand implements SubCommandInterface {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final double DEFAULT_BUFFER_ADD = 50.0;

    /** 每个管理员的临时选点：pos1/pos2，仅存 [blockX, blockZ]，不跨重启保留。 */
    private static final Map<UUID, int[]> POS1 = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]> POS2 = new ConcurrentHashMap<>();

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, permission())) {
            ConfigLoader.language.sendMessage(sender, "island.player.permission-denied");
            return;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        String action = args[0].trim().toLowerCase(Locale.ROOT);
        String[] rest = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];

        switch (action) {
            case "pos1" -> handlePos(sender, rest, POS1, "pos1");
            case "pos2" -> handlePos(sender, rest, POS2, "pos2");
            case "create" -> handleCreate(sender, rest);
            case "delete" -> handleDelete(sender, rest);
            case "gui" -> handleGui(sender);
            default -> sendUsage(sender);
        }
    }

    private void sendUsage(@NotNull CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gray>用法：</gray>"));
        sender.sendMessage(MM.deserialize("<gray> - /skylliadmin zone pos1</gray>"));
        sender.sendMessage(MM.deserialize("<gray> - /skylliadmin zone pos2</gray>"));
        sender.sendMessage(MM.deserialize("<gray> - /skylliadmin zone create <name> [buffer-add]</gray>"));
        sender.sendMessage(MM.deserialize("<gray> - /skylliadmin zone create <name> <content-radius> [buffer-add]</gray>"));
        sender.sendMessage(MM.deserialize("<gray> - /skylliadmin zone delete <name></gray>"));
        sender.sendMessage(MM.deserialize("<gray> - /skylliadmin zone gui</gray>"));
    }

    // ═══════════════════════════════════════════════════════════
    //  pos1 / pos2
    // ═══════════════════════════════════════════════════════════

    private void handlePos(@NotNull CommandSender sender, @NotNull String[] rest,
                            @NotNull Map<UUID, int[]> store, @NotNull String label) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        Location loc = player.getLocation();
        store.put(player.getUniqueId(), new int[]{loc.getBlockX(), loc.getBlockZ()});
        player.sendMessage(MM.deserialize("<green>✔ 已标记 " + label + "：</green><white>x=" + loc.getBlockX()
                + ", z=" + loc.getBlockZ() + "</white>"));
    }

    // ═══════════════════════════════════════════════════════════
    //  create
    // ═══════════════════════════════════════════════════════════

    private void handleCreate(@NotNull CommandSender sender, @NotNull String[] rest) {
        if (rest.length < 1) {
            sender.sendMessage(MM.deserialize("<red>用法：/skylliadmin zone create <name> [buffer-add]</red>"));
            sender.sendMessage(MM.deserialize("<red>或者：/skylliadmin zone create <name> <content-radius> [buffer-add]</red>"));
            return;
        }
        String name = rest[0];

        // 消歧：create 后第二个参数（rest[1]）能解析成数字 → 直接给半径的入口；否则走画框（pos1/pos2）入口。
        if (rest.length >= 2 && isNumeric(rest[1])) {
            handleCreateDirectRadius(sender, name, rest);
        } else {
            handleCreateFromCorners(sender, name, rest);
        }
    }

    private void handleCreateDirectRadius(@NotNull CommandSender sender, @NotNull String name, @NotNull String[] rest) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        double contentRadius;
        double bufferAdd = DEFAULT_BUFFER_ADD;
        try {
            contentRadius = Double.parseDouble(rest[1]);
            if (rest.length >= 3) {
                bufferAdd = Double.parseDouble(rest[2]);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(MM.deserialize("<red>半径 / 缓冲带数值无效。</red>"));
            return;
        }
        Location loc = player.getLocation();
        createZone(sender, name, loc.getBlockX(), loc.getBlockZ(), contentRadius, contentRadius + bufferAdd, player.getUniqueId());
    }

    private void handleCreateFromCorners(@NotNull CommandSender sender, @NotNull String name, @NotNull String[] rest) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        UUID id = player.getUniqueId();
        int[] pos1 = POS1.get(id);
        int[] pos2 = POS2.get(id);
        if (pos1 == null || pos2 == null) {
            sender.sendMessage(MM.deserialize("<red>请先用 /skylliadmin zone pos1 和 pos2 标记两个角点，"
                    + "或者使用 /skylliadmin zone create <name> <content-radius> [buffer-add] 直接给半径。</red>"));
            return;
        }

        double bufferAdd = DEFAULT_BUFFER_ADD;
        if (rest.length >= 2) {
            try {
                bufferAdd = Double.parseDouble(rest[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(MM.deserialize("<red>缓冲带数值无效。</red>"));
                return;
            }
        }

        int centerX = (pos1[0] + pos2[0]) / 2;
        int centerZ = (pos1[1] + pos2[1]) / 2;
        double contentRadius = Math.max(Math.abs(pos2[0] - pos1[0]), Math.abs(pos2[1] - pos1[1])) / 2.0;
        double bufferRadius = contentRadius + bufferAdd;

        boolean created = createZone(sender, name, centerX, centerZ, contentRadius, bufferRadius, id);
        if (created) {
            POS1.remove(id);
            POS2.remove(id);
        }
    }

    private boolean createZone(@NotNull CommandSender sender, @NotNull String name,
                                int centerX, int centerZ, double contentRadius, double bufferRadius,
                                @NotNull UUID createdBy) {
        ActivityZoneManager manager = Skyllia.getInstance().getInterneAPI().getActivityZoneManager();
        boolean ok = manager.createZone(name, centerX, centerZ, contentRadius, bufferRadius, createdBy);
        if (ok) {
            sender.sendMessage(MM.deserialize("<green>✔ 活动区 </green><white>" + name + "</white>"
                    + "<green> 已创建。中心 (" + centerX + ", " + centerZ + ")，内容半径 " + fmt(contentRadius)
                    + "，缓冲半径 " + fmt(bufferRadius) + "。</green>"));
        } else {
            sender.sendMessage(MM.deserialize("<red>✘ 创建失败：名称 </red><white>" + name + "</white><red> 已被占用。</red>"));
        }
        return ok;
    }

    // ═══════════════════════════════════════════════════════════
    //  delete
    // ═══════════════════════════════════════════════════════════

    private void handleDelete(@NotNull CommandSender sender, @NotNull String[] rest) {
        if (rest.length < 1) {
            sender.sendMessage(MM.deserialize("<red>用法：/skylliadmin zone delete <name></red>"));
            return;
        }
        String name = rest[0];
        ActivityZoneManager manager = Skyllia.getInstance().getInterneAPI().getActivityZoneManager();
        Optional<ActivityZone> zone = manager.getByName(name);
        if (zone.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>活动区 </red><white>" + name + "</white><red> 不存在。</red>"));
            return;
        }
        boolean ok = manager.deleteZone(name);
        if (ok) {
            sender.sendMessage(MM.deserialize("<green>✔ 活动区 </green><white>" + name + "</white><green> 已删除。</green>"));
        } else {
            sender.sendMessage(MM.deserialize("<red>✘ 删除失败，请查看控制台日志。</red>"));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  gui
    // ═══════════════════════════════════════════════════════════

    private void handleGui(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        player.getScheduler().run(Skyllia.getInstance(), t -> ZoneAdminGui.openList(player, 0), null);
    }

    // ═══════════════════════════════════════════════════════════
    //  helpers
    // ═══════════════════════════════════════════════════════════

    private static boolean isNumeric(@NotNull String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String fmt(double value) {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, permission())) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase(Locale.ROOT);
            return List.of("pos1", "pos2", "create", "delete", "gui").stream()
                    .filter(s -> s.startsWith(partial)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            String partial = args[1].trim().toLowerCase(Locale.ROOT);
            ActivityZoneManager manager = Skyllia.getInstance().getInterneAPI().getActivityZoneManager();
            return manager.getAll().stream()
                    .map(ActivityZone::name)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .toList();
        }
        return Collections.emptyList();
    }

    @Override
    public @NotNull String permission() {
        return "skyllia.admins.commands.zone";
    }
}
