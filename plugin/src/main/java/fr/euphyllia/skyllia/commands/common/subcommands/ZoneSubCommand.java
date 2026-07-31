package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.zone.ActivityZone;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.managers.zone.ActivityZoneManager;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.WorldUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /is zone visit <name>} —— 传送到指定活动区中心，并下发该活动区的独立边境。
 * <p>
 * 目前 zone 只有一个子动作（visit），沿用本次会话其它多动作 SubCommandInterface
 * （如 addons ChallengeCommand 的 admin gui 分支）在同一个类里按 args[0] 分发的写法，
 * 便于未来新增 zone 子动作时不用改注册表。
 * </p>
 */
public class ZoneSubCommand implements SubCommandInterface {

    private final Logger logger = LogManager.getLogger(ZoneSubCommand.class);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("visit")) {
            player.sendMessage(MM.deserialize("<gray>用法：/is zone visit <name></gray>"));
            return;
        }

        String zoneName = args[1];
        try {
            ActivityZoneManager manager = Skyllia.getInstance().getInterneAPI().getActivityZoneManager();
            Optional<ActivityZone> optZone = manager.getByName(zoneName);
            if (optZone.isEmpty()) {
                player.sendMessage(MM.deserialize("<red>活动区 </red><white>" + zoneName + "</white><red> 不存在。</red>"));
                return;
            }
            ActivityZone zone = optZone.get();

            World world = WorldUtils.getWorldConfigs().getFirst().getWorld();
            if (world == null) {
                player.sendMessage(MM.deserialize("<red>目标世界尚未加载，请稍后再试。</red>"));
                return;
            }

            Location center = new Location(world, zone.centerX() + 0.5, player.getLocation().getY(), zone.centerZ() + 0.5);

            player.teleportAsync(center, PlayerTeleportEvent.TeleportCause.PLUGIN).thenRun(() -> {
                player.sendMessage(MM.deserialize("<green>✔ 已传送到活动区 </green><white>" + zoneName + "</white>"));
                PlayerUtils.applyBorder(player, center, zone.contentRadius());
            });
        } catch (Exception e) {
            logger.log(Level.FATAL, e.getMessage(), e);
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase(Locale.ROOT);
            return List.of("visit").stream().filter(s -> s.startsWith(partial)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("visit")) {
            String partial = args[1].trim().toLowerCase(Locale.ROOT);
            ActivityZoneManager manager = Skyllia.getInstance().getInterneAPI().getActivityZoneManager();
            return manager.getAll().stream()
                    .map(ActivityZone::name)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .toList();
        }
        return Collections.emptyList();
    }
}
