package fr.euphyllia.skylliachallenge.managers;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.api.reward.ChallengeReward;
import fr.euphyllia.skylliachallenge.challenge.Challenge;
import fr.euphyllia.skylliachallenge.gui.ChallengeGui;
import fr.euphyllia.skylliachallenge.loader.ChallengeYamlLoader;
import fr.euphyllia.skylliachallenge.storage.ProgressStorage;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all registered {@link Challenge} objects in the plugin.
 * <p>
 * Its responsibilities include:
 * <ul>
 *     <li>Loading challenge definitions from YAML files</li>
 *     <li>Providing runtime access to challenges by ID</li>
 *     <li>Evaluating completion conditions for a player and island</li>
 *     <li>Applying rewards and updating persistent progress</li>
 *     <li>Opening the GUI to display available challenges</li>
 * </ul>
 *
 * <p>
 * This class acts as the central controller for everything related to challenge
 * validation and progression.
 */
public class ChallengeManagers {
    public static final int LEVEL_COUNT = 5;
    private static final String[] DEFAULT_LEVEL_NAMES = {"入门", "前期", "中期", "后期", "终极"};

    private final SkylliaChallenge skylliaChallenge;
    private final Map<NamespacedKey, Challenge> challengeMap = new ConcurrentHashMap<>();

    // 等级配置
    private final Map<Integer, String> levelNames = new HashMap<>();
    private final Map<Integer, List<String>> levelDescriptions = new HashMap<>();
    private final Map<Integer, List<ChallengeReward>> levelUnlockRewards = new HashMap<>();

    /**
     * Creates a new manager bound to the plugin instance.
     *
     * @param challenge the plugin instance
     */
    public ChallengeManagers(SkylliaChallenge challenge) {
        this.skylliaChallenge = challenge;
        for (int i = 1; i <= 5; i++) {
            levelNames.put(i, DEFAULT_LEVEL_NAMES[i - 1]);
            levelDescriptions.put(i, new ArrayList<>());
        }
    }

    public static String formatDurationShort(long millis) {
        if (millis <= 0) return "0s";
        long totalSec = millis / 1000;
        long days = totalSec / 86400;
        long hours = (totalSec % 86400) / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    /**
     * 加载 levels.yml，包含等级名称、描述和解锁奖励。
     */
    public void loadLevelsConfig(File file) {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (int i = 1; i <= 5; i++) {
            String name = yml.getString("levels." + i + ".name");
            if (name != null) levelNames.put(i, name);

            // 支持字符串或列表
            Object descObj = yml.get("levels." + i + ".description");
            List<String> descLines = new ArrayList<>();
            if (descObj instanceof String str) {
                // 字符串：按 \n 分割
                descLines.addAll(List.of(str.split("\\n")));
            } else if (descObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) descLines.add(s);
                }
            }
            levelDescriptions.put(i, descLines);
        }
        // 解析奖励（复用 ChallengeYamlLoader.parseRewards，需要将其改为 public static）
        for (int level = 2; level <= 5; level++) {
            List<String> raw = yml.getStringList("level-unlock-rewards." + level + ".rewards");
            if (!raw.isEmpty()) {
                levelUnlockRewards.put(level, ChallengeYamlLoader.parseRewards(raw));
            }
        }
    }

    /**
     * 计算指定级别中已完成的挑战数量（完成次数 >= 1）
     */
    public int getCompletedCount(int level, Island island) {
        return (int) getChallenges().stream()
                .filter(c -> c.isShowInGUI() && c.getLevel() == level)
                .filter(c -> ProgressStorage.getTimesCompleted(island.getId(), c.getId()) >= 1)
                .count();
    }

    /**
     * 计算指定级别中尚未完成的挑战数量（完成次数为 0）
     */
    public int getUncompletedCount(int level, Island island) {
        return (int) getChallenges().stream()
                .filter(c -> c.isShowInGUI() && c.getLevel() == level)
                .filter(c -> ProgressStorage.getTimesCompleted(island.getId(), c.getId()) == 0)
                .count();
    }

    /**
     * 判断下一级是否已解锁（当前级别未完成数 ≤ 阈值）
     */
    public boolean isNextLevelUnlocked(int currentLevel, Island island) {
        if (currentLevel >= 5) return true;
        int threshold = switch (currentLevel) {
            case 1 -> 4;
            case 2 -> 3;
            case 3 -> 2;
            case 4 -> 1;
            default -> 0;
        };
        return getUncompletedCount(currentLevel, island) <= threshold;
    }

    /**
     * 判断指定级别是否已解锁。
     * 规则：1 级始终解锁；2 级需 1 级未完成挑战数 ≤ 4；
     * 3 级需 2 级未完成挑战数 ≤ 3；4 级需 3 级未完成挑战数 ≤ 2；
     * 5 级需 4 级未完成挑战数 ≤ 1。
     */
    public boolean isLevelUnlocked(int level, Island island) {
        if (level <= 1) return true;
        int prevLevel = level - 1;
        int threshold = switch (prevLevel) {
            case 1 -> 4;
            case 2 -> 3;
            case 3 -> 2;
            case 4 -> 1;
            default -> Integer.MAX_VALUE;
        };
        long uncompleted = getChallenges().stream()
                .filter(c -> c.isShowInGUI() && c.getLevel() == prevLevel)
                .filter(c -> ProgressStorage.getTimesCompleted(island.getId(), c.getId()) == 0)
                .count();
        return uncompleted <= threshold;
    }

    /**
     * 检测并发放等级解锁奖励（基于未完成数刚好达到阈值）
     */
    public void checkAndGrantLevelUpReward(Island island, Player player, int completedLevel) {
        if (completedLevel >= 5) return;

        int threshold = switch (completedLevel) {
            case 1 -> 4;
            case 2 -> 3;
            case 3 -> 2;
            case 4 -> 1;
            default -> 0;
        };
        int uncompleted = getUncompletedCount(completedLevel, island);

        // 刚好降到阈值时触发（从阈值+1 变为阈值）
        if (uncompleted == threshold) {
            int newLevel = completedLevel + 1;
            List<ChallengeReward> rewards = levelUnlockRewards.get(newLevel);
            if (rewards != null) {
                for (ChallengeReward reward : rewards) {
                    reward.apply(player, island, null);
                }
            }

            // 广播当前完成的等级名称
            String challengeName = getLevelName(completedLevel) + "级别";
            Bukkit.broadcast(
                    ConfigLoader.language.translate(player.locale(),
                            "addons.challenge.player.notify-complete",
                            Map.of("%player_name%", player.getName(),
                                    "%challenge_name%", challengeName)),
                    "skyllia.challenge.notify"
            );
        }
    }

    public String getLevelName(int level) {
        return levelNames.getOrDefault(level, DEFAULT_LEVEL_NAMES[Math.min(level, LEVEL_COUNT)-1]);
    }

    public List<String> getLevelDescription(int level) {
        return levelDescriptions.getOrDefault(level, Collections.emptyList());
    }

    /**
     * @return all currently registered challenges
     */
    public Collection<Challenge> getChallenges() {
        return challengeMap.values();
    }

    /**
     * Retrieves a specific challenge by its unique key.
     *
     * @param key the challenge ID
     * @return the corresponding challenge, or {@code null} if none is found
     */
    @Nullable
    public Challenge getChallenge(NamespacedKey key) {
        return challengeMap.get(key);
    }

    /**
     * Registers a new challenge in memory.
     */
    public void registerChallenge(Challenge challenge) {
        challengeMap.put(challenge.getId(), challenge);
    }

    /**
     * Removes a challenge from memory.
     */
    public void unregisterChallenge(NamespacedKey key) {
        challengeMap.remove(key);
    }

    /**
     * Clears all loaded challenges (used before reloads).
     */
    public void clearChallenges() {
        challengeMap.clear();
    }

    /**
     * Loads challenge definitions from a filesystem folder
     * using {@link ChallengeYamlLoader}, overwriting any existing ones.
     *
     * @param folder the directory containing challenge YAMLs
     */
    public void loadChallenges(File folder) {
        clearChallenges();
        ChallengeYamlLoader.loadFolder(skylliaChallenge, folder).forEach(this::registerChallenge);
    }

    /**
     * Checks if a player is currently eligible to complete a challenge.
     * <p>
     * This verifies:
     * <ul>
     *     <li>Global completion limit ({@link Challenge#getMaxTimes()})</li>
     *     <li>All {@link ChallengeRequirement}s return {@code true}</li>
     * </ul>
     */
    public boolean canComplete(Island island, Challenge challenge, Player actor) {
        if (challenge.getMaxTimes() >= 0) {
            int times = ProgressStorage.getTimesCompleted(island.getId(), challenge.getId());
            if (times >= challenge.getMaxTimes()) return false;
        }

        long remaining = getRemainingCooldownMillis(island, challenge);
        if (remaining > 0) return false;

        if (challenge.getRequirements() != null) {
            for (ChallengeRequirement requirement : challenge.getRequirements()) {
                if (!requirement.isMet(actor, island)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Attempts to complete a challenge for the given island and player.
     * <p>
     * The process is:
     * <ol>
     *     <li>Check completion limit</li>
     *     <li>Consume resources via {@link ChallengeRequirement#consume}</li>
     *     <li>Re-evaluate {@link ChallengeRequirement#isMet} to verify</li>
     *     <li>Apply rewards and increment {@link ProgressStorage}</li>
     *     <li>Broadcast if configured</li>
     * </ol>
     *
     * @return {@code true} if completion was successful
     */
    public boolean complete(Island island, Challenge challenge, Player actor) {
        if (challenge.getMaxTimes() >= 0) {
            int times = ProgressStorage.getTimesCompleted(island.getId(), challenge.getId());
            if (times >= challenge.getMaxTimes()) return false;
        }

        long remaining = getRemainingCooldownMillis(island, challenge);
        if (remaining > 0) return false;

        if (challenge.getRequirements() != null) {
            for (ChallengeRequirement req : challenge.getRequirements()) {
                if (!req.consume(actor, island)) return false;
            }
        }

        boolean allMet = true;
        if (challenge.getRequirements() != null) {
            for (ChallengeRequirement req : challenge.getRequirements()) {
                if (!req.isMet(actor, island)) {
                    allMet = false;
                    break;
                }
            }
        }
        if (!allMet) return false;

        ProgressStoragePartial.resetPartial(island.getId(), challenge.getId());
        ProgressStorage.updateCompletion(island.getId(), challenge.getId(), System.currentTimeMillis());

        if (challenge.getRewards() != null) {
            for (ChallengeReward reward : challenge.getRewards()) {
                reward.apply(actor, island, challenge);
            }
        }

        if (challenge.isBroadcastCompletion()) {
            Bukkit.broadcast(ConfigLoader.language.translate(actor, "addons.challenge.player.notify-complete", Map.of(
                    "%player_name%", actor.getName(),
                    "%challenge_name%", challenge.getName()
            )), "skyllia.challenge.notify");
        }

        // A completed challenge may finish a challenge level (and unlock the next one).
        ChallengeLevelManagers levelManager = skylliaChallenge.getChallengeLevelManager();
        if (levelManager != null) {
            levelManager.evaluate(island, actor);
        }

        checkAndGrantLevelUpReward(island, actor, challenge.getLevel());
        return true;
    }

    /**
     * 管理员强制完成：跳过次数上限、冷却和需求检查，不消耗物品，直接发奖并记一次完成。
     */
    public boolean forceComplete(Island island, Challenge challenge, Player actor) {
        ProgressStoragePartial.resetPartial(island.getId(), challenge.getId());
        ProgressStorage.updateCompletion(island.getId(), challenge.getId(), System.currentTimeMillis());

        if (challenge.getRewards() != null) {
            for (ChallengeReward reward : challenge.getRewards()) {
                reward.apply(actor, island, challenge);
            }
        }

        ChallengeLevelManagers levelManager = skylliaChallenge.getChallengeLevelManager();
        if (levelManager != null) {
            levelManager.evaluate(island, actor);
        }
        checkAndGrantLevelUpReward(island, actor, challenge.getLevel());
        return true;
    }

    public long getRemainingCooldownMillis(Island island, Challenge challenge) {
        long cd = challenge.getCooldownMillis();
        if (cd <= 0) return 0L;

        long last = ProgressStorage.getLastCompleted(island.getId(), challenge.getId());
        if (last <= 0) return 0L;

        long now = System.currentTimeMillis();
        long remaining = (last + cd) - now;
        return Math.max(0L, remaining);
    }

    /**
     * Opens the challenge GUI for a player.
     */
    public void openGui(Player player) {
        new ChallengeGui(skylliaChallenge, this).open(player);
    }
}
