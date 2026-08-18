package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.enums.RemovalCause;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.utils.RegionUtils;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.managers.skyblock.SkyblockManager;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.WorldUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DeleteSubCommand implements SubCommandInterface {

    private final Logger logger = LogManager.getLogger(DeleteSubCommand.class);

    public static void checkClearPlayer(SkyblockManager skyblockManager, Players players, RemovalCause cause) {
        Player bPlayer = Bukkit.getPlayer(players.getMojangId());
        if (bPlayer != null && bPlayer.isOnline()) {
            PlayerUtils.teleportPlayerSpawn(bPlayer);
            bPlayer.getScheduler().execute(Skyllia.getInstance(), () -> {
                switch (cause) {
                    case KICKED -> {
                        if (ConfigLoader.playerManager.isClearInventoryWhenKicked()) {
                            bPlayer.getInventory().clear();
                        }
                        if (ConfigLoader.playerManager.isClearEnderChestWhenKicked()) {
                            bPlayer.getEnderChest().clear();
                        }
                        if (ConfigLoader.playerManager.isResetExperienceWhenKicked()) {
                            bPlayer.setTotalExperience(0);
                            bPlayer.setExp(0);
                            bPlayer.setLevel(0);
                            bPlayer.sendExperienceChange(0, 0); // Mise à jour du packet
                        }
                    }
                    case ISLAND_DELETED -> {
                        if (ConfigLoader.playerManager.isClearInventoryWhenDelete()) {
                            bPlayer.getInventory().clear();
                        }
                        if (ConfigLoader.playerManager.isClearEnderChestWhenDelete()) {
                            bPlayer.getEnderChest().clear();
                        }
                        if (ConfigLoader.playerManager.isResetExperienceWhenDelete()) {
                            bPlayer.setTotalExperience(0);
                            bPlayer.setExp(0);
                            bPlayer.setLevel(0);
                            bPlayer.sendExperienceChange(0, 0); // Mise à jour du packet
                        }
                    }
                    case LEAVE -> {
                        if (ConfigLoader.playerManager.isClearInventoryWhenLeave()) {
                            bPlayer.getInventory().clear();
                        }
                        if (ConfigLoader.playerManager.isClearEnderChestWhenLeave()) {
                            bPlayer.getEnderChest().clear();
                        }
                        if (ConfigLoader.playerManager.isResetExperienceWhenLeave()) {
                            bPlayer.setTotalExperience(0);
                            bPlayer.setExp(0);
                            bPlayer.setLevel(0);
                            bPlayer.sendExperienceChange(0, 0);
                        }
                    }
                }
                bPlayer.setGameMode(GameMode.SURVIVAL);
            }, null, 1L);
        } else {
            skyblockManager.addClearMemberNextLogin(players.getMojangId(), cause);
        }
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        // 注意是 < 1 而不是 != 1：第二个参数（重建用的岛屿类型）是可选的，
        // 用 != 1 会把 /is delete confirm <类型> 一并挡掉。
        if (args.length < 1) {
            ConfigLoader.language.sendMessage(player, "island.delete.args-missing");
            return;
        }
        String confirm = args[0];
        if (!confirm.equalsIgnoreCase("confirm")) {
            ConfigLoader.language.sendMessage(player, "island.admin.delete-no-confirm");
            return;
        }
        // 可选的第二个参数：新岛屿类型。省略则使用 islands.toml 里的 default-schem-key。
        String requestedType = args.length >= 2 ? args[1] : null;

        try {
            SkyblockManager skyblockManager = Skyllia.getInstance().getInterneAPI().getSkyblockManager();
            Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
            if (island == null) {
                ConfigLoader.language.sendMessage(player, "island.player.no-island");
                return;
            }

            Players executorPlayer = island.getMember(player.getUniqueId());

            if (executorPlayer == null || !executorPlayer.getRoleType().equals(RoleType.OWNER)) {
                ConfigLoader.language.sendMessage(player, "island.delete-only-owner");
                return;
            }

            // Vérification des membres
            if (ConfigLoader.general.getIslandSettings().preventDeletionIfHasMembers()) {
                long memberCount = island.getMembers().stream()
                        .filter(member -> !member.getMojangId().equals(player.getUniqueId()))
                        .count();
                if (memberCount > 0) {
                    ConfigLoader.language.sendMessage(player, "island.player.delete-has-members");
                    return;
                }
            }

            // ── 动旧岛之前，先确认新岛一定建得出来 ──
            //
            // runCreateIsland() 内部会校验 skyllia.island.command.create.<类型> 权限，
            // 并在缺失时直接放弃创建。如果等到那时才失败，旧岛已经被 setDisable(true)
            // 禁用掉了，玩家就会落到"旧岛没了、新岛也没有"的状态——正是这次要修的问题。
            // 所以把类型解析和权限校验全部提前到这里，任何一项不通过就原样返回，不碰旧岛。
            String schemKey = resolveNewIslandType(requestedType);
            if (schemKey == null) {
                ConfigLoader.language.sendMessage(player, "island.schematic-not-exist");
                return;
            }
            if (!PlayerUtils.hasPermission(player, "skyllia.island.command.create.%s".formatted(schemKey))) {
                ConfigLoader.language.sendMessage(player, "island.player.permission-denied");
                return;
            }

            skyblockManager.setLockedIsland(island, true);

            boolean isDisabled = island.setDisable(true);
            if (!isDisabled) {
                // 事件被取消或写库失败：把锁解开，别让岛屿卡在 locked 状态
                skyblockManager.setLockedIsland(island, false);
                ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
                return;
            }

            // 把旧岛成员降级为访客。执行者本人不在这里清理背包 / 传送 spawn——
            // 他马上就要拿到新岛，见下方 runCreateIsland 的回调。
            this.demoteMembers(skyblockManager, island, player.getUniqueId());
            this.kickAllPlayerOnIsland(island);

            // ── 关键改动：先建新岛、把玩家送过去，旧岛留到后台再删 ──
            //
            // 此前这里是「删完就结束」：删除旧岛区块之后只发一条成功消息，玩家由
            // checkClearPlayer() 送去 PlayerUtils.teleportPlayerSpawn()。而那个全局出生点
            // 一旦配置指向不存在的世界（例如 world-name 写了个没有的名字），
            // getSpawnLocation() 会返回 null 并回退到 Bukkit.getWorlds().getFirst()，
            // 也就是主世界的出生点——如果主世界是平坦世界，玩家就落在平坦世界；
            // 如果坐标落在未生成的区域，就直接掉进虚空。玩家删完岛就"没有然后了"。
            //
            // 现在改成：建新岛 → 建好后按配置重置背包 → 传送由建岛流程负责 →
            // 最后才在后台异步删掉旧岛的区块。即使旧岛删除失败，玩家也已经在新岛上了。
            new CreateSubCommand().runCreateIsland(player, new String[]{schemKey})
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            logger.log(Level.ERROR, "删除后重建岛屿失败，玩家 {}：{}",
                                    player.getName(), throwable.getMessage(), throwable);
                        }
                        // 无论新岛是否成功，都要按配置重置数据并把旧岛区块清掉，
                        // 否则旧岛会永远占着那块 region。
                        this.resetPlayerAfterDelete(player);
                        this.deleteOldIslandChunks(skyblockManager, island, player);
                    });
        } catch (Exception e) {
            logger.log(Level.FATAL, e.getMessage(), e);
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        }
    }

    /**
     * 解析重建时要用的岛屿类型，与 {@code CreateSubCommand#resolveSchematicKey} 保持一致的优先级：
     * 玩家显式指定 &gt; islands.toml 的 default-schem-key &gt; 列表中的第一个。
     *
     * @return 有效的岛屿类型键；一个可用类型都没有时返回 {@code null}
     */
    private @Nullable String resolveNewIslandType(@Nullable String requestedType) {
        List<String> types = ConfigLoader.schematicManager.getIslandTypes();
        if (types.isEmpty()) return null;

        if (requestedType != null && types.contains(requestedType)) {
            return requestedType;
        }

        String defaultKey = ConfigLoader.islandManager.getDefaultIslandKey();
        if (defaultKey != null && types.contains(defaultKey)) {
            return defaultKey;
        }

        return types.getFirst();
    }

    /**
     * 旧岛区块的后台清理。玩家此时已经在新岛上了，这一步的成败不再影响他的体验。
     */
    private void deleteOldIslandChunks(SkyblockManager skyblockManager, Island island, Player player) {
        List<String> worldsToDelete = ConfigLoader.worldManager.getWorldConfigs().entrySet().stream()
                .filter(entry -> entry.getValue().shouldDeleteIsland())
                .map(Map.Entry::getKey)
                .toList();

        AtomicInteger worldsLeft = new AtomicInteger(worldsToDelete.size());
        AtomicBoolean failed = new AtomicBoolean(false);

        if (worldsLeft.get() == 0) {
            finalizeDeletion(skyblockManager, island, false, player);
            return;
        }

        worldsToDelete.forEach(worldName -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                failed.set(true);
                logger.log(Level.FATAL, "Failed to delete island {} in world {}: world not loaded", island.getId(), worldName);
                if (worldsLeft.decrementAndGet() == 0) {
                    finalizeDeletion(skyblockManager, island, failed.get(), player);
                }
                return;
            }

            Skyllia.getInstance().getInterneAPI().getWorldModifier().deleteIsland(island, world, ConfigLoader.general.getIslandSettings().regionDistance(), (success) -> {
                if (!success) failed.set(true);
                if (worldsLeft.decrementAndGet() == 0) {
                    finalizeDeletion(skyblockManager, island, failed.get(), player);
                }
            });
        });
    }

    /**
     * 按 players.toml 的 {@code when-delete} 配置重置执行者的背包 / 末影箱 / 经验。
     * <p>
     * 放在新岛建好之后执行，而不是删岛之前：万一建岛失败，玩家至少不会既没了岛、
     * 又被清空了背包。
     * </p>
     */
    private void resetPlayerAfterDelete(Player player) {
        if (!player.isOnline()) return;

        player.getScheduler().execute(Skyllia.getInstance(), () -> {
            if (ConfigLoader.playerManager.isClearInventoryWhenDelete()) {
                player.getInventory().clear();
            }
            if (ConfigLoader.playerManager.isClearEnderChestWhenDelete()) {
                player.getEnderChest().clear();
            }
            if (ConfigLoader.playerManager.isResetExperienceWhenDelete()) {
                player.setTotalExperience(0);
                player.setExp(0);
                player.setLevel(0);
                player.sendExperienceChange(0, 0);
            }
            player.setGameMode(GameMode.SURVIVAL);
        }, null, 1L);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();
            if ("confirm".startsWith(partial)) {
                return Collections.singletonList("confirm");
            }
            return Collections.emptyList();
        }

        // 第二个参数：重建时使用的岛屿类型，省略则用默认模板
        if (args.length == 2 && args[0].equalsIgnoreCase("confirm")) {
            String partial = args[1].trim().toLowerCase();
            List<String> types = ConfigLoader.schematicManager.getIslandTypes();
            List<String> out = new java.util.ArrayList<>();
            for (String type : types) {
                if (type.toLowerCase().startsWith(partial)
                        && PlayerUtils.hasPermission(sender, "skyllia.island.command.create.%s".formatted(type))) {
                    out.add(type);
                }
            }
            return out;
        }

        return Collections.emptyList();
    }

    /**
     * 把旧岛的全部成员降级为访客。
     * <p>
     * 降级本身还有一个必要的副作用：{@code getIslandByPlayerId} 的查询同时排除了
     * {@code disable = 1} 的岛屿和 {@code VISITOR} 角色，并且 {@code updateMember}
     * 会清掉玩家→岛屿的缓存链接。只有先走完这一步，紧接着的建岛流程才不会因为
     * 「你已经有岛了」而被挡回去（{@code CreateSubCommand} 检测到已有岛屿时会转去 /is home）。
     * </p>
     *
     * @param executorId 执行删除的岛主；他不在这里被清理和传送，而是随后被送去新岛
     */
    private void demoteMembers(SkyblockManager skyblockManager, Island island, java.util.UUID executorId) {
        for (Players players : island.getMembers()) {
            players.setRoleType(RoleType.VISITOR);
            island.updateMember(players);

            // 其他成员没有新岛可去，维持原本的行为：清理数据并送去全局出生点
            if (!players.getMojangId().equals(executorId)) {
                checkClearPlayer(skyblockManager, players, RemovalCause.ISLAND_DELETED);
            }
        }
    }

    private void finalizeDeletion(SkyblockManager skyblockManager, Island island, boolean failed, Player player) {
        boolean lockResult = skyblockManager.setLockedIsland(island, failed);
        if (!lockResult) {
            logger.log(Level.FATAL, "Failed to update lock state for island {} after deletion", island.getId());
            if (player.isOnline()) {
                ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
            }
            return;
        }

        if (!player.isOnline()) {
            return;
        }

        if (failed) {
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        } else {
            ConfigLoader.language.sendMessage(player, "island.delete-success");
        }
    }

    private void kickAllPlayerOnIsland(final Island island) {
        for (WorldConfig worldConfig : WorldUtils.getWorldConfigs()) {
            RegionUtils.getEntitiesInRegion(Skyllia.getInstance(), ConfigLoader.general.getIslandSettings().regionDistance(), EntityType.PLAYER, worldConfig.getWorld(), island.getRegionCoordinate(), island.getSize(), entity -> {
                Player playerInIsland = (Player) entity;
                if (PlayerUtils.hasPermission(playerInIsland, "skyllia.island.command.access.bypass")) return;
                PlayerUtils.teleportPlayerSpawn(playerInIsland);
            });
        }
    }
}