package fr.euphyllia.skylliatrader.configuration.model;

import org.bukkit.Material;

/**
 * 一组"材料 + 数量"，用于 barter 订单的 give-items / take-items 字段。
 * 材料字段用标准 Bukkit {@link Material} 名，方便以后跟
 * SkylliaIslandValue 的计分表联动（该表也是按 Material 枚举名配置的）。
 */
public record ItemAmount(Material material, int amount) {

    public ItemAmount {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount 必须大于 0");
        }
    }
}
