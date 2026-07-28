package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Skyllia 主菜单（54 格）。
 * <p>
 * 全功能布局：
 * <pre>
 * 行1: ▓▓▓▓ 📋信息 ▓▓ 🏠回家 ▓▓▓▓
 * 行2: ▓ 📍设家园 ▓ ⚛设出生 ▓ 🧭设访问 ▓
 * 行3: ▓ 🔓开放/私密 ▓ 📛重置名称 ▓ 📝重置描述 ▓
 * 行4: ▓ 📊服务器TPS ▓ ⚙权限列表 ▓ 🚩标记列表 ▓
 * 行5: ▓ 🌐访问空岛 ▓ 🔑成员管理 ▓ 🚷访客管理 ▓
 * 行6: ▓▓▓▓▓ ⚠危险操作 ▓ ❌关闭 ▓▓▓▓▓
 * </pre>
 * </p>
 */
public final class MainGui {

    private MainGui() {}

    // ── 槽位常量 ──────────────────────────────────────────────
    // 行 1
    private static final int SLOT_INFO = 4;
    private static final int SLOT_HOME = 7;
    // 行 2：位置设置
    private static final int SLOT_SET_HOME = 11;
    private static final int SLOT_SET_SPAWN = 13;
    private static final int SLOT_SET_VISIT = 15;
    // 行 3：空岛设置
    private static final int SLOT_ACCESS = 20;
    private static final int SLOT_NAME_RESET = 22;
    private static final int SLOT_DESC_RESET = 24;
    // 行 4：TPS + 权限 + 标记
    private static final int SLOT_TPS = 29;
    private static final int SLOT_PERMISSION = 31;
    private static final int SLOT_FLAG = 33;
    // 行 5：访问 + 成员 + 访客
    private static final int SLOT_VISIT = 38;
    private static final int SLOT_MEMBER = 40;
    private static final int SLOT_VISITOR = 42;
    // 行 6
    private static final int SLOT_DANGER = 47;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_EXTENSION = 51;

    /** 边框/装饰槽位 */
    private static final int[] FILLER_SLOTS = {
            0, 1, 2, 3, 5, 6, 8,
            9, 17,
            18, 26,
            27, 35,
            36, 44,
            45, 46, 48, 50, 52, 53
    };

    public static void open(@NotNull Player player) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        boolean hasIsland = island != null;
        boolean isOwner = hasIsland && island.getOwner() != null
                && island.getOwner().getMojangId().equals(player.getUniqueId());

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.MAIN);
        Inventory inv = Bukkit.createInventory(holder, 54,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<light_purple>空岛管理"));

        // 边框填充
        for (int slot : FILLER_SLOTS) {
            inv.setItem(slot, GuiItem.filler());
        }

        // ── 行 1：信息 + 回家 ────────────────────────────────
        inv.setItem(SLOT_INFO, GuiItem.of(Material.ENDER_CHEST,
                "<!italic><light_purple>📋 空岛信息",
                lore("查看空岛详细信息", hasIsland ? "点击查看" : "需先创建空岛", hasIsland)));
        holder.bind(SLOT_INFO, e -> {
            if (checkIsland(player, hasIsland)) dispatch(player, "is info");
        });

        inv.setItem(SLOT_HOME, GuiItem.of(Material.RED_BED,
                "<!italic><light_purple>🏠 回到空岛",
                lore("传送回你的空岛", "点击传送", hasIsland)));
        holder.bind(SLOT_HOME, e -> {
            if (hasIsland) {
                dispatch(player, "is home");
            } else {
                dispatch(player, "is create");
            }
        });

        // ── 行 2：位置设置 ────────────────────────────────────
        inv.setItem(SLOT_SET_HOME, GuiItem.of(Material.GRASS_BLOCK,
                "<!italic><light_purple>📍 设置家园点",
                lore("将当前位置设为空岛家园", "点击设置（需在空岛上）", hasIsland)));
        holder.bind(SLOT_SET_HOME, e -> {
            if (checkIsland(player, hasIsland)) dispatch(player, "is sethome");
        });

        inv.setItem(SLOT_SET_SPAWN, GuiItem.of(Material.RESPAWN_ANCHOR,
                "<!italic><light_purple>⚛ 设置出生点",
                lore("将当前位置设为空岛出生点", "点击设置（需在空岛上）", hasIsland && isOwner, notOwnerHint(hasIsland, isOwner))));
        holder.bind(SLOT_SET_SPAWN, e -> {
            if (!checkIsland(player, hasIsland)) return;
            if (!isOwner) { player.sendMessage(net.kyori.adventure.text.Component.text("§c只有岛主可以设置出生点。")); return; }
            dispatch(player, "is setspawn");
        });

        inv.setItem(SLOT_SET_VISIT, GuiItem.of(Material.COMPASS,
                "<!italic><light_purple>🧭 设置访问点",
                lore("访客访问空岛时的到达点", "点击设置（需在空岛上）", hasIsland)));
        holder.bind(SLOT_SET_VISIT, e -> {
            if (checkIsland(player, hasIsland)) dispatch(player, "is setvisit");
        });

        // ── 行 3：空岛设置 ────────────────────────────────────
        inv.setItem(SLOT_ACCESS, GuiItem.of(Material.IRON_DOOR,
                "<!italic><light_purple>🔓 开放/私密切换",
                lore("切换空岛是否允许访客进入", "点击切换", hasIsland)));
        holder.bind(SLOT_ACCESS, e -> {
            if (checkIsland(player, hasIsland)) dispatch(player, "is access");
        });

        inv.setItem(SLOT_NAME_RESET, GuiItem.of(Material.NAME_TAG,
                "<!italic><light_purple>📛 重置空岛名称",
                lore("清除自定义名称，恢复默认", "点击重置", hasIsland && isOwner, notOwnerHint(hasIsland, isOwner))));
        holder.bind(SLOT_NAME_RESET, e -> {
            if (!checkIsland(player, hasIsland)) return;
            if (!isOwner) { player.sendMessage(net.kyori.adventure.text.Component.text("§c只有岛主可以重置名称。")); return; }
            dispatch(player, "is setname reset");
        });

        inv.setItem(SLOT_DESC_RESET, GuiItem.of(Material.WRITABLE_BOOK,
                "<!italic><light_purple>📝 重置空岛描述",
                lore("清除自定义描述", "点击重置", hasIsland && isOwner, notOwnerHint(hasIsland, isOwner))));
        holder.bind(SLOT_DESC_RESET, e -> {
            if (!checkIsland(player, hasIsland)) return;
            if (!isOwner) { player.sendMessage(net.kyori.adventure.text.Component.text("§c只有岛主可以重置描述。")); return; }
            dispatch(player, "is setdescription reset");
        });

        // ── 行 4：TPS + 权限管理 + 标记管理 ──────────────────
        inv.setItem(SLOT_TPS, GuiItem.of(Material.CLOCK,
                "<!italic><light_purple>📊 服务器 TPS",
                List.of("<dark_gray>─────────",
                        "<gray>查看服务器性能指标</gray>",
                        "<dark_gray>─────────",
                        "<yellow>点击查看</yellow>")));
        holder.bind(SLOT_TPS, e -> dispatch(player, "is tps"));

        inv.setItem(SLOT_PERMISSION, GuiItem.of(Material.GOLD_NUGGET,
                "<!italic><light_purple>⚙ 权限管理",
                List.of("<dark_gray>─────────",
                        "<gray>按角色配置每条权限的开关</gray>",
                        "<gray>左键开启 / 右键关闭</gray>",
                        "<dark_gray>─────────",
                        "<yellow>点击打开</yellow>")));
        holder.bind(SLOT_PERMISSION, e -> {
            if (checkIsland(player, hasIsland)) PermissionGui.openRoleSelect(player, island);
        });

        inv.setItem(SLOT_FLAG, GuiItem.of(Material.REDSTONE_TORCH,
                "<!italic><light_purple>🚩 空岛标记",
                List.of("<dark_gray>─────────",
                        "<gray>开关爆炸/火焰/生物破坏等</gray>",
                        "<gray>左键开启 / 右键关闭</gray>",
                        "<dark_gray>─────────",
                        "<yellow>点击打开</yellow>")));
        holder.bind(SLOT_FLAG, e -> {
            if (checkIsland(player, hasIsland)) FlagGui.open(player, island);
        });

        // ── 行 5：访问 + 成员 + 访客 ──────────────────────────
        inv.setItem(SLOT_VISIT, GuiItem.of(Material.OAK_DOOR,
                "<!italic><light_purple>🌐 访问其他空岛",
                lore("选择在线玩家前往其空岛", "点击打开列表", hasIsland)));
        holder.bind(SLOT_VISIT, e -> {
            if (checkIsland(player, hasIsland)) openVisitMenu(player);
        });

        inv.setItem(SLOT_MEMBER, GuiItem.of(Material.GOLDEN_APPLE,
                "<!italic><light_purple>🔑 成员管理",
                lore("晋升/降级/踢出/转让", "点击打开成员列表", hasIsland)));
        holder.bind(SLOT_MEMBER, e -> {
            if (checkIsland(player, hasIsland)) openMemberMenu(player, island);
        });

        inv.setItem(SLOT_VISITOR, GuiItem.of(Material.IRON_DOOR,
                "<!italic><light_purple>🚷 访客管理",
                lore("封禁/驱逐/解封访客", "点击打开管理列表", hasIsland)));
        holder.bind(SLOT_VISITOR, e -> {
            if (checkIsland(player, hasIsland)) openVisitorMenu(player, island);
        });

        // ── 行 6：危险操作 + 关闭 ────────────────────────────
        List<String> dangerLore = new ArrayList<>();
        dangerLore.add("<dark_gray>─────────");
        dangerLore.add("<gray>包含以下操作：</gray>");
        dangerLore.add("<red>• 离开空岛</red>");
        dangerLore.add("<red>• 删除空岛</red>");
        dangerLore.add("<dark_gray>─────────");
        dangerLore.add("<yellow>⚠ 需要二次确认</yellow>");
        inv.setItem(SLOT_DANGER, GuiItem.danger(Material.TNT, "危险操作", dangerLore));
        holder.bind(SLOT_DANGER, e -> {
            if (checkIsland(player, hasIsland)) openDangerMenu(player, island);
        });

        inv.setItem(SLOT_CLOSE, GuiItem.close());
        holder.bind(SLOT_CLOSE, e -> player.closeInventory());

        // ── 扩展功能（由已安装的 addon 通过 GuiExtensionRegistry 注册） ──
        int extensionCount = GuiExtensionRegistry.entries().size();
        List<String> extensionLore = new ArrayList<>();
        extensionLore.add("<dark_gray>─────────");
        extensionLore.add("<gray>由已安装的插件扩展提供的功能</gray>");
        extensionLore.add("<gray>已注册：" + extensionCount + " 项</gray>");
        extensionLore.add("<dark_gray>─────────");
        extensionLore.add("<yellow>点击打开</yellow>");
        inv.setItem(SLOT_EXTENSION, GuiItem.of(Material.NETHER_STAR,
                "<!italic><light_purple>🧩 扩展功能", extensionLore));
        holder.bind(SLOT_EXTENSION, e -> ExtensionGui.open(player, 0));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  子菜单：访问其他空岛
    // ═══════════════════════════════════════════════════════════

    /** 打开访问空岛菜单：显示在线玩家列表，点击传送到对方空岛 */
    private static void openVisitMenu(@NotNull Player player) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.MEMBER);
        Inventory inv = Bukkit.createInventory(holder, 54,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<light_purple>🌐 访问空岛"));

        // 边框
        for (int i : FILLER_SLOTS) {
            inv.setItem(i, GuiItem.filler());
        }

        // 在线玩家头颅（排除自己），填入中间区域（槽 10-34，共 25 格）
        int slot = 10;
        int col = 0;
        int shown = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId())) continue;
            if (shown >= 28) break; // 最多显示 28 个

            String name = online.getName();
            List<String> headLore = new ArrayList<>();
            headLore.add("<dark_gray>─────────");
            headLore.add("<gray>前往 " + name + " 的空岛</gray>");
            headLore.add("<dark_gray>─────────");
            headLore.add("<yellow>点击传送</yellow>");

            inv.setItem(slot, GuiItem.playerHead(online.getUniqueId(),
                    "<!italic><light_purple>" + name, headLore));
            final String targetName = name;
            holder.bind(slot, e -> dispatch(player, "is visit " + targetName));

            shown++;
            col++;
            slot++;
            // 跳过边框列（每行第 9 列是边框）
            if (col == 7) {
                slot += 2; // 跳过 17/26/35 等边框
                col = 0;
            }
        }

        if (shown == 0) {
            inv.setItem(22, GuiItem.of(Material.BARRIER,
                    "<!italic><red>没有其他在线玩家",
                    List.of("<dark_gray>─────────",
                            "<gray>当前没有可访问的空岛</gray>")));
        }

        // 返回按钮
        inv.setItem(49, GuiItem.back());
        holder.bind(49, e -> backToMain(player));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  子菜单：成员管理
    // ═══════════════════════════════════════════════════════════

    /** 打开成员管理菜单：显示空岛成员（真实头颅），点击进入操作 */
    private static void openMemberMenu(@NotNull Player player, @NotNull Island island) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.MEMBER);
        Inventory inv = Bukkit.createInventory(holder, 54,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<light_purple>🔑 成员管理"));

        for (int i : FILLER_SLOTS) {
            inv.setItem(i, GuiItem.filler());
        }

        List<Players> members = island.getMembers();
        boolean isOwner = island.getOwner() != null
                && island.getOwner().getMojangId().equals(player.getUniqueId());

        int slot = 10;
        int col = 0;
        for (Players member : members) {
            UUID memberId = member.getMojangId();
            String name = member.getLastKnowName();
            if (name == null || name.isEmpty()) name = "未知玩家";
            RoleType role = member.getRoleType();

            List<String> headLore = new ArrayList<>();
            headLore.add("<dark_gray>─────────");
            headLore.add("<gray>角色：</gray>" + roleText(role));
            headLore.add("<dark_gray>─────────");

            boolean isSelf = memberId.equals(player.getUniqueId());
            boolean isMemberOwner = role == RoleType.OWNER;

            if (isMemberOwner) {
                headLore.add("<yellow>★ 岛主</yellow>");
            } else if (isSelf) {
                headLore.add("<dark_gray>（你自己）</dark_gray>");
            } else if (isOwner) {
                headLore.add("<yellow>点击</yellow> <gray>管理成员</gray>");
            } else {
                headLore.add("<dark_gray>仅岛主可管理</dark_gray>");
            }

            inv.setItem(slot, GuiItem.playerHead(memberId,
                    "<!italic><light_purple>" + name, headLore));

            final Players targetMember = member;
            if (!isMemberOwner && !isSelf && isOwner) {
                holder.bind(slot, e -> openMemberActionMenu(player, island, targetMember));
            }

            col++;
            slot++;
            if (col == 7) {
                slot += 2;
                col = 0;
            }
            if (slot > 34) break; // 防止溢出到边框
        }

        // 成员计数
        inv.setItem(49, GuiItem.of(Material.PAPER,
                "<!italic><light_purple>成员信息",
                List.of("<dark_gray>─────────",
                        "<gray>成员总数：" + members.size() + "</gray>",
                        "<yellow>← 点击返回</yellow>")));
        holder.bind(49, e -> backToMain(player));

        player.openInventory(inv);
    }

    /** 打开成员操作菜单：晋升/降级/踢出/转让 */
    private static void openMemberActionMenu(@NotNull Player player,
                                              @NotNull Island island,
                                              @NotNull Players target) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.MEMBER);
        Inventory inv = Bukkit.createInventory(holder, 27,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<light_purple>管理：" + safeName(target)));

        // 边框
        for (int i = 0; i < 27; i++) {
            if (i == 11 || i == 13 || i == 15 || i == 20 || i == 22 || i == 24) continue;
            inv.setItem(i, GuiItem.of(Material.PURPLE_STAINED_GLASS_PANE, "<!italic><dark_gray> "));
        }

        String name = safeName(target);
        RoleType role = target.getRoleType();

        // 槽 13：成员头颅（信息展示）
        inv.setItem(13, GuiItem.playerHead(target.getMojangId(),
                "<!italic><light_purple>" + name,
                List.of("<dark_gray>─────────",
                        "<gray>当前角色：</gray>" + roleText(role),
                        "<dark_gray>选择下方操作</dark_gray>")));

        // 槽 11：晋升
        boolean canPromote = role != RoleType.CO_OWNER; // CO_OWNER 之上是 OWNER，不允许
        List<String> promoteLore = new ArrayList<>();
        promoteLore.add("<dark_gray>─────────");
        promoteLore.add("<gray>提升该成员的权限等级</gray>");
        if (canPromote) {
            promoteLore.add("<yellow>点击晋升</yellow>");
        } else {
            promoteLore.add("<red>已达最高可晋升等级</red>");
        }
        inv.setItem(11, GuiItem.of(canPromote ? Material.GREEN_WOOL : Material.GRAY_WOOL,
                "<!italic><green>⬆ 晋升", promoteLore));
        if (canPromote) {
            holder.bind(11, e -> dispatch(player, "is promote " + name));
        }

        // 槽 15：降级
        boolean canDemote = role != RoleType.MEMBER; // MEMBER 之下是 VISITOR，由 demote 命令处理
        List<String> demoteLore = new ArrayList<>();
        demoteLore.add("<dark_gray>─────────");
        demoteLore.add("<gray>降低该成员的权限等级</gray>");
        if (canDemote) {
            demoteLore.add("<yellow>点击降级</yellow>");
        } else {
            demoteLore.add("<red>已达最低等级</red>");
        }
        inv.setItem(15, GuiItem.of(canDemote ? Material.ORANGE_WOOL : Material.GRAY_WOOL,
                "<!italic><gold>⬇ 降级", demoteLore));
        if (canDemote) {
            holder.bind(15, e -> dispatch(player, "is demote " + name));
        }

        // 槽 20：踢出（危险）
        inv.setItem(20, GuiItem.danger(Material.IRON_DOOR, "踢出成员",
                List.of("<dark_gray>─────────",
                        "<gray>将该成员移出空岛</gray>",
                        "<red>⚠ 此操作不可逆</red>",
                        "<dark_gray>─────────",
                        "<yellow>点击</yellow> <red>二次确认</red>")));
        holder.bind(20, e -> ConfirmGui.open(player, "踢出 " + name,
                List.of("<red>你将把 " + name + " 踢出空岛",
                        "<red>该成员将失去所有权限"),
                () -> dispatch(player, "is kick " + name),
                () -> openMemberActionMenu(player, island, target)));

        // 槽 22：返回
        inv.setItem(22, GuiItem.back());
        holder.bind(22, e -> openMemberMenu(player, island));

        // 槽 24：转让岛主（危险）
        inv.setItem(24, GuiItem.danger(Material.GOLDEN_APPLE, "转让岛主",
                List.of("<dark_gray>─────────",
                        "<gray>将岛主转让给 " + name + "</gray>",
                        "<red>⚠ 你将失去岛主身份</red>",
                        "<red>⚠ 此操作不可逆</red>",
                        "<dark_gray>─────────",
                        "<yellow>点击</yellow> <red>二次确认</red>")));
        holder.bind(24, e -> ConfirmGui.open(player, "转让岛主给 " + name,
                List.of("<red>你将把岛主转让给 " + name,
                        "<red>你将降级为协管",
                        "<red>此操作完全不可逆"),
                () -> dispatch(player, "is transfer " + name + " confirm"),
                () -> openMemberActionMenu(player, island, target)));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  子菜单：访客管理
    // ═══════════════════════════════════════════════════════════

    /** 打开访客管理菜单：封禁/驱逐在线玩家 + 查看封禁列表 */
    private static void openVisitorMenu(@NotNull Player player, @NotNull Island island) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.VISITOR);
        Inventory inv = Bukkit.createInventory(holder, 54,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<light_purple>🚷 访客管理"));

        for (int i : FILLER_SLOTS) {
            inv.setItem(i, GuiItem.filler());
        }

        // 在线玩家（排除自己和成员）→ 封禁/驱逐
        int slot = 10;
        int col = 0;
        int shown = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId())) continue;
            if (shown >= 14) break; // 上半区最多 14 个

            String name = online.getName();
            // 跳过已是成员的玩家
            Players member = island.getMember(name);
            if (member != null && member.getRoleType() != RoleType.BAN) continue;

            boolean isBanned = member != null && member.getRoleType() == RoleType.BAN;

            List<String> headLore = new ArrayList<>();
            headLore.add("<dark_gray>─────────");
            if (isBanned) {
                headLore.add("<red>已封禁</red>");
                headLore.add("<yellow>点击解封</yellow>");
            } else {
                headLore.add("<gray>左键：</gray><red>封禁</red>");
                headLore.add("<gray>右键：</gray><gold>驱逐</gold>");
            }
            headLore.add("<dark_gray>─────────");

            inv.setItem(slot, GuiItem.playerHead(online.getUniqueId(),
                    "<!italic><light_purple>" + name, headLore));
            final String targetName = name;
            if (isBanned) {
                holder.bind(slot, e -> dispatch(player, "is unban " + targetName));
            } else {
                holder.bind(slot, e -> {
                    if (e.isRightClick()) {
                        dispatch(player, "is expel " + targetName);
                    } else {
                        ConfirmGui.open(player, "封禁 " + targetName,
                                List.of("<red>你将封禁 " + targetName,
                                        "<red>该玩家将无法进入你的空岛"),
                                () -> dispatch(player, "is ban " + targetName),
                                () -> openVisitorMenu(player, island));
                    }
                });
            }

            shown++;
            col++;
            slot++;
            if (col == 7) {
                slot += 2;
                col = 0;
            }
        }

        // 封禁列表按钮（槽 40）
        List<Players> bannedMembers = new ArrayList<>();
        for (Players m : island.getMembers()) {
            if (m.getRoleType() == RoleType.BAN) bannedMembers.add(m);
        }
        inv.setItem(40, GuiItem.of(Material.WRITTEN_BOOK,
                "<!italic><light_purple>📋 封禁列表",
                List.of("<dark_gray>─────────",
                        "<gray>已封禁玩家：" + bannedMembers.size() + " 人</gray>",
                        "<dark_gray>─────────",
                        "<yellow>点击查看</yellow>")));
        holder.bind(40, e -> dispatch(player, "is banlist"));

        // 返回按钮
        inv.setItem(49, GuiItem.back());
        holder.bind(49, e -> backToMain(player));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  子菜单：危险操作（离开/删除）
    // ═══════════════════════════════════════════════════════════

    /** 打开危险操作子菜单（离开/删除） */
    private static void openDangerMenu(@NotNull Player player, @NotNull Island island) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.MAIN);
        Inventory inv = Bukkit.createInventory(holder, 27,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<red>⚠ 危险操作"));

        // 边框（红色玻璃板）
        for (int i = 0; i < 27; i++) {
            if (i == 11 || i == 13 || i == 15 || i == 22) continue;
            inv.setItem(i, GuiItem.of(Material.RED_STAINED_GLASS_PANE, "<!italic><dark_gray> "));
        }

        boolean isOwner = island.getOwner() != null
                && island.getOwner().getMojangId().equals(player.getUniqueId());

        // 槽 11: 离开空岛
        List<String> leaveLore = new ArrayList<>();
        leaveLore.add("<dark_gray>─────────");
        leaveLore.add("<gray>离开当前空岛，失去成员身份。</gray>");
        if (isOwner) {
            leaveLore.add("<red>你是岛主，无法离开。</red>");
            leaveLore.add("<red>请先转让岛主或删除空岛。</red>");
        } else {
            leaveLore.add("<yellow>⚠ 此操作不可逆</yellow>");
            leaveLore.add("<dark_gray>─────────");
            leaveLore.add("<yellow>点击</yellow> <red>二次确认</red>");
        }
        inv.setItem(11, GuiItem.danger(Material.IRON_DOOR, "离开空岛", leaveLore));
        holder.bind(11, e -> {
            if (isOwner) {
                player.sendMessage(net.kyori.adventure.text.Component.text("§c你是岛主，无法离开空岛。"));
                return;
            }
            ConfirmGui.open(player, "离开空岛",
                    List.of("<red>你将离开 " + ownerName(island) + " 的空岛",
                            "<red>失去所有成员权限，不可恢复"),
                    () -> dispatch(player, "is leave confirm"),
                    () -> open(player));
        });

        // 槽 13: 警告标识
        inv.setItem(13, GuiItem.of(Material.WRITTEN_BOOK,
                "<!italic><red>⚠ 危险操作区",
                List.of("<dark_gray>以下操作不可逆",
                        "<dark_gray>请谨慎确认")));

        // 槽 15: 删除空岛（仅岛主）
        List<String> deleteLore = new ArrayList<>();
        deleteLore.add("<dark_gray>─────────");
        deleteLore.add("<gray>永久删除空岛，所有方块将被清除。</gray>");
        if (!isOwner) {
            deleteLore.add("<red>只有岛主可以删除空岛。</red>");
        } else {
            deleteLore.add("<red>⚠ 所有成员将被移除</red>");
            deleteLore.add("<red>⚠ 所有方块将丢失</red>");
            deleteLore.add("<dark_gray>─────────");
            deleteLore.add("<yellow>点击</yellow> <red>二次确认</red>");
        }
        inv.setItem(15, GuiItem.danger(Material.TNT, "删除空岛", deleteLore));
        holder.bind(15, e -> {
            if (!isOwner) {
                player.sendMessage(net.kyori.adventure.text.Component.text("§c只有岛主可以删除空岛。"));
                return;
            }
            ConfirmGui.open(player, "删除空岛",
                    List.of("<red>空岛将被永久删除",
                            "<red>所有方块、物品、成员将丢失",
                            "<red>此操作完全不可逆"),
                    () -> dispatch(player, "is delete confirm"),
                    () -> open(player));
        });

        // 槽 22: 返回
        inv.setItem(22, GuiItem.back());
        holder.bind(22, e -> backToMain(player));

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 执行一条 /is 子命令。
     * <p>
     * Folia 下 {@code performCommand} 必须在玩家所在 region 的 tick 线程执行，
     * 使用 {@link Player#getScheduler()} (EntityScheduler) 确保线程正确。
     * </p>
     */
    private static void dispatch(@NotNull Player player, @NotNull String command) {
        Consumer<ScheduledTask> task = t -> {
            player.closeInventory();
            player.performCommand(command);
        };
        // retired = 玩家下线时的回调（null = 不处理）
        player.getScheduler().run(Skyllia.getInstance(), task, null);
    }

    /** 检查玩家是否有空岛，无空岛时提示并关闭菜单 */
    private static boolean checkIsland(@NotNull Player player, boolean hasIsland) {
        if (!hasIsland) {
            // sendMessage 线程安全，closeInventory 需要在玩家 region 线程
            ConfigLoader.language.sendMessage(player, "island.player.no-island");
            player.getScheduler().run(Skyllia.getInstance(), t -> player.closeInventory(), null);
            return false;
        }
        return true;
    }

    /** 返回主菜单（直接 openInventory 替换，不 closeInventory 避免闪烁） */
    private static void backToMain(@NotNull Player player) {
        open(player);
    }

    /** 生成 lore：描述 + 操作提示（普通按钮） */
    private static @NotNull List<String> lore(@NotNull String desc,
                                              @NotNull String hint,
                                              boolean enabled) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>" + desc + "</gray>");
        lore.add("<dark_gray>─────────");
        if (enabled) {
            lore.add("<yellow>左键</yellow> <gray>" + hint + "</gray>");
        } else {
            lore.add("<dark_gray>需先创建空岛</dark_gray>");
        }
        return lore;
    }

    /** 生成 lore：带额外提示（如权限不足） */
    private static @NotNull List<String> lore(@NotNull String desc,
                                              @NotNull String hint,
                                              boolean enabled,
                                              @NotNull String extraHint) {
        List<String> lore = lore(desc, hint, enabled);
        if (!extraHint.isEmpty()) {
            lore.add(lore.size() - 1, "<dark_gray>" + extraHint + "</dark_gray>");
        }
        return lore;
    }

    /** 非岛主提示 */
    private static @NotNull String notOwnerHint(boolean hasIsland, boolean isOwner) {
        if (!hasIsland) return "";
        if (!isOwner) return "仅岛主可操作";
        return "";
    }

    /** 获取岛主名称 */
    private static @NotNull String ownerName(@NotNull Island island) {
        if (island.getOwner() == null) return "未知";
        String name = island.getOwner().getLastKnowName();
        return name == null || name.isEmpty() ? "未知" : name;
    }

    /** 安全获取成员名称 */
    private static @NotNull String safeName(@NotNull Players member) {
        String name = member.getLastKnowName();
        return (name == null || name.isEmpty()) ? "未知玩家" : name;
    }

    /** 角色名称（中文） */
    private static @NotNull String roleText(@NotNull RoleType role) {
        return switch (role) {
            case OWNER -> "<yellow>★ 岛主</yellow>";
            case CO_OWNER -> "<aqua>协管</aqua>";
            case MODERATOR -> "<light_purple>管理员</light_purple>";
            case MEMBER -> "<gray>成员</gray>";
            case VISITOR -> "<dark_gray>访客</dark_gray>";
            case BAN -> "<red>已封禁</red>";
        };
    }
}
