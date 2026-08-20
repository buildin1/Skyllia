package fr.euphyllia.skyllia.gui;

import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * 54 格「分页列表」菜单的公共版式：边框槽位、内容槽位、翻页数学。
 * <p>
 * 这套版式原本在 SkylliaIslandValue 的 {@code ScoreDetailGui} 和 SkylliaTrader 的
 * {@code TraderOrderListGui} 里各抄了一份一模一样的实现（连 {@code buildContentSlots()}
 * 的循环都一字不差），改一处忘另一处只是时间问题，所以提取到核心供各附属插件共用。
 * </p>
 * <p>
 * 版式约定（槽位下标 0-based，共 54 格）：
 * </p>
 * <ul>
 *   <li>第 0 行：0-8 是边框，其中 <b>4 号槽留给调用方</b>放"总览/标题物品"；</li>
 *   <li>第 1-4 行：每行左右各一格边框，中间 7 格是内容区，共 {@link #PAGE_SIZE} = 28 格；</li>
 *   <li>第 5 行：45 留给"上一页"、53 留给"下一页"、49 留给"关闭/返回"，
 *       48 属于边框但调用方可以在填完边框之后覆盖它放自定义功能键
 *       （{@code ScoreDetailGui} 的"重新扫描"就摆在这里）。</li>
 * </ul>
 * <p>
 * 注意：4 / 45 / 49 / 53 都<b>不在</b>边框数组里，调用方无论放不放东西那几格都是空的；
 * 48 在边框数组里，不覆盖时显示填充物，不会留洞。
 * </p>
 */
public final class GuiPageLayout {

    /** 一页能摆多少个内容物品。 */
    public static final int PAGE_SIZE = 28;

    /** 上一页 / 下一页 / 关闭（或返回）的约定槽位，供调用方直接引用，避免各处写魔数。 */
    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_NEXT_PAGE = 53;
    public static final int SLOT_CLOSE = 49;
    /** 顶部居中的"总览物品"槽位。 */
    public static final int SLOT_HEADER = 4;

    private static final int[] BORDER = {
            0, 1, 2, 3, 5, 6, 7, 8,
            9, 17,
            18, 26,
            27, 35,
            36, 44,
            46, 47, 48, 50, 51, 52
    };

    private static final int[] CONTENT_SLOTS = buildContentSlots();

    private GuiPageLayout() {
    }

    private static int[] buildContentSlots() {
        int[] slots = new int[PAGE_SIZE];
        int slot = 10;
        int col = 0;
        int idx = 0;
        while (idx < PAGE_SIZE) {
            slots[idx++] = slot;
            col++;
            slot++;
            if (col == 7) {
                // 跳过本行右侧边框与下一行左侧边框这两格
                slot += 2;
                col = 0;
            }
        }
        return slots;
    }

    /** 把边框槽位全部填上默认填充物；调用方之后可以覆盖 48 号槽放自定义按钮。 */
    public static void fillBorder(@NotNull Inventory inventory) {
        for (int slot : BORDER) {
            inventory.setItem(slot, GuiItem.filler());
        }
    }

    /** 取第 {@code index} 个内容位（0 到 {@link #PAGE_SIZE}-1）对应的物品栏槽位。 */
    public static int contentSlot(int index) {
        return CONTENT_SLOTS[index];
    }

    /** 共 {@code itemCount} 个条目时的总页数；空列表也算 1 页（要展示"暂无数据"）。 */
    public static int totalPages(int itemCount) {
        return Math.max(1, (int) Math.ceil(itemCount / (double) PAGE_SIZE));
    }

    /** 把外部传进来的页码夹到 {@code [0, totalPages-1]}，防止越界翻页。 */
    public static int clampPage(int page, int totalPages) {
        return Math.max(0, Math.min(page, totalPages - 1));
    }
}
