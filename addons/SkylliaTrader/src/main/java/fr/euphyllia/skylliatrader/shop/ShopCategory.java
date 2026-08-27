package fr.euphyllia.skylliatrader.shop;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * 游商货架分类。按材质推断，不在 {@code shop.toml} 里逐条手写——208 条商品靠命名规则归类，
 * 漏网的进 {@link #OTHER}，不会让货架缺货。
 */
public enum ShopCategory {
    CROPS("作物", Material.WHEAT_SEEDS),
    STONE("石材", Material.STONE),
    NATURE("花草", Material.POPPY),
    MOB_LOOT("战利品", Material.BLAZE_ROD),
    GEMS("矿物", Material.DIAMOND),
    RARE("稀有", Material.NETHER_STAR),
    OTHER("其他", Material.CHEST);

    private final String displayName;
    private final Material icon;

    ShopCategory(String displayName, Material icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public @NotNull String displayName() {
        return displayName;
    }

    public @NotNull Material icon() {
        return icon;
    }

    public static @NotNull ShopCategory of(@NotNull Material material) {
        String name = material.name();

        if (isRare(name, material)) return RARE;
        if (isGem(name, material)) return GEMS;
        if (isMobLoot(material)) return MOB_LOOT;
        if (isCrop(name, material)) return CROPS;
        if (isNature(name, material)) return NATURE;
        if (isStone(name, material)) return STONE;
        return OTHER;
    }

    private static boolean isRare(String name, Material material) {
        return name.endsWith("_SMITHING_TEMPLATE")
                || material == Material.ELYTRA
                || material == Material.NETHER_STAR
                || material == Material.HEART_OF_THE_SEA
                || material == Material.SPONGE
                || material == Material.BUDDING_AMETHYST
                || material == Material.NETHERITE_INGOT
                || material == Material.ANCIENT_DEBRIS
                || material == Material.ECHO_SHARD
                || material == Material.SCULK_CATALYST
                || material == Material.SEA_LANTERN;
    }

    private static boolean isGem(String name, Material material) {
        return material == Material.COAL
                || material == Material.RAW_IRON
                || material == Material.RAW_GOLD
                || material == Material.RAW_COPPER
                || material == Material.GLOWSTONE
                || material == Material.AMETHYST_SHARD
                || material == Material.DIAMOND
                || name.startsWith("RAW_");
    }

    private static boolean isMobLoot(Material material) {
        return material == Material.BLAZE_ROD
                || material == Material.MAGMA_CREAM
                || material == Material.GHAST_TEAR
                || material == Material.PRISMARINE_SHARD
                || material == Material.PRISMARINE_CRYSTALS;
    }

    private static boolean isCrop(String name, Material material) {
        return name.endsWith("_SEEDS")
                || name.endsWith("_SAPLING")
                || name.endsWith("_BERRIES")
                || material == Material.SUGAR_CANE
                || material == Material.NETHER_WART
                || material == Material.FROGSPAWN
                || material == Material.MANGROVE_PROPAGULE
                || material == Material.COCOA_BEANS
                || material == Material.CACTUS
                || material == Material.RED_MUSHROOM
                || material == Material.BROWN_MUSHROOM
                || material == Material.BAMBOO
                || material == Material.KELP
                || material == Material.SEAGRASS
                || material == Material.SEA_PICKLE
                || material == Material.CHORUS_FRUIT
                || material == Material.CRIMSON_STEM
                || material == Material.CRIMSON_NYLIUM
                || material == Material.WARPED_NYLIUM
                || material == Material.NETHER_WART_BLOCK
                || material == Material.SHROOMLIGHT
                || material == Material.MYCELIUM
                || name.equals("PALE_MOSS_BLOCK")
                || name.equals("RESIN_BLOCK")
                || name.equals("CREAKING_HEART");
    }

    private static boolean isNature(String name, Material material) {
        return name.endsWith("_DYE")
                || name.endsWith("_TULIP")
                || name.endsWith("_CORAL_FAN")
                || (name.endsWith("_CORAL") && !name.endsWith("_CORAL_BLOCK"))
                || material == Material.DANDELION
                || material == Material.POPPY
                || material == Material.BLUE_ORCHID
                || material == Material.ALLIUM
                || material == Material.AZURE_BLUET
                || material == Material.OXEYE_DAISY
                || material == Material.CORNFLOWER
                || material == Material.LILY_OF_THE_VALLEY
                || material == Material.SUNFLOWER
                || material == Material.LILAC
                || material == Material.ROSE_BUSH
                || material == Material.PEONY
                || material == Material.TORCHFLOWER
                || material == Material.LILY_PAD
                || material == Material.VINE
                || material == Material.AZALEA
                || material == Material.MOSS_BLOCK
                || material == Material.WEEPING_VINES
                || material == Material.GLOW_LICHEN
                || material == Material.SPORE_BLOSSOM;
    }

    private static boolean isStone(String name, Material material) {
        return name.endsWith("_TERRACOTTA")
                || name.equals("TERRACOTTA")
                || name.endsWith("_CONCRETE")
                || name.endsWith("_CORAL_BLOCK")
                || material == Material.SAND
                || material == Material.RED_SAND
                || material == Material.GRAVEL
                || material == Material.CLAY_BALL
                || material == Material.ICE
                || material == Material.PACKED_ICE
                || material == Material.BLUE_ICE
                || material == Material.NETHERRACK
                || material == Material.SOUL_SAND
                || material == Material.SOUL_SOIL
                || material == Material.END_STONE
                || material == Material.TUFF
                || material == Material.CALCITE
                || material == Material.GRANITE
                || material == Material.DIORITE
                || material == Material.ANDESITE
                || material == Material.POINTED_DRIPSTONE
                || name.equals("CINNABAR")
                || name.equals("SULFUR")
                || name.equals("POTENT_SULFUR")
                || material == Material.BASALT
                || material == Material.BLACKSTONE
                || material == Material.NETHER_BRICKS
                || material == Material.QUARTZ_BLOCK
                || material == Material.PURPUR_BLOCK
                || material == Material.GILDED_BLACKSTONE
                || material == Material.CRYING_OBSIDIAN;
    }
}
