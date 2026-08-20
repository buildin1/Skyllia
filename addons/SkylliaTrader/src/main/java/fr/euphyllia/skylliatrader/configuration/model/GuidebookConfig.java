package fr.euphyllia.skylliatrader.configuration.model;

import org.bukkit.Material;

import java.util.List;

/**
 * 「游商指南」说明书的配置（config.toml 的 {@code [guidebook]}）。
 *
 * <h2>它是干什么的</h2>
 * <p>
 * 自然刷新的游商<b>只开放交易次数轨的基础档</b>（见 {@link MerchantOfferScope}），
 * 除此之外额外上架这一本说明书：告诉新玩家凭证从哪来、怎么召唤进阶商队、四条轨道各自怎么涨、
 * 分别能解锁什么。这套系统的解锁维度有四条，玩家不看说明根本猜不出来——
 * 说明书就是防「不知道怎么玩」的兜底。
 * </p>
 * <p>
 * <b>整本书的文案都在配置里</b>（书名、作者、每一页），服主改文案不需要动代码、不需要重新编译，
 * {@code /skyllia reload} 就能生效。这是刻意的：这本书的内容一定会随着后续阶段上线而反复修订。
 * </p>
 *
 * <h2>⚠️ T2 的边界</h2>
 * <p>
 * <b>T2 只落地「说明书作为一个商品条目存在」的数据与配置结构，没有购买入口。</b>
 * 商店购买 GUI 与扣款发货事务是 T3 的范围，在那之前玩家没有任何途径买到这本书——
 * 这是已知且刻意的，不要为了让它能买而把 T3 的事务逻辑提前写进来（先扣钱后发货、
 * 失败退款、inFlight 防双击那一整套必须和商店 GUI 一起做，拆开做只会做出一个刷物品漏洞）。
 * T2 阶段管理员可以用 {@code /skylliadmin trader guidebook} 拿一本样品校对文案。
 * </p>
 *
 * @param enabled       是否上架这本说明书
 * @param material      书的材质，默认 {@code WRITTEN_BOOK}（成书）。留这个开关是因为服主可能想换成
 *                      {@code KNOWLEDGE_BOOK} 之类；但只有成书能翻页显示多页文案
 * @param title         书名（MiniMessage）
 * @param author        作者名（纯文本；成书的作者栏不吃富文本）
 * @param price         售价（Vault 金币）。<b>刻意压得很低</b>——它是新手引导，不是收入来源，
 *                      买不起说明书的新手正是最需要它的人
 * @param purchaseLimit 限购次数，{@code 0} = 不限购
 * @param pages         每一页的正文（MiniMessage）。一页放不下会被客户端截断，写的时候自己分页
 */
public record GuidebookConfig(
        boolean enabled,
        Material material,
        String title,
        String author,
        double price,
        int purchaseLimit,
        List<String> pages
) {

    public GuidebookConfig {
        pages = List.copyOf(pages);
    }
}
