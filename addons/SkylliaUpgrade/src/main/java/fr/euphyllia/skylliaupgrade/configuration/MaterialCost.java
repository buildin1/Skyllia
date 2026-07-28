package fr.euphyllia.skylliaupgrade.configuration;

import org.bukkit.Material;

import java.util.Locale;

/** 一项材料上交要求，序列化为 "MATERIAL_NAME:amount" 存进 TOML。 */
public record MaterialCost(Material material, int amount) {

    public static MaterialCost parse(String raw) {
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("格式应为 MATERIAL:amount，得到 '" + raw + "'");
        Material material = Material.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
        int amount = Integer.parseInt(parts[1].trim());
        if (amount <= 0) throw new IllegalArgumentException("数量必须为正数");
        return new MaterialCost(material, amount);
    }

    public String serialize() {
        return material.name() + ":" + amount;
    }
}
