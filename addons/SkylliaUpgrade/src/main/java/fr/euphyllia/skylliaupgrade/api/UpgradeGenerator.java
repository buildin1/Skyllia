package fr.euphyllia.skylliaupgrade.api;

import java.util.List;
import java.util.UUID;

/** 统一的岛屿升级等级持久化接口，实现类对应不同数据库后端。 */
public interface UpgradeGenerator {

    /** 获取岛屿当前等级；若无记录返回等级 0。 */
    UpgradeRecord getRecord(UUID islandId);

    /** 设置岛屿的等级（覆盖式写入）。 */
    Boolean setLevel(UUID islandId, int level);

    /** 排行榜：等级最高的岛屿列表。 */
    List<UpgradeRecord> getTopLevels();
}
