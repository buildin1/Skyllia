package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.api.utils.nms.BiomesImpl;
import fr.euphyllia.skyllia.cache.commands.CommandCacheExecution;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.WorldUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SetBiomeSubCommand implements SubCommandInterface {

    private final Logger logger = LogManager.getLogger(SetBiomeSubCommand.class);
    private final List<String> biomeNameList = Skyllia.getInstance().getInterneAPI().getBiomesImpl().getBiomeNameList();

    private final PermissionId ISLAND_SET_BIOME_PERMISSION;

    public SetBiomeSubCommand() {
        this.ISLAND_SET_BIOME_PERMISSION = SkylliaAPI.getPermissionRegistry().register(new PermissionNode(
                new NamespacedKey(Skyllia.getInstance(), "command.island.set_biome"),
                "island.permission.command.set_biome.name",
                "island.permission.command.set_biome.description"
        ));
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }

        if (args.length < 1) {
            ConfigLoader.language.sendMessage(player, "island.biome.args-missing");
            return;
        }

        String selectBiome = args[0];
        Biome biome;

        InterneAPI api = Skyllia.getInstance().getInterneAPI();
        BiomesImpl biomesImpl = api.getBiomesImpl();
        biome = api.getBiomesImpl().getBiome(selectBiome);
        if (biome == null) {
            ConfigLoader.language.sendMessage(player, "island.biome.not-exist", Map.of(
                    "%s", selectBiome));
            return;
        }

        String biomeName = biomesImpl.getNameBiome(biome);
        String biomeRaw = biomeName.split(":")[1];

        Location playerLocation = player.getLocation();
        World world = playerLocation.getWorld();

        if (world == null || !WorldUtils.isWorldSkyblock(world.getName())) {
            ConfigLoader.language.sendMessage(player, "island.biome.only-on-island");
            return;
        }

        // 主世界只能改主世界群系、下界只能改下界群系（2026-08 反馈：跨维度改群系会刷出
        // 对应维度的怪，一律不再允许）。GUI 图鉴那条路在 BiomeCatalogGui 里同样过滤了。
        if (!biomesImpl.getBiomeNameList(world.getEnvironment()).contains(biomeName)) {
            ConfigLoader.language.sendMessage(player, "island.biome.wrong-dimension", Map.of(
                    "%s", biomeName));
            return;
        }

        try {
            Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());

            if (island == null) {
                ConfigLoader.language.sendMessage(player, "island.player.no-island");
                return;
            }

            final UUID islandId = island.getId();

            if (CommandCacheExecution.isAlreadyExecute(islandId, "biome")) {
                ConfigLoader.language.sendMessage(player, "island.generic.command-in-progress");
                return;
            }

            CommandCacheExecution.addCommandExecute(islandId, "biome");

            boolean allowed = SkylliaAPI.getPermissionsManager().hasPermission(player, island, ISLAND_SET_BIOME_PERMISSION, null, ConfigLoader.general.getDebugSettings().permission());
            if (!allowed) {
                ConfigLoader.language.sendMessage(player, "island.player.permission-denied");
                CommandCacheExecution.removeCommandExec(islandId, "biome");
                return;
            }

            RegionCoordinate islandPosition = island.getRegionCoordinate();

            RegionCoordinate playerRegionPosition = RegionHelper.getRegionCoordinateFromLocation(playerLocation);

            if (islandPosition.x() != playerRegionPosition.x() || islandPosition.z() != playerRegionPosition.z()) {
                ConfigLoader.language.sendMessage(player, "island.player.not-on-own-island");
                CommandCacheExecution.removeCommandExec(islandId, "biome");
                return;
            }

            ConfigLoader.language.sendMessage(player, "island.biome.change-in-progress");

            CompletableFuture<Boolean> changeBiomeFuture;
            String messageToSend;

            if (args.length >= 2 && args[1].equalsIgnoreCase("island")) {

                changeBiomeFuture = Skyllia.getInstance().getInterneAPI().getWorldModifier().changeBiomeIsland(world, biome, island, ConfigLoader.general.getIslandSettings().regionDistance());
                messageToSend = "island.biome.island-success";

            } else {
                int chunkX = playerLocation.getBlockX() >> 4;
                int chunkZ = playerLocation.getBlockZ() >> 4;
                changeBiomeFuture = Skyllia.getInstance().getInterneAPI().getWorldModifier().changeBiomeChunk(world, chunkX, chunkZ, biome);
                messageToSend = "island.biome.chunk-success";
            }

            changeBiomeFuture.thenAccept(success -> {
                if (success) {
                    ConfigLoader.language.sendMessage(player, messageToSend);
                } else {
                    ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
                }
                CommandCacheExecution.removeCommandExec(islandId, "biome");
            }).exceptionally(ex -> {
                logger.log(Level.ERROR, ex.getMessage(), ex);
                ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
                CommandCacheExecution.removeCommandExec(islandId, "biome");
                return null;
            });
        } catch (Exception e) {
            logger.log(Level.ERROR, e.getMessage(), e);
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        } finally {
            CommandCacheExecution.removeCommandExec(player.getUniqueId(), "biome");
        }

    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();

            // 补全也按发送者所在世界的维度过滤，和执行时的校验保持一致
            List<String> candidates = (sender instanceof Player p)
                    ? Skyllia.getInstance().getInterneAPI().getBiomesImpl().getBiomeNameList(p.getWorld().getEnvironment())
                    : biomeNameList;

            return candidates.stream()
                    .filter(biome -> PlayerUtils.hasPermission(sender, "skyllia.island.command.biome.%s".formatted(biome)))
                    .filter(biome -> biome.toLowerCase().startsWith(partial))
                    .toList();
        }

        if (args.length == 2) {
            String partial = args[1].trim().toLowerCase();

            List<String> options = new ArrayList<>();
            options.add("chunk");
            if (PlayerUtils.hasPermission(sender, "skyllia.island.command.biome_island")) {
                options.add("island");
            }

            return options.stream()
                    .filter(opt -> opt.toLowerCase().startsWith(partial))
                    .toList();
        }

        return Collections.emptyList();
    }
}