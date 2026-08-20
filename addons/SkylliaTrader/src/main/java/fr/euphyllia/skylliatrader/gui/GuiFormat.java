package fr.euphyllia.skylliatrader.gui;

import java.util.Locale;

/**
 * GUI 文案里的小格式化工具。
 * <p>
 * 目前只有一个"金额/数量去掉多余小数"的方法：订单价格、累计消费这些字段在 TOML 里是
 * {@code double}，直接拼字符串会显示成 {@code 320.0 金币}，玩家一眼就觉得界面没做完。
 * 进度指南和管理端订单列表两边都要用，所以提到这里共用，避免各写一份。
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
}
