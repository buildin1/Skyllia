package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.database.IslandPermissionQuery;
import fr.euphyllia.skyllia.api.permissions.CompiledPermissions;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.PermissionSet;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 权限管理 GUI：每条权限一个开关按钮。
 * <p>
 * 流程：
 * <ol>
 *   <li>角色选择菜单（5 个角色）</li>
 *   <li>权限列表菜单（分页，每页 28 条）</li>
 *   <li>每条权限一个按钮：
 *     <ul>
 *       <li>绿色羊毛 = 已开启</li>
 *       <li>红色羊毛 = 已关闭</li>
 *       <li>左键 = 开启</li>
 *       <li>右键 = 关闭</li>
 *       <li>悬停 lore 显示当前状态</li>
 *     </ul>
 *   </li>
 * </ol>
 * </p>
 * <p>
 * 名称和描述从注册表 PermissionNode 的 displayName/description（i18n key）
 * 通过 LanguageConfigManager.translateRaw 动态读取，避免硬编码遗漏。
 * </p>
 * <p>
 * 切换时不关闭菜单，直接原地 {@link Inventory#setItem(int, ItemStack)} 更新槽位，
 * 避免 closeInventory + 重开导致的屏幕闪烁和鼠标重置。
 * </p>
 */
public final class PermissionGui {

    private PermissionGui() {}

    /** 可切换的角色（不含 BAN） */
    private static final RoleType[] CONFIGURABLE_ROLES = {
            RoleType.OWNER, RoleType.CO_OWNER, RoleType.MODERATOR, RoleType.MEMBER, RoleType.VISITOR
    };

    /** 权限按钮槽位（中间 4×7 = 28 格） */
    private static final int[] PERM_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final int SLOT_ROLE_INFO = 4;
    private static final int SLOT_PREV_PAGE = 45;
    private static final int SLOT_NEXT_PAGE = 53;
    private static final int SLOT_BACK = 49;

    /** 角色选择菜单的边框 */
    private static final int[] ROLE_FILLER_SLOTS = {
            0, 1, 2, 3, 5, 6, 7, 8,
            9, 17,
            18, 19, 20, 21, 23, 24, 25, 26
    };

    // ═══════════════════════════════════════════════════════════
    //  角色选择菜单
    // ═══════════════════════════════════════════════════════════

    /** 打开角色选择菜单 */
    public static void openRoleSelect(@NotNull Player player, @NotNull Island island) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.PERMISSION_ROOT);
        Inventory inv = Bukkit.createInventory(holder, 27,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<light_purple>⚙ 选择角色"));

        for (int slot : ROLE_FILLER_SLOTS) {
            inv.setItem(slot, GuiItem.filler());
        }

        int slot = 11;
        for (RoleType role : CONFIGURABLE_ROLES) {
            inv.setItem(slot, GuiItem.of(roleMaterial(role),
                    "<!italic><light_purple>" + roleText(role),
                    List.of("<dark_gray>─────────",
                            "<gray>配置 " + roleText(role) + " 可用的权限</gray>",
                            "<dark_gray>─────────",
                            "<yellow>点击查看权限列表</yellow>")));
            final RoleType r = role;
            holder.bind(slot, e -> openList(player, island, r, 0));
            slot++;
        }

        inv.setItem(22, GuiItem.back());
        holder.bind(22, e -> MainGui.open(player));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  权限列表菜单
    // ═══════════════════════════════════════════════════════════

    /** 打开权限列表（分页） */
    public static void openList(@NotNull Player player, @NotNull Island island,
                                 @NotNull RoleType role, int pageIn) {
        PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
        List<NamespacedKey> keys = new ArrayList<>(registry.keys());
        keys.sort((a, b) -> a.toString().compareTo(b.toString()));

        int total = keys.size();
        int perPage = PERM_SLOTS.length;
        int totalPages = Math.max(1, (total + perPage - 1) / perPage);
        int page = pageIn;
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;
        final int currentPage = page;

        int from = page * perPage;
        int to = Math.min(from + perPage, total);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.PERMISSION_LIST);
        Inventory inv = Bukkit.createInventory(holder, 54,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<light_purple>⚙ " + roleText(role) + " 权限 (" + (page + 1) + "/" + totalPages + ")"));

        // 边框
        for (int i = 0; i < 54; i++) {
            if (isPermSlot(i) || i == SLOT_ROLE_INFO || i == SLOT_PREV_PAGE
                    || i == SLOT_NEXT_PAGE || i == SLOT_BACK) continue;
            inv.setItem(i, GuiItem.filler());
        }

        inv.setItem(SLOT_ROLE_INFO, GuiItem.of(roleMaterial(role),
                "<!italic><light_purple>" + roleText(role),
                List.of("<dark_gray>─────────",
                        "<gray>当前角色：</gray>" + roleText(role),
                        "<gray>权限总数：" + total + "</gray>",
                        "<dark_gray>─────────",
                        "<yellow>左键</yellow> <green>开启</green>",
                        "<yellow>右键</yellow> <red>关闭</red>")));

        CompiledPermissions compiled = island.getCompiledPermissions();
        compiled.ensureUpToDate(registry);
        Locale locale = player.locale();

        for (int i = from; i < to; i++) {
            NamespacedKey key = keys.get(i);
            PermissionId pid = registry.getIfPresent(key);
            if (pid == null) continue;

            boolean enabled = compiled.has(registry, role, pid);
            PermissionNode node = safeNode(registry, pid);
            String displayName = resolveName(locale, node, key);
            String description = resolveDesc(locale, node);

            int slotIndex = i - from;
            int slot = PERM_SLOTS[slotIndex];

            inv.setItem(slot, buildPermItem(enabled, displayName, key, description));

            final PermissionId pidFinal = pid;
            final PermissionNode nodeFinal = node;
            final String nameFinal = displayName;
            final String descFinal = description;
            final NamespacedKey keyFinal = key;
            holder.bind(slot, e -> {
                boolean newValue = !e.isRightClick();
                handlePermissionToggle(player, island, role, pidFinal, nodeFinal,
                        nameFinal, descFinal, keyFinal, newValue, currentPage, slot);
            });
        }

        if (page > 0) {
            inv.setItem(SLOT_PREV_PAGE, GuiItem.prevPage());
            holder.bind(SLOT_PREV_PAGE, e -> openList(player, island, role, currentPage - 1));
        }

        if (page < totalPages - 1) {
            inv.setItem(SLOT_NEXT_PAGE, GuiItem.nextPage());
            holder.bind(SLOT_NEXT_PAGE, e -> openList(player, island, role, currentPage + 1));
        }

        inv.setItem(SLOT_BACK, GuiItem.back());
        holder.bind(SLOT_BACK, e -> openRoleSelect(player, island));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  权限切换：DB + 运行时 + 原地更新槽位（不重开菜单）
    // ═══════════════════════════════════════════════════════════

    /**
     * 异步写入 DB + 同步运行时缓存，完成后直接更新当前打开 Inventory 的对应槽位。
     * <p>
     * 不调用 closeInventory / openInventory，避免屏幕闪烁和鼠标重置。
     * </p>
     */
    private static void handlePermissionToggle(@NotNull Player player,
                                                @NotNull Island island,
                                                @NotNull RoleType role,
                                                @NotNull PermissionId pid,
                                                @NotNull PermissionNode node,
                                                @NotNull String displayName,
                                                @NotNull String description,
                                                @NotNull NamespacedKey key,
                                                boolean newValue,
                                                int currentPage,
                                                int targetSlot) {
        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize("<gray>正在更新权限 </gray><white>" + displayName + "</white>"
                        + "<gray> → </gray>" + (newValue ? "<green>开启</green>" : "<red>关闭</red>")));

        Bukkit.getAsyncScheduler().runNow(Skyllia.getInstance(), task -> {
            boolean success = false;
            try {
                IslandPermissionQuery query = Skyllia.getInstance()
                        .getInterneAPI()
                        .getIslandQuery()
                        .getIslandPermissionQuery();
                if (query != null) {
                    success = query.set(island.getId(), role, pid, newValue);
                }
                if (success) {
                    PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
                    CompiledPermissions compiled = island.getCompiledPermissions();
                    compiled.ensureUpToDate(registry);
                    PermissionSet set = compiled.setFor(role);
                    if (set != null) {
                        set.set(pid, newValue);
                    }
                    player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<green>✔ 权限已更新：</green><white>" + displayName + "</white>"
                                    + "<gray> → </gray>" + (newValue ? "<green>开启</green>" : "<red>关闭</red>")));
                } else {
                    ConfigLoader.language.sendMessage(player, "island.permission.update.failed");
                }
            } catch (Exception e) {
                Skyllia.getInstance().getLogger().severe(
                        "[Skyllia-GUI] 权限更新失败: " + e.getMessage());
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        "§c权限更新失败，请查看控制台。"));
            }

            // 原地更新槽位（不重开菜单，避免闪烁）
            final boolean finalEnabled = success && newValue;
            player.getScheduler().run(Skyllia.getInstance(), t -> {
                InventoryView view = player.getOpenInventory();
                Inventory top = view.getTopInventory();
                if (top.getHolder() instanceof SkylliaGuiHolder) {
                    top.setItem(targetSlot, buildPermItem(finalEnabled, displayName, key, description));
                }
            }, null);
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  i18n 解析
    // ═══════════════════════════════════════════════════════════

    /** 安全读取 PermissionNode，失败时返回 null */
    private static @Nullable PermissionNode safeNode(@NotNull PermissionRegistry registry,
                                                      @NotNull PermissionId pid) {
        try {
            return registry.node(pid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 i18n key 解析权限中文名，失败时回退到 key 字符串。
     * <p>PermissionNode.displayName 形如 "island.permission.command.set_name.name"。</p>
     */
    private static @NotNull String resolveName(@NotNull Locale locale,
                                                @Nullable PermissionNode node,
                                                @NotNull NamespacedKey key) {
        if (node != null && node.displayName() != null && !node.displayName().isEmpty()) {
            String raw = ConfigLoader.language.translateRaw(locale, node.displayName(), Map.of());
            if (raw != null && !raw.startsWith("<red>Missing translation:")) {
                return raw;
            }
        }
        // 回退：从 key 生成可读名称
        return prettyKey(key);
    }

    /** 从 i18n key 解析权限描述 */
    private static @NotNull String resolveDesc(@NotNull Locale locale, @Nullable PermissionNode node) {
        if (node != null && node.description() != null && !node.description().isEmpty()) {
            String raw = ConfigLoader.language.translateRaw(locale, node.description(), Map.of());
            if (raw != null && !raw.startsWith("<red>Missing translation:")) {
                return raw;
            }
        }
        return "";
    }

    /** 将 NamespacedKey 美化为可读字符串（如 command.island.ban → command.island.ban） */
    private static @NotNull String prettyKey(@NotNull NamespacedKey key) {
        return key.getNamespace() + ":" + key.getKey();
    }

    // ═══════════════════════════════════════════════════════════
    //  物品构建
    // ═══════════════════════════════════════════════════════════

    /** 构建权限按钮物品 */
    private static @NotNull ItemStack buildPermItem(boolean enabled,
                                                     @NotNull String displayName,
                                                     @NotNull NamespacedKey key,
                                                     @NotNull String description) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>权限：</gray><white>" + prettyKey(key) + "</white>");
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

    private static boolean isPermSlot(int slot) {
        for (int s : PERM_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    private static @NotNull Material roleMaterial(@NotNull RoleType role) {
        return switch (role) {
            case OWNER -> Material.GOLDEN_APPLE;
            case CO_OWNER -> Material.GOLDEN_CARROT;
            case MODERATOR -> Material.EMERALD;
            case MEMBER -> Material.IRON_INGOT;
            case VISITOR -> Material.PAPER;
            case BAN -> Material.BARRIER;
        };
    }

    private static @NotNull String roleText(@NotNull RoleType role) {
        return switch (role) {
            case OWNER -> "岛主";
            case CO_OWNER -> "协管";
            case MODERATOR -> "管理员";
            case MEMBER -> "成员";
            case VISITOR -> "访客";
            case BAN -> "封禁";
        };
    }
}
