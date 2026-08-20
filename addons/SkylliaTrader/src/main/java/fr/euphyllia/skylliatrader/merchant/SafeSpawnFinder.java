package fr.euphyllia.skylliatrader.merchant;

import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 虚空世界里的「安全落点」搜索。
 *
 * <h2>⚠️ 为什么绝对不能用 {@code World#getHighestBlockYAt}</h2>
 * <p>
 * 空岛世界（以及本服的下界/末地岛世界）是<b>虚空</b>：绝大多数 XZ 列上一个方块都没有。
 * {@code getHighestBlockYAt} 在这种列上返回的是世界的 <b>minY</b>（-64），
 * 拿它当生成点 = 游商在 y=-64 凭空出现，下一 tick 就开始自由落体，几秒后
 * {@code OUT_OF_WORLD} 消失。玩家看到的现象是「用了凭证，商人没了，凭证也没了」。
 * </p>
 * <p>
 * 正确做法是以 {@link Island#getSpawnLocation(World)} 为基准做<b>上下有限范围</b>的扫描：
 * 岛屿 spawn 一定在玩家踩得到的地面附近，从它出发找脚下有实心方块、头上有净空的位置，
 * 找不到就放弃——<b>放弃比乱生成好</b>，见 {@link #find} 的返回值约定。
 * </p>
 *
 * <h2>线程约束（Folia）</h2>
 * <p>
 * {@link #find} 会读方块，<b>必须在拥有该区域的 region 线程上调用</b>。
 * 调用方的正确写法是 {@code Bukkit.getRegionScheduler().execute(plugin, baseLocation, () -> ...)}。
 * 扫描半径可能让候选点跨出基准点所在的区块甚至区域，所以每个候选点都会先用
 * {@link Bukkit#isOwnedByCurrentRegion(World, int, int)} 校验一次，<b>不属于当前区域的候选点直接跳过</b>
 * ——跨区域读方块在 Folia 上是会抛异常的硬错误，不是「读到脏数据」这种可以将就的问题。
 * 这同时也顺带保证了只在<b>已加载</b>的区块上找点，不会触发同步加载区块。
 * </p>
 */
public final class SafeSpawnFinder {

    /**
     * 不能当「脚下地面」的方块。
     * <p>
     * 光靠 {@code isSolid()} 拦不住它们（岩浆块、仙人掌、营火的 isSolid 都是 true），
     * 但站上去的结果是游商持续掉血直到死亡——玩家看到的现象是
     * 「用了凭证，商人过一会儿就没了」，而且没有任何日志。
     * </p>
     */
    private static final Set<Material> UNSAFE_GROUND = EnumSet.of(
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.LAVA_CAULDRON,
            Material.POINTED_DRIPSTONE
    );

    /**
     * 不能出现在「头顶净空」里的方块。
     * <p>
     * 这些方块 {@code isPassable()} 全都为真，而且 {@code isLiquid()} 全都为假
     * （那个方法只认 WATER 和 LAVA），所以现有的「可穿过 + 非液体」两条判定一条都拦不住。
     * 前几种会把商人烧死/毒死/冻死，传送门那几种更糟——商人会被直接送去另一个世界，
     * 而它的岛屿名额记录还留在原地。
     * </p>
     */
    private static final Set<Material> UNSAFE_CLEARANCE = EnumSet.of(
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.WITHER_ROSE,
            Material.POWDER_SNOW,
            Material.BUBBLE_COLUMN,
            Material.SWEET_BERRY_BUSH,
            Material.NETHER_PORTAL,
            Material.END_PORTAL,
            Material.END_GATEWAY
    );

    private SafeSpawnFinder() {
    }

    /**
     * 在基准点附近找一个能安全放下游商的位置。
     *
     * <h3>算法</h3>
     * <ol>
     *   <li>按「离基准点由近到远」的顺序生成候选水平偏移：先 (0,0)，再半径 1 的一圈，
     *       再半径 2 的一圈……到 {@code scanRadius} 为止。<b>由近到远</b>是刻意的：
     *       游商应该出现在岛屿 spawn 旁边，而不是岛角落里；</li>
     *   <li>每个候选列上，按 {@code y = baseY, baseY-1, baseY+1, baseY-2, baseY+2, …} 的顺序
     *       向外交替探测，最多探到 ±{@code verticalRange}。<b>先向下</b>是因为岛屿 spawn 通常
     *       被设在地面上方一点点（出生点上浮），脚下那格才是要找的地面；</li>
     *   <li>一个位置被判为安全，当且仅当：
     *       <ul>
     *         <li>脚下那格是实心方块（{@code Block#isSolid}）<b>且不在危险地面黑名单里</b>
     *             ——虚空里实心这一条就足以排除掉 99% 的坏点，黑名单负责剩下的
     *             「实心但站上去会死」（岩浆块、仙人掌、营火……）；</li>
     *         <li>从这一格往上数 {@code minClearHeight} 格全是「可穿过」的
     *             （{@code Block#isPassable}）、<b>不是液体</b>、<b>且不在净空黑名单里</b>
     *             ——只判 passable 的话水和岩浆都算 passable，游商会被生成进岩浆里；
     *             而火、凋灵玫瑰、细雪、传送门这些既 passable 又不算液体，只能靠黑名单拦；</li>
     *         <li>整体落在岛屿边界内（{@link Island#isInside(World, int, int, int)}），
     *             不会把商人放到邻居家或者岛外；</li>
     *       </ul>
     *   </li>
     *   <li>最多试 {@code maxAttempts} 个候选<b>列</b>就收手（每列的垂直探测不单独计数——
     *       计数的意义是「别在极端地形上无限扫下去」，而一列的垂直探测本来就是有界的小循环）。</li>
     * </ol>
     *
     * @param island          目标岛屿，用来做边界校验
     * @param base            基准点，应当来自 {@link Island#getSpawnLocation(World)}
     * @param scanRadius      水平扫描半径（方块）
     * @param minClearHeight  头顶要求的最小净空（方块）
     * @param verticalRange   垂直方向上下各探多少格
     * @param maxAttempts     最多尝试多少个候选列
     * @return 安全落点（已经居中到方块中心、朝向沿用基准点），<b>找不到时返回 {@code null}</b>。
     * 调用方<b>必须</b>把 null 当成「本次不生成」：不消耗凭证、不消耗自然刷新冷却，
     * 并给玩家一句中文提示。绝不能退而求其次用基准点原样生成——那正是坠出世界的来源。
     */
    public static @Nullable Location find(@NotNull Island island, @NotNull Location base,
                                          int scanRadius, int minClearHeight,
                                          int verticalRange, int maxAttempts) {
        World world = base.getWorld();
        if (world == null) return null;

        int baseX = base.getBlockX();
        int baseY = base.getBlockY();
        int baseZ = base.getBlockZ();

        int minY = world.getMinHeight();
        // getMaxHeight 是「第一个不存在的 y」，所以最高可站立的脚位是 maxHeight-1 再减去净空。
        int maxFeetY = world.getMaxHeight() - 1 - minClearHeight;

        int attempts = 0;
        for (int[] offset : horizontalOffsets(scanRadius)) {
            if (attempts >= maxAttempts) break;

            int x = baseX + offset[0];
            int z = baseZ + offset[1];

            // Folia：不属于当前区域的方块不能读，跳过（这也保证了区块一定是加载着的）。
            if (!Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) continue;

            attempts++;

            for (int dy : verticalOffsets(verticalRange)) {
                int y = baseY + dy;
                // 脚位下面还要有一格放地面，所以脚位本身不能低到 minY。
                if (y <= minY || y > maxFeetY) continue;
                if (!island.isInside(world, x, y, z)) continue;
                if (!isSafeFeetPosition(world, x, y, z, minClearHeight)) continue;

                // +0.5 居中到方块中心，避免生成在方块边缘被挤出去；yaw/pitch 沿用基准点。
                Location result = new Location(world, x + 0.5, y, z + 0.5, base.getYaw(), 0f);
                return result;
            }
        }
        return null;
    }

    /**
     * 判断 {@code (x, y, z)} 能不能当游商的脚位：脚下实心、头顶净空、净空里没有液体。
     */
    private static boolean isSafeFeetPosition(World world, int x, int y, int z, int minClearHeight) {
        Block ground = world.getBlockAt(x, y - 1, z);
        // 用 Block#isSolid 而不是 Material#isSolid：前者看的是这一格<b>当前的方块状态</b>
        // （打开的活板门、非满格的高度类方块……都会被正确判成站不住），后者只看材质的默认状态；
        // Paper 也明说 Block 版更快，因为它不用先把材质映射回方块。
        if (!ground.isSolid()) return false;
        // 实心也不一定站得住：岩浆块/仙人掌/营火这些的 isSolid 都是 true，站上去会被持续伤害。
        if (UNSAFE_GROUND.contains(ground.getType())) return false;
        // 脚下是实心但被水淹着（水下的石头）时，下面的净空检查会因为液体判定把它拦下来，
        // 这里不重复判断。

        for (int i = 0; i < minClearHeight; i++) {
            Block block = world.getBlockAt(x, y + i, z);
            if (!block.isPassable()) return false;
            // isPassable 对水/岩浆返回 true，必须单独排掉，否则游商会被塞进岩浆里烧死，
            // 玩家只会看到「凭证用了，商人没影」。
            if (block.isLiquid()) return false;
            // 火/凋灵玫瑰/细雪/气泡柱/传送门这些同样 isPassable 且 isLiquid 为 false
            // （isLiquid 只认 WATER 和 LAVA），要靠黑名单单独拦。
            if (UNSAFE_CLEARANCE.contains(block.getType())) return false;
        }
        return true;
    }

    /**
     * 生成「由近到远」的水平偏移序列：(0,0) → 半径 1 的一圈 → 半径 2 的一圈 → …
     * <p>
     * 用「切比雪夫距离分环」而不是逐行扫描整个正方形，是为了让靠近岛屿 spawn 的位置
     * 一定先被试到。半径很小（默认 3，共 49 个候选），一次性建表比写一个状态机迭代器清楚得多。
     * </p>
     */
    private static List<int[]> horizontalOffsets(int radius) {
        List<int[]> offsets = new ArrayList<>();
        offsets.add(new int[]{0, 0});
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // 只取这一环的边框，内部的点在更小的 r 上已经加过了。
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    offsets.add(new int[]{dx, dz});
                }
            }
        }
        return offsets;
    }

    /** 生成 {@code 0, -1, +1, -2, +2, …, -range, +range} 的垂直偏移序列（先向下，理由见 {@link #find}）。 */
    private static int[] verticalOffsets(int range) {
        int[] offsets = new int[range * 2 + 1];
        offsets[0] = 0;
        int index = 1;
        for (int d = 1; d <= range; d++) {
            offsets[index++] = -d;
            offsets[index++] = d;
        }
        return offsets;
    }
}
