package fr.euphyllia.skylliatrader.shop;

import fr.euphyllia.skylliatrader.configuration.model.GuidebookConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 按配置把「游商指南」说明书组装成一个物品。
 *
 * <h2>⚠️ T2 的边界</h2>
 * <p>
 * <b>T2 没有购买入口</b>——商店 GUI 与扣款发货事务是 T3 的范围（见 {@link GuidebookConfig} 的说明）。
 * 本类在 T2 阶段的唯一用途是 {@code /skylliadmin trader guidebook}：让服主拿一本样品出来校对文案，
 * 改完 {@code /skyllia reload} 立刻能看到效果，不用等 T3 上线才知道排版好不好看。
 * </p>
 * <p>
 * T3 接入时，商店条目直接调 {@link #build} 拿这个 ItemStack 发给玩家即可，不需要再写一遍。
 * </p>
 */
public final class GuidebookItem {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private GuidebookItem() {
    }

    /**
     * 组装说明书。
     * <p>
     * 只有 {@code WRITTEN_BOOK} 才有 {@link BookMeta}，能翻页显示多页文案；服主如果把
     * {@code guidebook.material} 换成别的材质，这里会<b>降级成「名字 + lore」</b>而不是报错——
     * 内容还在，只是要靠鼠标悬停看。降级比抛异常好：一个材质写错不该让整套配置加载失败。
     * </p>
     */
    public static @NotNull ItemStack build(@NotNull GuidebookConfig config) {
        ItemStack item = new ItemStack(config.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        Component title = MM.deserialize("<!italic>" + config.title());

        if (meta instanceof BookMeta bookMeta) {
            // 成书的书名栏不吃富文本样式，但 Adventure 的 API 就是收 Component，
            // 传进去客户端会自己把样式抹掉，不需要额外处理。
            bookMeta.title(title);
            bookMeta.author(Component.text(config.author()));
            List<Component> pages = new ArrayList<>(config.pages().size());
            for (String page : config.pages()) {
                pages.add(MM.deserialize(page));
            }
            // ⚠️ 成书最多 100 页，超出的部分 CraftMetaBook#addPages 会<b>静默 return</b>——
            // 不抛异常、不打日志，所以这里传多少页都不会崩服，只会悄悄少几页。
            // 页数校验放在配置加载时做（TraderConfigManager#loadGuidebook 会打 warn），
            // 那里才是服主能看到反馈的地方；这里不再重复判断，也不截断。
            bookMeta.pages(pages);
        } else {
            meta.displayName(title);
            List<Component> lore = new ArrayList<>();
            for (String page : config.pages()) {
                // 非成书材质没有翻页，把每一页拍平成 lore；换行符要拆开，
                // 否则 lore 里会出现一行里带 \n 的怪东西。
                for (String line : page.split("\n")) {
                    lore.add(MM.deserialize("<!italic><gray>" + line));
                }
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }
}
