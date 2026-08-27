package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.coordinate.ChunkCoordinate;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.cache.commands.CommandCacheExecution;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 「群系图鉴」菜单：列出所有可用的生物群系，点击后对调用方传入的区块列表批量应用。
 * <p>
 * 由 {@link fr.euphyllia.skyllia.listeners.bukkitevents.player.BiomeSelectionToolListener}
 * 在玩家完成选区（并通过聊天栏可点击链接二次确认）后打开，传入的 {@code chunks} 已经是
 * 裁剪到该玩家岛屿领地范围内的最终区块列表。
 * </p>
 */
public final class BiomeCatalogGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** 生物群系名字（不含命名空间）→ 图标材质。任何没显式列出的群系一律回退到 {@link #FALLBACK_ICON}。 */
    private static final Map<String, Material> ICON_MAP = buildIconMap();
    private static final Material FALLBACK_ICON = Material.GRASS_BLOCK;

    private BiomeCatalogGui() {}

    public static void open(@NotNull Player player, @NotNull World world, @NotNull Island island,
                             @NotNull List<ChunkCoordinate> chunks) {
        open(player, world, island, chunks, 0);
    }

    public static void open(@NotNull Player player, @NotNull World world, @NotNull Island island,
                             @NotNull List<ChunkCoordinate> chunks, int page) {
        // 按选区所在世界的维度过滤：主世界只列主世界群系，下界只列下界群系（2026-08 反馈：
        // 主世界改成下界群系后会刷下界怪，跨维度改群系一律不再提供）
        List<String> biomeNames = new ArrayList<>(
                SkylliaAPI.getBiomesImpl().getBiomeNameList(world.getEnvironment()));
        biomeNames.sort(String::compareTo);

        int totalPages = GuiPageLayout.totalPages(biomeNames.size());
        int clamped = GuiPageLayout.clampPage(page, totalPages);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54,
                MM.deserialize("<light_purple>🌍 选择群系 <gray>(" + (clamped + 1) + "/" + totalPages + ")"));

        GuiPageLayout.fillBorder(inv);

        inv.setItem(GuiPageLayout.SLOT_HEADER, GuiItem.of(Material.GRASS_BLOCK,
                "<!italic><light_purple>共 " + chunks.size() + " 个区块待修改",
                List.of("<dark_gray>─────────",
                        "<gray>点击下方任意群系立即应用到选区</gray>",
                        "<dark_gray>此操作节流分批处理，不会卡服</dark_gray>")));

        if (biomeNames.isEmpty()) {
            inv.setItem(31, GuiItem.of(Material.BARRIER, "<!italic><red>没有可用的生物群系"));
        } else {
            int from = clamped * GuiPageLayout.PAGE_SIZE;
            int to = Math.min(from + GuiPageLayout.PAGE_SIZE, biomeNames.size());
            for (int i = from; i < to; i++) {
                String biomeName = biomeNames.get(i);
                int slot = GuiPageLayout.contentSlot(i - from);

                inv.setItem(slot, GuiItem.of(iconFor(biomeName),
                        "<!italic><light_purple>" + langTagFor(biomeName),
                        List.of("<dark_gray>─────────",
                                "<gray>" + biomeName + "</gray>",
                                "<dark_gray>─────────",
                                "<yellow>点击应用此群系</yellow>")));
                holder.bind(slot, e -> applyBiome(player, world, island, chunks, biomeName));
            }
        }

        if (clamped > 0) {
            inv.setItem(GuiPageLayout.SLOT_PREV_PAGE, GuiItem.prevPage());
            holder.bind(GuiPageLayout.SLOT_PREV_PAGE, e -> open(player, world, island, chunks, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(GuiPageLayout.SLOT_NEXT_PAGE, GuiItem.nextPage());
            holder.bind(GuiPageLayout.SLOT_NEXT_PAGE, e -> open(player, world, island, chunks, clamped + 1));
        }

        inv.setItem(GuiPageLayout.SLOT_CLOSE, GuiItem.close());
        holder.bind(GuiPageLayout.SLOT_CLOSE, e -> player.closeInventory());

        player.openInventory(inv);
    }

    /**
     * 应用选中的群系。走 {@link CommandCacheExecution} 按岛屿 id 防重入（与
     * {@code SetBiomeSubCommand} 用的是同一把锁的用法，key 都是 "biome"），底层调用
     * {@link fr.euphyllia.skyllia.managers.world.WorldModifier#changeBiomeRegion} 节流分批处理。
     */
    private static void applyBiome(@NotNull Player player, @NotNull World world, @NotNull Island island,
                                    @NotNull List<ChunkCoordinate> chunks, @NotNull String biomeName) {
        Biome biome = SkylliaAPI.getBiomesImpl().getBiome(biomeName);
        if (biome == null) {
            player.sendMessage(Component.text("§c该生物群系不存在或已失效，请重新打开菜单。"));
            return;
        }

        UUID islandId = island.getId();
        if (CommandCacheExecution.isAlreadyExecute(islandId, "biome")) {
            ConfigLoader.language.sendMessage(player, "island.generic.command-in-progress");
            return;
        }
        CommandCacheExecution.addCommandExecute(islandId, "biome");

        player.closeInventory();
        ConfigLoader.language.sendMessage(player, "island.biome.change-in-progress");

        Skyllia.getInstance().getInterneAPI().getWorldModifier()
                .changeBiomeRegion(world, chunks, biome, island)
                .thenAccept(success -> {
                    CommandCacheExecution.removeCommandExec(islandId, "biome");
                    Player online = Bukkit.getPlayer(player.getUniqueId());
                    if (online == null) return;
                    if (success) {
                        ConfigLoader.language.sendMessage(online, "island.biome.island-success");
                    } else {
                        ConfigLoader.language.sendMessage(online, "island.generic.unexpected-error");
                    }
                })
                .exceptionally(ex -> {
                    CommandCacheExecution.removeCommandExec(islandId, "biome");
                    Player online = Bukkit.getPlayer(player.getUniqueId());
                    if (online != null) {
                        ConfigLoader.language.sendMessage(online, "island.generic.unexpected-error");
                    }
                    return null;
                });
    }

    /**
     * 群系名 → 图标。任何没有显式映射的群系（含未来新增的原版群系、任何第三方群系）
     * 一律回退到 {@link #FALLBACK_ICON}，绝不因为漏了某个群系就抛异常。
     * 额外用 {@code isItem()} 兜底一次，防止映射表本身配错了非物品材质。
     */
    private static Material iconFor(@NotNull String biomeName) {
        String key = stripNamespace(biomeName).toLowerCase(Locale.ROOT);
        Material mapped = ICON_MAP.getOrDefault(key, FALLBACK_ICON);
        return mapped.isItem() ? mapped : Material.PAPER;
    }

    /** 用 Minecraft 客户端自带翻译渲染群系显示名（随玩家客户端语言变化）。 */
    private static String langTagFor(@NotNull String biomeName) {
        String namespace = "minecraft";
        String key = biomeName;
        int idx = biomeName.indexOf(':');
        if (idx >= 0) {
            namespace = biomeName.substring(0, idx);
            key = biomeName.substring(idx + 1);
        }
        return "<lang:biome." + namespace + "." + key + ">";
    }

    private static String stripNamespace(@NotNull String biomeName) {
        int idx = biomeName.indexOf(':');
        return idx >= 0 ? biomeName.substring(idx + 1) : biomeName;
    }

    private static Map<String, Material> buildIconMap() {
        Map<String, Material> m = new HashMap<>();
        // 平原 / 雪原类
        m.put("plains", Material.GRASS_BLOCK);
        m.put("sunflower_plains", Material.SUNFLOWER);
        m.put("snowy_plains", Material.SNOW_BLOCK);
        m.put("ice_spikes", Material.PACKED_ICE);
        m.put("snowy_slopes", Material.SNOWBALL);
        m.put("frozen_peaks", Material.BLUE_ICE);
        m.put("jagged_peaks", Material.STONE);
        m.put("stony_peaks", Material.STONE);
        m.put("grove", Material.SPRUCE_SAPLING);
        // 沙漠 / 恶地
        m.put("desert", Material.SAND);
        m.put("badlands", Material.RED_SAND);
        m.put("eroded_badlands", Material.RED_SANDSTONE);
        m.put("wooded_badlands", Material.COARSE_DIRT);
        // 沼泽
        m.put("swamp", Material.LILY_PAD);
        m.put("mangrove_swamp", Material.MANGROVE_ROOTS);
        // 森林类
        m.put("forest", Material.OAK_LEAVES);
        m.put("flower_forest", Material.POPPY);
        m.put("birch_forest", Material.BIRCH_LEAVES);
        m.put("dark_forest", Material.DARK_OAK_LEAVES);
        m.put("old_growth_birch_forest", Material.BIRCH_LOG);
        m.put("old_growth_pine_taiga", Material.SPRUCE_LOG);
        m.put("old_growth_spruce_taiga", Material.STRIPPED_SPRUCE_LOG);
        m.put("cherry_grove", Material.CHERRY_SAPLING);
        // 针叶林 / 稀树草原
        m.put("taiga", Material.SPRUCE_LEAVES);
        m.put("snowy_taiga", Material.SNOW);
        m.put("savanna", Material.ACACIA_LEAVES);
        m.put("savanna_plateau", Material.ACACIA_LOG);
        // 山地
        m.put("windswept_hills", Material.STONE);
        m.put("windswept_gravelly_hills", Material.GRAVEL);
        m.put("windswept_forest", Material.MOSSY_COBBLESTONE);
        m.put("windswept_savanna", Material.COARSE_DIRT);
        m.put("meadow", Material.DANDELION);
        // 丛林
        m.put("jungle", Material.JUNGLE_LEAVES);
        m.put("sparse_jungle", Material.JUNGLE_SAPLING);
        m.put("bamboo_jungle", Material.BAMBOO);
        // 河流 / 海滩
        m.put("river", Material.WATER_BUCKET);
        m.put("frozen_river", Material.ICE);
        m.put("beach", Material.SAND);
        m.put("snowy_beach", Material.SNOW_BLOCK);
        m.put("stony_shore", Material.GRAVEL);
        // 海洋
        m.put("warm_ocean", Material.BRAIN_CORAL_BLOCK);
        m.put("lukewarm_ocean", Material.PRISMARINE);
        m.put("deep_lukewarm_ocean", Material.PRISMARINE_BRICKS);
        m.put("ocean", Material.WATER_BUCKET);
        m.put("deep_ocean", Material.DARK_PRISMARINE);
        m.put("cold_ocean", Material.BLUE_ICE);
        m.put("deep_cold_ocean", Material.PACKED_ICE);
        m.put("frozen_ocean", Material.ICE);
        m.put("deep_frozen_ocean", Material.PACKED_ICE);
        // 蘑菇岛 / 洞穴
        m.put("mushroom_fields", Material.RED_MUSHROOM);
        m.put("dripstone_caves", Material.POINTED_DRIPSTONE);
        m.put("lush_caves", Material.MOSS_BLOCK);
        m.put("deep_dark", Material.SCULK);
        // 下界
        m.put("nether_wastes", Material.NETHERRACK);
        m.put("warped_forest", Material.WARPED_NYLIUM);
        m.put("crimson_forest", Material.CRIMSON_NYLIUM);
        m.put("soul_sand_valley", Material.SOUL_SAND);
        m.put("basalt_deltas", Material.BASALT);
        // 末地
        m.put("the_end", Material.END_STONE);
        m.put("end_highlands", Material.END_STONE);
        m.put("end_midlands", Material.END_STONE);
        m.put("small_end_islands", Material.END_STONE);
        m.put("end_barrens", Material.END_STONE);
        m.put("the_void", Material.BARRIER);
        return m;
    }
}
