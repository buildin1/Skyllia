package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.database.IslandPermissionQuery;
import fr.euphyllia.skyllia.api.permissions.FlagId;
import fr.euphyllia.skyllia.api.permissions.FlagNode;
import fr.euphyllia.skyllia.api.permissions.IslandFlagRegistry;
import fr.euphyllia.skyllia.api.permissions.IslandFlags;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 空岛标记 GUI：每条标记一个开关按钮。
 * <p>
 * 标记是全岛范围的环境开关（爆炸/火焰/生物破坏等），按世界区分。
 * </p>
 * <p>
 * 交互：
 * <ul>
 *   <li>绿色羊毛 = 已开启</li>
 *   <li>红色羊毛 = 已关闭</li>
 *   <li>左键 = 开启</li>
 *   <li>右键 = 关闭</li>
 *   <li>悬停 lore 显示当前状态</li>
 * </ul>
 * </p>
 * <p>
 * 名称和描述从注册表 FlagNode 的 displayName/description（i18n key）
 * 通过 LanguageConfigManager.translateRaw 动态读取，避免硬编码遗漏。
 * </p>
 * <p>
 * 切换时不关闭菜单，直接原地 {@link Inventory#setItem(int, ItemStack)} 更新槽位，
 * 避免 closeInventory + 重开导致的屏幕闪烁和鼠标重置。
 * </p>
 */
public final class FlagGui {

    private FlagGui() {}

    /** 标记按钮槽位 */
    private static final int[] FLAG_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final int SLOT_WORLD_INFO = 4;
    private static final int SLOT_BACK = 49;

    /** 标记列表菜单的边框 */
    private static final int[] FILLER_SLOTS = {
            0, 1, 2, 3, 5, 6, 7, 8,
            9, 17,
            18, 26,
            27, 35,
            36, 44,
            45, 46, 47, 48, 50, 51, 52, 53
    };

    /** 打开标记管理菜单 */
    public static void open(@NotNull Player player, @NotNull Island island) {
        String worldName = resolveWorldName(player);

        IslandFlagRegistry registry = SkylliaAPI.getFlagRegistry();
        List<NamespacedKey> keys = new ArrayList<>(registry.keys());
        keys.sort((a, b) -> a.toString().compareTo(b.toString()));

        int total = keys.size();

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.FLAG_LIST);
        Inventory inv = Bukkit.createInventory(holder, 54,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<light_purple>🚩 空岛标记"));

        for (int slot : FILLER_SLOTS) {
            inv.setItem(slot, GuiItem.filler());
        }

        inv.setItem(SLOT_WORLD_INFO, GuiItem.of(Material.NETHER_STAR,
                "<!italic><light_purple>标记信息",
                List.of("<dark_gray>─────────",
                        "<gray>作用世界：</gray><white>" + worldName + "</white>",
                        "<gray>标记总数：" + total + "</gray>",
                        "<dark_gray>─────────",
                        "<yellow>左键</yellow> <green>开启</green>",
                        "<yellow>右键</yellow> <red>关闭</red>")));

        IslandFlags flags = island.getIslandFlags(worldName);
        flags.ensureUpToDate(registry);
        Locale locale = player.locale();

        for (int i = 0; i < total && i < FLAG_SLOTS.length; i++) {
            NamespacedKey key = keys.get(i);
            FlagId fid = registry.getIfPresent(key);
            if (fid == null) continue;

            boolean enabled = flags.has(registry, fid);
            FlagNode node = safeNode(registry, fid);
            String displayName = resolveName(locale, node, key);
            String description = resolveDesc(locale, node);

            int slot = FLAG_SLOTS[i];
            inv.setItem(slot, buildFlagItem(enabled, displayName, key, description));

            final FlagId fidFinal = fid;
            final FlagNode nodeFinal = node;
            final String nameFinal = displayName;
            final String descFinal = description;
            final NamespacedKey keyFinal = key;
            holder.bind(slot, e -> {
                boolean newValue = !e.isRightClick();
                handleFlagToggle(player, island, fidFinal, nodeFinal,
                        nameFinal, descFinal, keyFinal, newValue, worldName, slot);
            });
        }

        inv.setItem(SLOT_BACK, GuiItem.back());
        holder.bind(SLOT_BACK, e -> MainGui.open(player));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  标记切换：DB + 运行时 + 原地更新槽位（不重开菜单）
    // ═══════════════════════════════════════════════════════════

    /**
     * 异步写入 DB + 同步运行时缓存，完成后直接更新当前打开 Inventory 的对应槽位。
     * <p>不调用 closeInventory / openInventory，避免屏幕闪烁和鼠标重置。</p>
     */
    private static void handleFlagToggle(@NotNull Player player,
                                          @NotNull Island island,
                                          @NotNull FlagId fid,
                                          @Nullable FlagNode node,
                                          @NotNull String displayName,
                                          @NotNull String description,
                                          @NotNull NamespacedKey key,
                                          boolean newValue,
                                          @NotNull String worldName,
                                          int targetSlot) {
        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize("<gray>正在更新标记 </gray><white>" + displayName + "</white>"
                        + "<gray> → </gray>" + (newValue ? "<green>开启</green>" : "<red>关闭</red>")));

        Bukkit.getAsyncScheduler().runNow(Skyllia.getInstance(), task -> {
            boolean success = false;
            try {
                IslandPermissionQuery query = Skyllia.getInstance()
                        .getInterneAPI()
                        .getIslandQuery()
                        .getIslandPermissionQuery();
                if (query != null) {
                    IslandFlagRegistry registry = SkylliaAPI.getFlagRegistry();
                    success = query.setFlag(island.getId(), registry, fid, worldName, newValue);

                    // 同步运行时缓存
                    if (success) {
                        IslandFlags flags = island.getIslandFlags(worldName);
                        flags.ensureUpToDate(registry);
                        flags.set(registry, fid, newValue);
                    }
                }
                if (success) {
                    player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<green>✔ 标记已更新：</green><white>" + displayName + "</white>"
                                    + "<gray> → </gray>" + (newValue ? "<green>开启</green>" : "<red>关闭</red>")));
                } else {
                    ConfigLoader.language.sendMessage(player, "island.flag.update.failed");
                }
            } catch (Exception e) {
                Skyllia.getInstance().getLogger().severe(
                        "[Skyllia-GUI] 标记更新失败: " + e.getMessage());
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        "§c标记更新失败，请查看控制台。"));
            }

            // 原地更新槽位（不重开菜单，避免闪烁）
            final boolean finalEnabled = success && newValue;
            player.getScheduler().run(Skyllia.getInstance(), t -> {
                InventoryView view = player.getOpenInventory();
                Inventory top = view.getTopInventory();
                if (top.getHolder() instanceof SkylliaGuiHolder) {
                    top.setItem(targetSlot, buildFlagItem(finalEnabled, displayName, key, description));
                }
            }, null);
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  i18n 解析
    // ═══════════════════════════════════════════════════════════

    /** 安全读取 FlagNode，失败时返回 null */
    private static @Nullable FlagNode safeNode(@NotNull IslandFlagRegistry registry,
                                                @NotNull FlagId fid) {
        try {
            return registry.node(fid);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 i18n key 解析标记中文名，失败时回退到 key 字符串 */
    private static @NotNull String resolveName(@NotNull Locale locale,
                                                @Nullable FlagNode node,
                                                @NotNull NamespacedKey key) {
        if (node != null && node.displayName() != null && !node.displayName().isEmpty()) {
            String raw = ConfigLoader.language.translateRaw(locale, node.displayName(), Map.of());
            if (raw != null && !raw.startsWith("<red>Missing translation:")) {
                return raw;
            }
        }
        return prettyKey(key);
    }

    /** 从 i18n key 解析标记描述 */
    private static @NotNull String resolveDesc(@NotNull Locale locale, @Nullable FlagNode node) {
        if (node != null && node.description() != null && !node.description().isEmpty()) {
            String raw = ConfigLoader.language.translateRaw(locale, node.description(), Map.of());
            if (raw != null && !raw.startsWith("<red>Missing translation:")) {
                return raw;
            }
        }
        return "";
    }

    /** 将 NamespacedKey 美化为可读字符串 */
    private static @NotNull String prettyKey(@NotNull NamespacedKey key) {
        return key.getNamespace() + ":" + key.getKey();
    }

    // ═══════════════════════════════════════════════════════════
    //  物品构建
    // ═══════════════════════════════════════════════════════════

    /** 构建标记按钮物品 */
    private static @NotNull ItemStack buildFlagItem(boolean enabled,
                                                     @NotNull String displayName,
                                                     @NotNull NamespacedKey key,
                                                     @NotNull String description) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>标记：</gray><white>" + prettyKey(key) + "</white>");
        if (!description.isEmpty()) {
            lore.add("<gray>说明：</gray><white>" + description + "</white>");
        }
        lore.add("<gray>当前状态：</gray>" + (enabled ? "<green>✔ 已开启</green>" : "<red>✘ 已关闭</red>"));
        lore.add("<dark_gray>─────────");
        lore.add("<yellow>左键</yellow> <green>开启</green>");
        lore.add("<yellow>右键</yellow> <red>关闭</red>");

        return GuiItem.of(
                enabled ? Material.GREEN_WOOL : Material.RED_WOOL,
                "<!italic>" + (enabled ? "<green>✔ " : "<red>✘ ") + displayName,
                lore);
    }

    // ═══════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════

    /** 解析标记适用的世界名 */
    private static @NotNull String resolveWorldName(@NotNull Player player) {
        String playerWorld = player.getWorld().getName();
        if (SkylliaAPI.isWorldSkyblock(playerWorld)) {
            return playerWorld;
        }
        List<WorldConfig> worlds = SkylliaAPI.getRegisteredWorlds();
        if (worlds.isEmpty()) {
            return playerWorld;
        }
        return worlds.getFirst().getWorldName();
    }
}
