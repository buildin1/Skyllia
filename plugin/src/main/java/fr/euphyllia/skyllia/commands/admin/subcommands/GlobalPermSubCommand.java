package fr.euphyllia.skyllia.commands.admin.subcommands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /skylliadmin perm <set|unset|list>} —— 管理岛屿角色权限的<b>全局接管层</b>。
 * <p>
 * 与 {@link GlobalFlagSubCommand} 同理，但作用在「角色 × 权限」维度上。被接管的权限会
 * 直接决定全服所有岛屿上该角色的判定结果，改完立即生效，不碰数据库、不用重启。
 * </p>
 * <p>
 * 这是修复「老岛权限位图全零」最省事的手段：那些岛屿建立时把每个角色的权限都写成了 0，
 * 单改配置文件对它们无效、只影响新岛；而全局接管层是在判定时实时叠加的，对新老岛一视同仁。
 * </p>
 * <p>
 * 用法：
 * <pre>
 *   /skylliadmin perm list
 *   /skylliadmin perm set   MEMBER block.break true
 *   /skylliadmin perm set   VISITOR player.teleport true
 *   /skylliadmin perm unset MEMBER block.break
 * </pre>
 * 权限名可以省略 {@code skyllia:} 前缀。
 * </p>
 * <p>
 * 注意：岛主（OWNER）的判定在接管层<b>之前</b>就已短路放行（取决于
 * {@code permissions.check-owner} 配置），所以接管 OWNER 通常没有意义。
 * </p>
 */
public class GlobalPermSubCommand implements SubCommandInterface {

    public static final String PERMISSION = "skyllia.admins.commands.island.permission";

    private static final String DEFAULT_NAMESPACE = "skyllia";

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, permission())) {
            ConfigLoader.language.sendMessage(sender, "island.player.permission-denied");
            return;
        }

        if (args.length == 0) {
            usage(sender);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "set" -> set(sender, args);
            case "unset", "remove" -> unset(sender, args);
            default -> usage(sender);
        }
    }

    private void list(CommandSender sender) {
        List<String> lines = ConfigLoader.permissionsV2.describeGlobalOverrides();
        if (lines.isEmpty()) {
            send(sender, "<gray>当前没有任何权限被全局接管，所有权限均由各岛岛主自行控制。</gray>");
            return;
        }
        send(sender, "<light_purple>全局接管中的权限（共 " + lines.size() + " 条）：</light_purple>");
        for (String line : lines) {
            send(sender, "<gray>  • </gray><white>" + line + "</white>");
        }
    }

    private void set(CommandSender sender, String[] args) {
        // set <role> <permission> <true|false>
        if (args.length < 4) {
            send(sender, "<red>用法：/skylliadmin perm set &lt;角色&gt; &lt;权限&gt; &lt;true|false&gt;</red>");
            return;
        }

        RoleType role = parseRole(args[1]);
        if (role == null) {
            send(sender, "<red>未知角色：</red><white>" + args[1] + "</white>"
                    + "<gray>（可用：" + String.join(", ", roleNames()) + "）</gray>");
            return;
        }

        NamespacedKey key = parseKey(args[2]);
        if (key == null) {
            send(sender, "<red>权限名格式不正确：</red><white>" + args[2] + "</white>");
            return;
        }

        Boolean value = parseBoolean(args[3]);
        if (value == null) {
            send(sender, "<red>取值必须是 true 或 false，收到：</red><white>" + args[3] + "</white>");
            return;
        }

        if (!ConfigLoader.permissionsV2.setGlobalOverride(role, key, value)) {
            send(sender, "<red>未知权限：</red><white>" + key + "</white>"
                    + "<gray> —— 请检查拼写，或确认提供该权限的附属插件已安装。</gray>");
            return;
        }

        send(sender, "<green>✔ 已全局接管 </green><white>[" + role.name() + "] " + key + "</white>"
                + "<gray> → </gray>" + (value ? "<green>true</green>" : "<red>false</red>"));
        send(sender, "<gray>已对全服所有岛屿立即生效，无需重启。</gray>");

        if (role == RoleType.OWNER && !ConfigLoader.general.getPermissionsSettings().checkOwner()) {
            send(sender, "<yellow>提示：当前 permissions.check-owner = false，岛主的判定会在接管层之前直接放行，"
                    + "因此这条接管对岛主不会产生任何效果。</yellow>");
        }
    }

    private void unset(CommandSender sender, String[] args) {
        // unset <role> <permission>
        if (args.length < 3) {
            send(sender, "<red>用法：/skylliadmin perm unset &lt;角色&gt; &lt;权限&gt;</red>");
            return;
        }

        RoleType role = parseRole(args[1]);
        if (role == null) {
            send(sender, "<red>未知角色：</red><white>" + args[1] + "</white>");
            return;
        }

        NamespacedKey key = parseKey(args[2]);
        if (key == null) {
            send(sender, "<red>权限名格式不正确：</red><white>" + args[2] + "</white>");
            return;
        }

        if (!ConfigLoader.permissionsV2.setGlobalOverride(role, key, null)) {
            send(sender, "<red>未知权限：</red><white>" + key + "</white>");
            return;
        }

        send(sender, "<green>✔ 已撤销对 </green><white>[" + role.name() + "] " + key + "</white>"
                + "<green> 的全局接管</green><gray>，该权限重新由各岛岛主自行控制。</gray>");
    }

    private void usage(CommandSender sender) {
        send(sender, "<light_purple>权限全局接管</light_purple><gray> —— 被接管的权限立即对全服所有岛屿生效</gray>");
        send(sender, "<yellow>/skylliadmin perm list</yellow><gray> 查看当前接管了哪些权限</gray>");
        send(sender, "<yellow>/skylliadmin perm set &lt;角色&gt; &lt;权限&gt; &lt;true|false&gt;</yellow>");
        send(sender, "<yellow>/skylliadmin perm unset &lt;角色&gt; &lt;权限&gt;</yellow><gray> 交还岛主自治</gray>");
        send(sender, "<gray>例：</gray><white>/skylliadmin perm set MEMBER inventory.open true</white><gray> 全服成员可开容器</gray>");
    }

    private static @Nullable RoleType parseRole(String raw) {
        try {
            return RoleType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> roleNames() {
        List<String> names = new ArrayList<>();
        for (RoleType r : RoleType.values()) names.add(r.name());
        return names;
    }

    private static @Nullable NamespacedKey parseKey(String raw) {
        String input = raw.trim();
        if (input.isEmpty()) return null;
        try {
            int idx = input.indexOf(':');
            if (idx < 0) return new NamespacedKey(DEFAULT_NAMESPACE, input.toLowerCase(Locale.ROOT));
            if (idx == 0 || idx == input.length() - 1) return null;
            return new NamespacedKey(input.substring(0, idx).toLowerCase(Locale.ROOT),
                    input.substring(idx + 1).toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static @Nullable Boolean parseBoolean(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("on") || v.equals("yes")) return true;
        if (v.equals("false") || v.equals("off") || v.equals("no")) return false;
        return null;
    }

    private static void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        List<String> matches = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("list", "set", "unset"), matches);
            return matches;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        boolean editing = action.equals("set") || action.equals("unset") || action.equals("remove");

        if (args.length == 2 && editing) {
            StringUtil.copyPartialMatches(args[1], roleNames(), matches);
            return matches;
        }

        if (args.length == 3 && editing) {
            List<String> keys = new ArrayList<>();
            for (NamespacedKey k : SkylliaAPI.getPermissionRegistry().keys()) {
                keys.add(DEFAULT_NAMESPACE.equals(k.getNamespace()) ? k.getKey() : k.toString());
            }
            keys.sort(String::compareTo);
            StringUtil.copyPartialMatches(args[2], keys, matches);
            return matches;
        }

        if (args.length == 4 && action.equals("set")) {
            StringUtil.copyPartialMatches(args[3], List.of("true", "false"), matches);
            return matches;
        }

        return matches;
    }

    @Override
    public String permission() {
        return PERMISSION;
    }
}
