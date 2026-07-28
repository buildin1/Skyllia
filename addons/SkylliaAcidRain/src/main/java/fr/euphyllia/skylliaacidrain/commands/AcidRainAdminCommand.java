package fr.euphyllia.skylliaacidrain.commands;

import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skylliaacidrain.season.AcidSeasonManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code /skylliadmin acidrain <forcestart|forceend> [world]} — 测试用命令：
 * 强制立即开始/结束指定世界（默认为执行者所在世界）的酸雨季，跳过月相周期判断。
 */
public class AcidRainAdminCommand implements SubCommandInterface {

    public static final String PERMISSION = "skyllia.admins.commands.acidrain";

    private final AcidSeasonManager acidSeasonManager;

    public AcidRainAdminCommand(AcidSeasonManager acidSeasonManager) {
        this.acidSeasonManager = acidSeasonManager;
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, permission())) {
            ConfigLoader.language.sendMessage(sender, "island.player.permission-denied");
            return;
        }

        if (args.length == 0 || (!args[0].equalsIgnoreCase("forcestart") && !args[0].equalsIgnoreCase("forceend"))) {
            sender.sendMessage(Component.text("§c用法：/skylliadmin acidrain <forcestart|forceend> [世界名]"));
            return;
        }

        boolean start = args[0].equalsIgnoreCase("forcestart");
        World world = resolveWorld(sender, args);
        if (world == null) {
            sender.sendMessage(Component.text("§c未找到目标世界，请以玩家身份执行或在参数中指定世界名。"));
            return;
        }

        // 状态变更、事件触发均在酸雨季管理器内部完成的调度里执行，这里只需切到 global region 线程发起调用
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            if (start) {
                acidSeasonManager.forceStartSeason(world);
                sender.sendMessage(Component.text("§a[SkylliaAcidRain] 已强制在世界 " + world.getName() + " 开启酸雨季。"));
            } else {
                boolean ended = acidSeasonManager.forceEndSeason(world);
                if (ended) {
                    sender.sendMessage(Component.text("§a[SkylliaAcidRain] 已强制结束世界 " + world.getName() + " 的酸雨季。"));
                } else {
                    sender.sendMessage(Component.text("§e[SkylliaAcidRain] 世界 " + world.getName() + " 当前并未处于酸雨季。"));
                }
            }
        });
    }

    private World resolveWorld(CommandSender sender, String[] args) {
        if (args.length > 1) {
            return Bukkit.getWorld(args[1]);
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        return null;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("forcestart", "forceend");
        }
        if (args.length == 2) {
            List<String> worldNames = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worldNames.add(world.getName());
            }
            List<String> matches = new ArrayList<>();
            StringUtil.copyPartialMatches(args[1], worldNames, matches);
            return matches;
        }
        return Collections.emptyList();
    }

    @Override
    public String permission() {
        return PERMISSION;
    }
}
