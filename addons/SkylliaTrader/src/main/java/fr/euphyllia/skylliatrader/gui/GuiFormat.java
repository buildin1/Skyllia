package fr.euphyllia.skylliatrader.gui;

import fr.euphyllia.skylliatrader.configuration.model.ItemAmount;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.Locale;

/**
 * GUI 文案里的小格式化工具。
 * <p>
 * "金额/数量去掉多余小数"：订单价格、累计消费这些字段在 TOML 里是
 * {@code double}，直接拼字符串会显示成 {@code 320.0 金币}，玩家一眼就觉得界面没做完。
 * 进度指南和管理端订单列表/编辑页都要用，所以提到这里共用，避免各写一份。
 * </p>
 */
public final class GuiFormat {

    private GuiFormat() {
    }

    /** 整数值去掉 {@code .0}，非整数保留两位小数。 */
    public static String fmt(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * 把配置里的 MiniMessage 显示名转成<b>聊天消息能正确显示的</b>老式 {@code §} 着色文本。
     * <p>
     * shop.toml / orders.toml 里的 {@code display-name} 一律是 MiniMessage 格式
     * （{@code "<white>沙子"}、{@code "<gold>原木收购"}）。这类字符串<b>只有</b>经过
     * {@code MiniMessage.deserialize} 才会变成颜色；而本模块给玩家发聊天消息用的是
     * {@code Component.text("§a…" + displayName + "…")} 这种<b>老式 § 拼接</b>的写法——
     * {@code Component.text} 不解析任何标签，于是玩家看到的就是字面量
     * {@code <white>沙子}（2026-08-21 服主实测反馈：购买提示里出现 {@code <white>xxx}）。
     * </p>
     * <p>
     * 这里做一次「MiniMessage → Component → 老式 § 文本」的转换，让显示名能安全地拼进
     * 那些老式消息里，颜色也不会丢。<b>凡是把配置显示名塞进 {@code Component.text} 的地方
     * 都必须过一遍这个方法</b>；反过来，塞进 GUI lore / 物品名（那些最终会走
     * {@code MiniMessage.deserialize}）的地方<b>不要</b>用它，直接用原串。
     * </p>
     */
    public static String legacyName(String miniMessageName) {
        if (miniMessageName == null || miniMessageName.isEmpty()) return "";
        try {
            return LegacyComponentSerializer.legacySection()
                    .serialize(MiniMessage.miniMessage().deserialize(miniMessageName));
        } catch (Exception e) {
            // 配置里写了非法标签时不能让一条聊天消息把整个购买流程炸掉：
            // 退化成原样返回，最坏结果只是这一条消息里露出标签，交易本身不受影响。
            return miniMessageName;
        }
    }

    /** 把一组 {@code material x amount} 拼成一行人类可读的描述，空列表显示"（无）"。 */
    public static String describeItems(List<ItemAmount> items) {
        if (items.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            ItemAmount item = items.get(i);
            sb.append(item.material()).append(" x").append(item.amount());
        }
        return sb.toString();
    }
}
