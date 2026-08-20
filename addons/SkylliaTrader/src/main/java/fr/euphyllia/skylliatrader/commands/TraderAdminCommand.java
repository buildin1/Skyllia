package fr.euphyllia.skylliatrader.commands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.CredentialItemSpec;
import fr.euphyllia.skylliatrader.configuration.model.GuidebookConfig;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import fr.euphyllia.skylliatrader.credential.CredentialItems;
import fr.euphyllia.skylliatrader.data.MerchantRecord;
import fr.euphyllia.skylliatrader.gui.admin.TraderAdminMainGui;
import fr.euphyllia.skylliatrader.merchant.CaravanType;
import fr.euphyllia.skylliatrader.merchant.MerchantService;
import fr.euphyllia.skylliatrader.shop.GuidebookItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 管理端命令树：{@code /skylliadmin trader ...}。
 * 通过 {@code SkylliaAPI.registerAdminCommands(new TraderAdminCommand(), "trader")} 挂进
 * Skyllia 核心的 {@code /skylliadmin} 子命令注册表（见 {@code SkylliaAdminCommand}），
 * 不需要自己开一个新的顶层命令。
 * <p>
 * T1 做了两件事：{@code gui} 打开统一管理 GUI 入口，{@code tiers} 打印四轨当前档位配置。
 * T2 追加了游商相关的四条：{@code merchants} 查某玩家岛上的常驻商人、{@code release} 强制释放
 * 一种商队的名额、{@code credential} 拿一张凭证样品、{@code guidebook} 拿一本说明书样品。
 * 订单的具体增删改仍在 {@link TraderAdminMainGui} 里留着占位，由 T3-T4 填。
 * </p>
 *
 * <h2>线程模型</h2>
 * <p>
 * {@code SkylliaAdminCommand} 把所有子命令丢进 {@code Bukkit.getAsyncScheduler()}，
 * 所以 {@link #onExecute} <b>本身就在 async 线程上</b>——查岛屿、读游商数据这些阻塞调用
 * 可以直接写，不需要再包一层调度。反过来，任何要碰玩家背包/界面的动作
 * （{@code credential} / {@code guidebook} 发物品）都必须跳回玩家线程。
 * </p>
 */
public class TraderAdminCommand implements SubCommandInterface {

    private static final Logger log = LoggerFactory.getLogger(TraderAdminCommand.class);

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(permission())) {
            sender.sendMessage(Component.text("§c你没有权限使用这个命令。"));
            return;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "gui" -> handleGui(sender);
            case "tiers" -> handleTiers(sender);
            case "merchants" -> handleMerchants(sender, args);
            case "release" -> handleRelease(sender, args);
            case "credential" -> handleCredential(sender, args);
            case "guidebook" -> handleGuidebook(sender);
            default -> sendUsage(sender);
        }
    }

    /** {@code /skylliadmin trader merchants <玩家>}：列出该玩家所在岛屿登记的常驻商人。 */
    private void handleMerchants(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("§c用法：/skylliadmin trader merchants <玩家>"));
            return;
        }
        Island island = resolveIsland(sender, args[1]);
        if (island == null) return;

        SkylliaTrader plugin = SkylliaTrader.getInstance();
        List<MerchantRecord> records = plugin.getMerchantService().listMerchants(island);
        sender.sendMessage(Component.text("§d═══ 岛屿 " + island.getId() + " 的常驻商人 ═══"));
        if (records.isEmpty()) {
            sender.sendMessage(Component.text("§7（没有任何登记记录）"));
            return;
        }
        long now = System.currentTimeMillis();
        for (MerchantRecord record : records) {
            String caravanName = record.caravanType() != null
                    ? record.caravanType().defaultDisplayName() : "§c未知商队(" + record.caravan + ")";
            String state = record.isClaim()
                    ? (record.occupiesSlot(now) ? "§e占位中" : "§8占位已超时（可被顶替）")
                    : "§a已就位 " + record.entityUuid;
            sender.sendMessage(Component.text("§7· §f" + caravanName + " §7- " + state));
            sender.sendMessage(Component.text("§8    位置：" + record.world + " "
                    + Math.round(record.x) + ", " + Math.round(record.y) + ", " + Math.round(record.z)));
            // summonerUuid 存在的理由就是「管理端展示与审计」，不打出来的话它就是一个
            // 只写不读的死字段，下一个人看到会直接把它删掉。
            sender.sendMessage(Component.text("§8    召唤者：" + describeSummoner(record.summonerUuid)));
        }
    }

    /**
     * 把 {@code summonerUuid} 变成一句人话。
     * <p>
     * 用 {@code Bukkit.getOfflinePlayer(UUID)} 而不是 {@code getOfflinePlayer(String)}：
     * 前者只查本地 usercache，不会发起阻塞的 Mojang 请求；查不到名字就退回显示 UUID，
     * 反正管理员真正需要的是「能不能对上人」，UUID 也能对。
     * </p>
     */
    private String describeSummoner(String summonerUuid) {
        if (summonerUuid == null || summonerUuid.isBlank()) return "§7（未记录）";
        try {
            UUID id = UUID.fromString(summonerUuid);
            String name = Bukkit.getOfflinePlayer(id).getName();
            return name != null ? name + " §8(" + summonerUuid + ")" : summonerUuid;
        } catch (IllegalArgumentException e) {
            return "§c无法解析(" + summonerUuid + ")";
        }
    }

    /**
     * {@code /skylliadmin trader release <玩家> <商队>}：强制释放一种商队的名额。
     * <p>
     * <b>只动数据，不动实体</b>——实体可能在没加载的区块里，跨区域去删是 Folia 上的硬错误。
     * 那只商人会在下一次区块加载时被孤儿检查清掉（见 {@code MerchantLifecycleListener}）。
     * </p>
     * <p>
     * 顺带会清掉这座岛的重召冷却：管理员执行 release 的典型场景就是「商人刚死、记录已经自动
     * 删掉了、玩家却召不出来」——那时挡着玩家的其实是最长 5 分钟的重召冷却，不是名额。
     * </p>
     */
    private void handleRelease(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("§c用法：/skylliadmin trader release <玩家> <overworld|nether|end>"));
            return;
        }
        Island island = resolveIsland(sender, args[1]);
        if (island == null) return;

        CaravanType caravan = CaravanType.parseOrNull(args[2]);
        if (caravan == null) {
            sender.sendMessage(Component.text("§c未知的商队类型：" + args[2] + "（可选 overworld / nether / end）"));
            return;
        }

        MerchantService.ReleaseResult result =
                SkylliaTrader.getInstance().getMerchantService().releaseSlot(island, caravan);

        // ⚠️ 必须先看 saved：写库失败时内存里那份改动等于没发生，名额其实还占着，
        // 而旧实现只看「内存里删了几条」，会照样回一句「已释放 N 条」，
        // 管理员以为处理完了，玩家下次召唤照样失败。
        if (!result.saved()) {
            sender.sendMessage(Component.text("§c释放失败：数据写入出错，名额仍然占用着。"
                    + "请查看控制台日志（数据库连接/磁盘），修好之后重试本命令。"));
            return;
        }

        if (result.removed() > 0) {
            sender.sendMessage(Component.text("§a已释放 " + result.removed() + " 条 "
                    + caravan.defaultDisplayName() + " 记录；如果那只商人还活着，"
                    + "它会在下次区块加载时被自动清理。"));
        } else {
            sender.sendMessage(Component.text("§e该岛没有 " + caravan.defaultDisplayName() + " 的登记记录。"));
        }
        if (result.cooldownCleared()) {
            sender.sendMessage(Component.text("§a已同时清除该岛的重召冷却，成员现在可以立即用凭证召唤。"));
        }
    }

    /**
     * {@code /skylliadmin trader credential <商队> [数量]}：给自己发一张凭证样品。
     * <p>
     * <b>这不是发放渠道</b>，凭证的正式来源是挑战任务奖励（见 {@code CredentialItems} 类注释）。
     * 这条命令是给服主对模型、试右键用的。
     * </p>
     */
    private void handleCredential(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§c该子命令只能由玩家执行（要把物品发到背包里）。"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("§c用法：/skylliadmin trader credential <overworld|nether|end> [数量]"));
            return;
        }
        CaravanType caravan = CaravanType.parseOrNull(args[1]);
        if (caravan == null) {
            sender.sendMessage(Component.text("§c未知的商队类型：" + args[1] + "（可选 overworld / nether / end）"));
            return;
        }
        int amount = args.length >= 3 ? parseAmount(args[2]) : 1;

        CredentialItemSpec spec = TraderConfigLoader.config.getCredentialItem(caravan);
        ItemStack item = spec == null ? null : CredentialItems.build(caravan, spec, amount);
        if (item == null) {
            sender.sendMessage(Component.text("§c这种凭证当前被停用了（custom-model-data 为负数，"
                    + "通常是配置里和别的商队撞了 CMD 被自动停用）。请检查控制台日志。"));
            return;
        }
        giveItem(player, item, "凭证样品");
    }

    /** {@code /skylliadmin trader guidebook}：给自己发一本说明书样品，用来校对文案排版。 */
    private void handleGuidebook(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§c该子命令只能由玩家执行（要把物品发到背包里）。"));
            return;
        }
        GuidebookConfig config = TraderConfigLoader.config.getGuidebook();
        if (config == null) {
            sender.sendMessage(Component.text("§c说明书配置尚未加载。"));
            return;
        }
        if (!config.enabled()) {
            sender.sendMessage(Component.text("§e说明书当前是关闭状态（guidebook.enabled = false），"
                    + "这里仍然按配置给你一本样品供校对。"));
        }
        giveItem(player, GuidebookItem.build(config), "说明书样品");
    }

    /**
     * 把物品发到玩家背包，装不下就丢在脚下。<b>必须跳回玩家线程</b>——
     * 本方法的调用链起点是 async scheduler，直接改背包会踩线程模型。
     */
    private void giveItem(Player player, ItemStack item, String what) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        player.getScheduler().run(plugin, t -> {
            player.getInventory().addItem(item).forEach((slot, leftover) ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            player.sendMessage(Component.text("§a已发放" + what + "。"));
        }, null);
    }

    private int parseAmount(String raw) {
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 按玩家名找岛屿。找不到时把原因说清楚（没这个人 / 这个人没有岛），
     * 而不是笼统一句「失败」——管理命令最烦的就是不知道自己哪里写错了。
     */
    private Island resolveIsland(CommandSender sender, String playerName) {
        try {
            Player online = Bukkit.getPlayerExact(playerName);
            UUID playerId;
            if (online != null) {
                playerId = online.getUniqueId();
            } else {
                @SuppressWarnings("deprecation")
                OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
                if (!offline.hasPlayedBefore()) {
                    sender.sendMessage(Component.text("§c找不到玩家：" + playerName));
                    return null;
                }
                playerId = offline.getUniqueId();
            }
            Island island = SkylliaAPI.getIslandByPlayerId(playerId);
            if (island == null) {
                sender.sendMessage(Component.text("§c玩家 " + playerName + " 没有空岛。"));
            }
            return island;
        } catch (Exception e) {
            log.error("查询玩家 {} 的岛屿失败", playerName, e);
            sender.sendMessage(Component.text("§c查询岛屿失败，请查看控制台日志。"));
            return null;
        }
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§c该子命令只能由玩家执行（需要打开 GUI）。"));
            return;
        }
        TraderAdminMainGui.open(player);
    }

    private void handleTiers(CommandSender sender) {
        TrackTiers tiers = TraderConfigLoader.config.getTrackTiers();
        sender.sendMessage(Component.text("§d═══ SkylliaTrader 四轨当前档位配置 ═══"));
        sender.sendMessage(Component.text("§7交易次数：§f" + tiers.tradeCount()));
        sender.sendMessage(Component.text("§7岛屿等级：§f" + tiers.islandLevel()));
        sender.sendMessage(Component.text("§7商会声望：§f" + tiers.reputation()));
        sender.sendMessage(Component.text("§7累计消费（只给折扣/限购加成）：§f" + tiers.spending()));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("§d用法：§f/skylliadmin trader <gui|tiers|merchants|release|credential|guidebook>"));
        sender.sendMessage(Component.text("§7  gui                       打开游商管理 GUI"));
        sender.sendMessage(Component.text("§7  tiers                     查看四轨当前档位配置"));
        sender.sendMessage(Component.text("§7  merchants <玩家>          查看该岛登记的常驻商人"));
        sender.sendMessage(Component.text("§7  release <玩家> <商队>     强制释放一种商队的名额"));
        sender.sendMessage(Component.text("§7  credential <商队> [数量]  拿一张凭证样品（正式发放走挑战任务奖励）"));
        sender.sendMessage(Component.text("§7  guidebook                 拿一本说明书样品，用来校对文案"));
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return Stream.of("gui", "tiers", "merchants", "release", "credential", "guidebook")
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String partial = args[1].toLowerCase();
            if (sub.equals("merchants") || sub.equals("release")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partial))
                        .collect(Collectors.toList());
            }
            if (sub.equals("credential")) {
                return caravanNames(partial);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("release")) {
            return caravanNames(args[2].toLowerCase());
        }
        return List.of();
    }

    private List<String> caravanNames(String partial) {
        List<String> result = new ArrayList<>();
        for (CaravanType type : CaravanType.values()) {
            if (type.configKey().startsWith(partial)) result.add(type.configKey());
        }
        return result;
    }

    @Override
    public String permission() {
        return "skyllia.trader.admin";
    }
}
