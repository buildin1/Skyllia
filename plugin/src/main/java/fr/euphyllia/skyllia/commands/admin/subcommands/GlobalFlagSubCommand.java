package fr.euphyllia.skyllia.commands.admin.subcommands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
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
 * {@code /skylliadmin flag <set|unset|list>} —— 管理岛屿标志的<b>全局接管层</b>。
 * <p>
 * 被接管的标志会直接决定全服所有岛屿（包括早已建好的老岛）的取值，改完立即生效：
 * 不需要修改数据库、不需要重启、也不需要让每个岛主自己去菜单里点一遍。
 * 没有被接管的标志则完全交给岛主通过 {@code /is flag} 自治。
 * </p>
 * <p>
 * 用法：
 * <pre>
 *   /skylliadmin flag list
 *   /skylliadmin flag set   island.spawn.passive.all true
 *   /skylliadmin flag set   island.spawn.hostile.phantom false
 *   /skylliadmin flag set   island.allow.fire false sky-nether
 *   /skylliadmin flag unset island.spawn.passive.all
 * </pre>
 * 标志名可以省略 {@code skyllia:} 前缀。末尾的世界名可选，省略即对所有世界生效。
 * </p>
 */
public class GlobalFlagSubCommand implements SubCommandInterface {

    public static final String PERMISSION = "skyllia.admins.commands.island.flag";

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
        List<String> lines = ConfigLoader.islandFlags.describeGlobalOverrides();
        if (lines.isEmpty()) {
            send(sender, "<gray>当前没有任何标志被全局接管，所有标志均由岛主自行控制。</gray>");
            return;
        }
        send(sender, "<light_purple>全局接管中的标志（共 " + lines.size() + " 条）：</light_purple>");
        for (String line : lines) {
            send(sender, "<gray>  • </gray><white>" + line + "</white>");
        }
    }

    private void set(CommandSender sender, String[] args) {
        // set <flag> <true|false> [world]
        if (args.length < 3) {
            send(sender, "<red>用法：/skylliadmin flag set &lt;标志&gt; &lt;true|false&gt; [世界名]</red>");
            return;
        }

        NamespacedKey key = parseKey(args[1]);
        if (key == null) {
            send(sender, "<red>标志名格式不正确：</red><white>" + args[1] + "</white>");
            return;
        }

        Boolean value = parseBoolean(args[2]);
        if (value == null) {
            send(sender, "<red>取值必须是 true 或 false，收到：</red><white>" + args[2] + "</white>");
            return;
        }

        String worldName = args.length >= 4 ? args[3] : null;
        if (worldName != null && !isRegisteredWorld(worldName)) {
            send(sender, "<red>未知的空岛世界：</red><white>" + worldName + "</white>"
                    + "<gray>（可用：" + String.join(", ", registeredWorldNames()) + "）</gray>");
            return;
        }

        if (!ConfigLoader.islandFlags.setGlobalOverride(key, worldName, value)) {
            send(sender, "<red>未知标志：</red><white>" + key + "</white>"
                    + "<gray> —— 请检查拼写，或确认提供该标志的附属插件已安装。</gray>");
            return;
        }

        String scope = worldName == null ? "所有世界" : worldName;
        send(sender, "<green>✔ 已全局接管 </green><white>" + key + "</white>"
                + "<gray> → </gray>" + (value ? "<green>true</green>" : "<red>false</red>")
                + "<gray>（作用域：" + scope + "）</gray>");
        send(sender, "<gray>已对全服所有岛屿立即生效，无需重启。</gray>");
    }

    private void unset(CommandSender sender, String[] args) {
        // unset <flag> [world]
        if (args.length < 2) {
            send(sender, "<red>用法：/skylliadmin flag unset &lt;标志&gt; [世界名]</red>");
            return;
        }

        NamespacedKey key = parseKey(args[1]);
        if (key == null) {
            send(sender, "<red>标志名格式不正确：</red><white>" + args[1] + "</white>");
            return;
        }

        String worldName = args.length >= 3 ? args[2] : null;

        if (!ConfigLoader.islandFlags.setGlobalOverride(key, worldName, null)) {
            send(sender, "<red>未知标志：</red><white>" + key + "</white>");
            return;
        }

        String scope = worldName == null ? "所有世界" : worldName;
        send(sender, "<green>✔ 已撤销对 </green><white>" + key + "</white>"
                + "<green> 的全局接管</green><gray>（作用域：" + scope + "），该标志重新由各岛岛主自行控制。</gray>");
    }

    private void usage(CommandSender sender) {
        send(sender, "<light_purple>标志全局接管</light_purple><gray> —— 被接管的标志立即对全服所有岛屿生效</gray>");
        send(sender, "<yellow>/skylliadmin flag list</yellow><gray> 查看当前接管了哪些标志</gray>");
        send(sender, "<yellow>/skylliadmin flag set &lt;标志&gt; &lt;true|false&gt; [世界名]</yellow>");
        send(sender, "<yellow>/skylliadmin flag unset &lt;标志&gt; [世界名]</yellow><gray> 交还岛主自治</gray>");
        send(sender, "<gray>例：</gray><white>/skylliadmin flag set island.spawn.passive.all true</white><gray> 全服放开动物生成</gray>");
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

    private static List<String> registeredWorldNames() {
        List<String> names = new ArrayList<>();
        for (WorldConfig wc : SkylliaAPI.getRegisteredWorlds()) names.add(wc.getWorldName());
        return names;
    }

    private static boolean isRegisteredWorld(String name) {
        for (String n : registeredWorldNames()) {
            if (n.equalsIgnoreCase(name)) return true;
        }
        return false;
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

        if (args.length == 2 && (action.equals("set") || action.equals("unset") || action.equals("remove"))) {
            List<String> keys = new ArrayList<>();
            for (NamespacedKey k : SkylliaAPI.getFlagRegistry().keys()) {
                // 绝大多数标志都在 skyllia 命名空间下，补全时省掉前缀更好打
                keys.add(DEFAULT_NAMESPACE.equals(k.getNamespace()) ? k.getKey() : k.toString());
            }
            keys.sort(String::compareTo);
            StringUtil.copyPartialMatches(args[1], keys, matches);
            return matches;
        }

        if (args.length == 3 && action.equals("set")) {
            StringUtil.copyPartialMatches(args[2], List.of("true", "false"), matches);
            return matches;
        }

        boolean worldSlot = (args.length == 4 && action.equals("set"))
                || (args.length == 3 && (action.equals("unset") || action.equals("remove")));
        if (worldSlot) {
            StringUtil.copyPartialMatches(args[args.length - 1], registeredWorldNames(), matches);
            return matches;
        }

        return matches;
    }

    @Override
    public String permission() {
        return PERMISSION;
    }
}
