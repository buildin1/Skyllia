package fr.euphyllia.skylliatrader.configuration.model;

/**
 * 商队订单的交易类型。
 */
public enum OrderType {

    /** 岛屿把 take-items 交给商队，商队支付 price 指定的 Vault 金币（可选附带声望奖励）。 */
    MONEY,

    /** 纯物物交换：岛屿把 take-items 交给商队，商队把 give-items 给岛屿，不涉及金币。 */
    BARTER;

    /**
     * 从配置字符串解析类型，<b>无法识别时返回 {@code null}</b>，由调用方决定怎么报错。
     * <p>
     * 这里曾经是"未知值一律降级成 {@link #MONEY}"：管理员把 {@code type = "barter"} 手滑
     * 写成 {@code "trade"}，订单会被当成 money 解析，然后因为缺字段抛异常，日志上只留下
     * 一句"第 N 条解析失败"，完全看不出真正原因是 type 拼错了。宁可明确返回 null 让调用方
     * 打出"type='trade' 无法识别，可选值：money / barter"，也不要静默降级。
     * </p>
     */
    public static OrderType parseOrNull(String raw) {
        if (raw == null) return MONEY;
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "barter" -> BARTER;
            case "money" -> MONEY;
            default -> null;
        };
    }
}
