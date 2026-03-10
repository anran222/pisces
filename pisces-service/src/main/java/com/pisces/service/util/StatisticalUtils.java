package com.pisces.service.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 统计工具类
 * 包含双比例 Z 检验、正态 CDF、卡方 SRM 检测、样本量计算等
 * 全部使用纯 Java 实现，无第三方依赖
 */
public final class StatisticalUtils {

    private StatisticalUtils() {}

    // ─────────────────────────────────────────────
    // 基础概率函数
    // ─────────────────────────────────────────────

    /**
     * 标准正态 CDF：Φ(x)
     * 使用误差函数近似（最大误差 < 1.5×10⁻⁷）
     */
    public static double normalCDF(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    /**
     * 误差函数近似（Abramowitz & Stegun 7.1.26）
     */
    public static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double poly = t * (0.254829592
                + t * (-0.284496736
                + t * (1.421413741
                + t * (-1.453152027
                + t * 1.061405429))));
        double result = 1.0 - poly * Math.exp(-x * x);
        return x >= 0 ? result : -result;
    }

    /**
     * 双尾 p 值（由 Z 统计量计算）
     */
    public static double zToPValue(double z) {
        return 2.0 * (1.0 - normalCDF(Math.abs(z)));
    }

    /**
     * 正态分布分位数（逆 CDF）
     * Beasley-Springer-Moro 近似
     */
    public static double normalQuantile(double p) {
        if (p <= 0 || p >= 1) throw new IllegalArgumentException("p 必须在 (0,1) 之间");
        double[] a = {2.50662823884, -18.61500062529, 41.39119773534, -25.44106049637};
        double[] b = {-8.47351093090, 23.08336743743, -21.06224101826, 3.13082909833};
        double[] c = {0.3374754822726147, 0.9761690190917186, 0.1607979714918209,
                0.0276438810333863, 0.0038405729373609, 0.0003951896511349,
                0.0000321767881768, 0.0000002888167364, 0.0000003960315187};
        double q = p - 0.5;
        if (Math.abs(q) <= 0.42) {
            double r = q * q;
            return q * (((a[3] * r + a[2]) * r + a[1]) * r + a[0])
                    / ((((b[3] * r + b[2]) * r + b[1]) * r + b[0]) * r + 1.0);
        }
        double r = Math.log(-Math.log(q < 0 ? p : 1.0 - p));
        double ans = c[0] + r * (c[1] + r * (c[2] + r * (c[3] + r * (c[4]
                + r * (c[5] + r * (c[6] + r * (c[7] + r * c[8])))))));
        return q < 0 ? -ans : ans;
    }

    // ─────────────────────────────────────────────
    // 卡方分布（用于 SRM 检测）
    // ─────────────────────────────────────────────

    /**
     * 卡方分布 CDF：P(X ≤ x)，自由度 dof
     */
    public static double chiSquareCDF(double x, int dof) {
        if (x <= 0) return 0.0;
        return regularizedGammaP(dof / 2.0, x / 2.0);
    }

    /** 正则化不完全 Gamma 函数 P(a, x)，用级数展开 */
    private static double regularizedGammaP(double a, double x) {
        if (x < 0 || a <= 0) return 0.0;
        if (x == 0) return 0.0;
        double ap = a;
        double del = 1.0 / a;
        double sum = del;
        for (int n = 1; n < 300; n++) {
            ap++;
            del *= x / ap;
            sum += del;
            if (Math.abs(del) < Math.abs(sum) * 1e-10) break;
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    /** log Γ(x)，Lanczos 近似 */
    private static double logGamma(double x) {
        double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double ci : c) ser += ci / ++y;
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    // ─────────────────────────────────────────────
    // SRM 检测（样本比例不匹配）
    // ─────────────────────────────────────────────

    /**
     * 用卡方检验检测 SRM（Sample Ratio Mismatch）
     *
     * @param observed      各组实际观测访客数
     * @param expectedRatios 各组预期流量比例（不必归一化）
     * @return 卡方值、p 值、是否存在 SRM 等
     */
    public static Map<String, Object> detectSRM(long[] observed, double[] expectedRatios) {
        if (observed.length != expectedRatios.length || observed.length < 2) {
            throw new IllegalArgumentException("observed 与 expectedRatios 长度须相同且 ≥ 2");
        }

        long totalObserved = 0;
        for (long o : observed) totalObserved += o;

        double totalRatio = 0;
        for (double r : expectedRatios) totalRatio += r;

        double chiSquare = 0;
        double[] expected = new double[observed.length];
        for (int i = 0; i < observed.length; i++) {
            expected[i] = totalObserved * (expectedRatios[i] / totalRatio);
            if (expected[i] > 0) {
                double diff = observed[i] - expected[i];
                chiSquare += (diff * diff) / expected[i];
            }
        }

        int dof = observed.length - 1;
        double pValue = 1.0 - chiSquareCDF(chiSquare, dof);
        boolean hasSRM = pValue < 0.01; // SRM 使用更严格的 1% 阈值

        Map<String, Object> result = new HashMap<>();
        result.put("chiSquare", chiSquare);
        result.put("degreesOfFreedom", dof);
        result.put("pValue", pValue);
        result.put("hasSRM", hasSRM);
        result.put("observed", observed);
        result.put("expected", expected);
        result.put("interpretation", hasSRM
                ? String.format("检测到 SRM（p=%.4f < 0.01），实际分流比例与预期不符，实验结论不可信，请排查分桶逻辑", pValue)
                : String.format("未检测到 SRM（p=%.4f），分流比例正常", pValue));
        return result;
    }

    // ─────────────────────────────────────────────
    // 双比例 Z 检验
    // ─────────────────────────────────────────────

    /**
     * 双比例 Z 检验（Two-proportion Z-test）
     * 适用于转化率、点击率等比例指标的显著性检验
     *
     * @param n1 变体组曝光量
     * @param x1 变体组转化量
     * @param n2 基准组曝光量
     * @param x2 基准组转化量
     * @param alpha 显著性水平（如 0.05）
     * @return Z 统计量、p 值、提升率、置信区间等
     */
    public static Map<String, Object> twoProportionZTest(long n1, long x1, long n2, long x2, double alpha) {
        Map<String, Object> result = new HashMap<>();
        if (n1 <= 0 || n2 <= 0) {
            result.put("error", "样本量必须大于 0");
            return result;
        }

        double p1 = (double) x1 / n1;   // 变体转化率
        double p2 = (double) x2 / n2;   // 基准转化率
        double pPool = (double) (x1 + x2) / (n1 + n2); // 混合估计

        double se = Math.sqrt(pPool * (1 - pPool) * (1.0 / n1 + 1.0 / n2));
        double z = se > 0 ? (p1 - p2) / se : 0.0;
        double pValue = zToPValue(z);
        double lift = p2 > 0 ? (p1 - p2) / p2 : 0.0;

        // 置信区间（使用非混合 SE）
        double ciSE = Math.sqrt(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2);
        double zCrit = normalQuantile(1.0 - alpha / 2.0);
        double ciLower = (p1 - p2) - zCrit * ciSE;
        double ciUpper = (p1 - p2) + zCrit * ciSE;

        boolean isSignificant = pValue < alpha;

        result.put("variantRate", p1);
        result.put("baselineRate", p2);
        result.put("zStatistic", z);
        result.put("pValue", pValue);
        result.put("isSignificant", isSignificant);
        result.put("lift", lift);
        result.put("liftPercent", lift * 100);
        result.put("ciLower", ciLower);
        result.put("ciUpper", ciUpper);
        result.put("confidenceLevel", (1 - alpha) * 100 + "%");
        result.put("interpretation", isSignificant
                ? String.format("统计显著：变体转化率 %.4f，基准 %.4f，提升 %.2f%%，p=%.4f",
                        p1, p2, lift * 100, pValue)
                : String.format("未达显著：p=%.4f（需 < %.2f），建议继续收集数据", pValue, alpha));
        return result;
    }

    // ─────────────────────────────────────────────
    // 样本量计算（功效分析）
    // ─────────────────────────────────────────────

    /**
     * 计算每组所需最小样本量
     *
     * @param baselineRate 基准转化率
     * @param mde          最小可检测效应（相对提升，如 0.05 表示 5%）
     * @param alpha        显著性水平（如 0.05）
     * @param power        统计功效（如 0.8）
     */
    public static long calculateSampleSize(double baselineRate, double mde, double alpha, double power) {
        double p1 = baselineRate;
        double p2 = baselineRate * (1 + mde);
        double pPool = (p1 + p2) / 2.0;

        double zAlpha = normalQuantile(1.0 - alpha / 2.0);
        double zBeta = normalQuantile(power);

        double numerator = Math.pow(
                zAlpha * Math.sqrt(2 * pPool * (1 - pPool))
                        + zBeta * Math.sqrt(p1 * (1 - p1) + p2 * (1 - p2)),
                2);
        double denominator = Math.pow(p2 - p1, 2);
        return (long) Math.ceil(numerator / denominator);
    }

    // ─────────────────────────────────────────────
    // 序贯检验（SPRT）
    // ─────────────────────────────────────────────

    /**
     * SPRT（序贯概率比检验）— 适合实验期间持续监控，无 Peeking Problem
     *
     * @param n1       变体组曝光量
     * @param x1       变体组转化量
     * @param n2       基准组曝光量
     * @param x2       基准组转化量
     * @param p0       H0 转化率（通常等于基准率）
     * @param mde      最小可检测效应（相对）
     * @param alpha    I 类错误率
     * @param beta     II 类错误率（1 - power）
     */
    public static Map<String, Object> sprtTest(long n1, long x1, long n2, long x2,
                                               double p0, double mde,
                                               double alpha, double beta) {
        double p1 = p0;          // H0：转化率 = p0
        double p2 = p0 * (1 + mde); // H1：转化率 = p0*(1+mde)

        // 对数似然比（Wald SPRT）
        double variantRate = n1 > 0 ? (double) x1 / n1 : 0;
        double logLR = 0;
        if (x1 > 0) logLR += x1 * Math.log(p2 / p1);
        if (n1 - x1 > 0) logLR += (n1 - x1) * Math.log((1 - p2) / (1 - p1));

        double A = Math.log((1 - beta) / alpha);  // 接受 H1 阈值
        double B = Math.log(beta / (1 - alpha));  // 接受 H0 阈值

        String decision;
        if (logLR >= A) {
            decision = "ACCEPT_H1";
        } else if (logLR <= B) {
            decision = "ACCEPT_H0";
        } else {
            decision = "CONTINUE";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("logLikelihoodRatio", logLR);
        result.put("acceptH1Threshold", A);
        result.put("acceptH0Threshold", B);
        result.put("decision", decision);
        result.put("variantRate", variantRate);
        result.put("baselineRate", n2 > 0 ? (double) x2 / n2 : 0);
        result.put("canStop", !decision.equals("CONTINUE"));
        result.put("interpretation", switch (decision) {
            case "ACCEPT_H1" -> String.format("序贯检验：接受 H1，变体效果显著（LLR=%.3f ≥ %.3f），可停止实验", logLR, A);
            case "ACCEPT_H0" -> String.format("序贯检验：接受 H0，无显著效果（LLR=%.3f ≤ %.3f），可停止实验", logLR, B);
            default -> String.format("序贯检验：继续观测（LLR=%.3f 在 [%.3f, %.3f] 之间）", logLR, B, A);
        });
        return result;
    }
}
