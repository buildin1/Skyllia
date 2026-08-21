package fr.euphyllia.skyllia.gui.layout;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各菜单的内置默认外观。
 *
 * <p>这里是唯一的事实来源：{@link GuiLayoutConfigManager} 首次加载时把它写进 toml，
 * 之后任何一项缺失或配错也回退到这里。想改默认长相就改这里，想让服主能改就确保
 * 对应字段出现在这里。</p>
 */
public final class GuiLayoutDefaults {

    private GuiLayoutDefaults() {}

    private static GuiButtonDef btn(int slot, Material icon, String name, String desc, String hint) {
        return new GuiButtonDef(slot, icon, name, desc, hint, null);
    }

    /**
     * 主菜单 {@code gui/main.toml}。
     * <p>
     * 内容区固定在 x2y2~x8y5（0-indexed 槽位 10-16 / 19-25 / 28-34 / 37-43）这个矩形内，
     * 第 1 行（0-8）纯装饰，不放任何功能按钮；第 6 行（45-53）是底部操作栏。
     * 按分组占用各行（同一行内允许留白，不要求塞满 7 格）：
     * </p>
     * <pre>
     * y2（信息）      :        x4=info  x5=home  x6=tps
     * y3（位置设置）  :        x4=set-home  x5=set-spawn  x6=set-visit
     * y4（空岛设置）  :        x4=access  x5=name-reset  x6=desc-reset
     * y5（管理）      : x3=permission x4=flag x5=visit x6=member x7=visitor
     * y6（操作栏）    : x3=danger  x5=close  x7=next-page
     * </pre>
     */
    public static @NotNull GuiLayout main() {
        Map<String, GuiButtonDef> b = new LinkedHashMap<>();
        // y2：信息（info/home 放在最显眼的第一行前两格）
        b.put("info", btn(12, Material.ENDER_CHEST,
                "<!italic><light_purple>📋 空岛信息", "查看空岛详细信息", "点击查看"));
        b.put("home", btn(13, Material.RED_BED,
                "<!italic><light_purple>🏠 回到空岛", "传送回你的空岛", "点击传送"));
        b.put("tps", btn(14, Material.CLOCK,
                "<!italic><light_purple>📊 服务器 TPS", "查看服务器运行状态", "点击查看"));
        // y3：位置设置
        b.put("set-home", btn(21, Material.GRASS_BLOCK,
                "<!italic><light_purple>📍 设置家园点", "将当前位置设为空岛家园", "点击设置（需在空岛上）"));
        b.put("set-spawn", btn(22, Material.RESPAWN_ANCHOR,
                "<!italic><light_purple>⚛ 设置出生点", "将当前位置设为空岛出生点", "点击设置（需在空岛上）"));
        b.put("set-visit", btn(23, Material.COMPASS,
                "<!italic><light_purple>🧭 设置访问点", "访客访问空岛时的到达点", "点击设置（需在空岛上）"));
        // y4：空岛设置
        b.put("access", btn(30, Material.IRON_DOOR,
                "<!italic><light_purple>🔓 开放/私密切换", "切换空岛是否允许访客进入", "点击切换"));
        b.put("name-reset", btn(31, Material.NAME_TAG,
                "<!italic><light_purple>📛 重置空岛名称", "清除自定义名称，恢复默认", "点击重置"));
        b.put("desc-reset", btn(32, Material.WRITABLE_BOOK,
                "<!italic><light_purple>📝 重置空岛描述", "清除自定义描述", "点击重置"));
        // y5：管理（权限/标记/访问/成员/访客）
        b.put("permission", btn(38, Material.GOLD_NUGGET,
                "<!italic><light_purple>⚙ 权限管理", "管理各角色的操作权限", "点击打开"));
        b.put("flag", btn(39, Material.REDSTONE_TORCH,
                "<!italic><light_purple>🚩 空岛标记", "管理生物生成等空岛规则", "点击打开"));
        b.put("visit", btn(40, Material.OAK_DOOR,
                "<!italic><light_purple>🌐 访问其他空岛", "选择在线玩家前往其空岛", "点击打开列表"));
        b.put("member", btn(41, Material.GOLDEN_APPLE,
                "<!italic><light_purple>🔑 成员管理", "晋升/降级/踢出/转让", "点击打开成员列表"));
        b.put("visitor", btn(42, Material.IRON_DOOR,
                "<!italic><light_purple>🚷 访客管理", "封禁/驱逐/解封访客", "点击打开管理列表"));
        // y6：底部操作栏
        b.put("danger", new GuiButtonDef(47, Material.TNT,
                "<!italic><red>⚠ 危险操作", "", "", List.of(
                "<dark_gray>─────────",
                "<gray>包含以下操作：</gray>",
                "<red>• 离开空岛</red>",
                "<red>• 删除空岛</red>",
                "<dark_gray>─────────",
                "<yellow>⚠ 需要二次确认</yellow>")));
        b.put("close", new GuiButtonDef(49, Material.BARRIER,
                "<!italic><red>✖ 关闭", "", "", List.of("<dark_gray>点击关闭菜单")));
        // 原「扩展功能」子菜单入口改为「下一页」，key 从 extension 改名为 next-page——
        // 存量服务器 gui/main.toml 里残留的 extension 键会变成孤儿键，自然被忽略
        // （GuiLayoutConfigManager 只按 defaults 的 key 集合读取），无需迁移。
        b.put("next-page", new GuiButtonDef(51, Material.NETHER_STAR,
                "<!italic><light_purple>下一页 ▶", "", "", List.of(
                "<dark_gray>─────────",
                "<gray>扩展功能 / 加入其他岛屿 / 群系修改</gray>",
                "<dark_gray>─────────",
                "<yellow>点击翻页</yellow>")));

        return new GuiLayout("main",
                "<light_purple>空岛管理",
                54,
                Material.PURPLE_STAINED_GLASS_PANE,
                "<!italic><dark_gray> ",
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8,
                        9, 17, 18, 26, 27, 35, 36, 44,
                        45, 46, 48, 50, 52, 53),
                b,
                GuiLayout.LoreTemplate.defaults());
    }
}
