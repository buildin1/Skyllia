package fr.euphyllia.skylliaislandlevel.manager;

import com.ezylang.evalex.Expression;
import com.ezylang.evalex.config.ExpressionConfiguration;
import com.ezylang.evalex.data.EvaluationValue;
import fr.euphyllia.skyllia.api.skyblock.Island;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LevelFormulaEvaluator {

    private static final Pattern PAPI_PATTERN = Pattern.compile("%([^%]+)%");
    private static final Logger log = LoggerFactory.getLogger(LevelFormulaEvaluator.class);

    private static final Set<String> BUILTIN_VARS = Set.of("score", "level", "size");

    private static final ExpressionConfiguration EVAL_CONFIG =
            ExpressionConfiguration.defaultConfiguration();

    private volatile String expressionStr;
    private volatile boolean valid = false;
    private volatile boolean papiAvailable = false;

    public LevelFormulaEvaluator(String expression) {
        reload(expression);
    }

    private static double evaluateInternal(String rawExpr, List<PapiVar> papiVars, double score, long currentLevel, double islandSize) throws Exception {
        String expr = substituteTokens(rawExpr, papiVars);

        Expression expression = new Expression(expr, EVAL_CONFIG)
                .with("score", BigDecimal.valueOf(score))
                .and("level", BigDecimal.valueOf(currentLevel))
                .and("size", BigDecimal.valueOf(islandSize));

        for (PapiVar v : papiVars) {
            expression = expression.and(v.varName, BigDecimal.valueOf(v.value));
        }

        EvaluationValue result = expression.evaluate();
        return result.getNumberValue().doubleValue();
    }

    private static String substituteTokens(String rawExpr, List<PapiVar> papiVars) {
        String expr = rawExpr;
        for (PapiVar v : papiVars) {
            expr = expr.replace("%" + v.placeholder + "%", v.varName);
        }
        return expr;
    }

    private static List<PapiVar> extractPapiVars(String expression) {
        List<PapiVar> vars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = PAPI_PATTERN.matcher(expression);

        while (m.find()) {
            String placeholder = m.group(1);
            String varName = sanitize(placeholder);

            if (BUILTIN_VARS.contains(varName)) {
                continue;
            }
            if (seen.add(varName)) {
                vars.add(new PapiVar(placeholder, varName, 0.0));
            }
        }
        return vars;
    }

    private static double parseDouble(String raw, String placeholder) {
        if (raw == null || raw.isBlank()) return 0.0;
        String cleaned = raw.replaceAll("§[0-9a-fk-orxA-FK-ORX]", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            e.printStackTrace();
            return 0.0;
        }
    }

    static String sanitize(String placeholder) {
        return "p_" + placeholder.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static OfflinePlayer resolveOwner(Island island) {
        if (island.getOwner() == null) return Bukkit.getOfflinePlayer(UUID.randomUUID());
        return Bukkit.getOfflinePlayer(island.getOwner().getMojangId());
    }

    public synchronized void reload(String expression) {
        this.expressionStr = expression;
        this.papiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        try {
            List<PapiVar> dummyVars = extractPapiVars(expression);
            evaluateInternal(expression, dummyVars, 0.0, 0L, 0.0);
            valid = true;
        } catch (Exception e) {
            log.error("[SkylliaIslandLevel] Invalid level_expression '{}'", expression, e);
            valid = false;
        }
    }

    public long evaluate(double score, long currentLevel, long minLevel, Island island) {
        if (!valid) return minLevel;
        try {
            List<PapiVar> papiVars = resolvePapiVars(expressionStr, island);
            double result = evaluateInternal(expressionStr, papiVars, score, currentLevel, island.getSize());
            if (Double.isNaN(result) || Double.isInfinite(result)) return minLevel;
            return Math.max(minLevel, (long) result);
        } catch (Exception e) {
            e.printStackTrace();
            return minLevel;
        }
    }

    public long evaluate(double score, long currentLevel, long minLevel) {
        if (!valid) return minLevel;
        try {
            double result = evaluateInternal(expressionStr, Collections.emptyList(), score, currentLevel, 0.0);
            if (Double.isNaN(result) || Double.isInfinite(result)) return minLevel;
            return Math.max(minLevel, (long) result);
        } catch (Exception e) {
            e.printStackTrace();
            return minLevel;
        }
    }

    public boolean isValid() {
        return valid;
    }

    private List<PapiVar> resolvePapiVars(String expression, Island island) {
        List<PapiVar> extracted = extractPapiVars(expression);
        if (extracted.isEmpty()) return extracted;

        if (!papiAvailable) {
            return extracted;
        }

        OfflinePlayer ownerPlayer = resolveOwner(island);
        for (PapiVar var : extracted) {
            String raw = PlaceholderAPI.setPlaceholders(ownerPlayer, "%" + var.placeholder + "%");
            var.value = parseDouble(raw, var.placeholder);
        }
        return extracted;
    }

    private static final class PapiVar {
        final String placeholder; // raw token between %%  e.g. "skyllia_island_members"
        final String varName;     // sanitised EvalEx variable name  e.g. "p_skyllia_island_members"
        double value;             // resolved numeric value (0.0 until resolved)

        PapiVar(String placeholder, String varName, double value) {
            this.placeholder = placeholder;
            this.varName = varName;
            this.value = value;
        }
    }
}
