package io.github.legacygraph.builder.match;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 字段相似度计算工具。
 * 综合 Jaccard 相似度与 LCS 序列相似度，取较大值，输出范围 0.0~1.0。
 */
public final class FieldSimilarityCalculator {

    private FieldSimilarityCalculator() {
    }

    /**
     * 计算两组字段名的相似度。
     * 算法：取 Jaccard 相似度（|A ∩ B| / |A ∪ B|）与 LCS 序列相似度（LCS 长度 / max(|A|, |B|)）的最大值。
     * 字段名统一小写比较；任一输入为 null 或空数组时返回 0.0。
     *
     * @param fieldsA 字段数组 A
     * @param fieldsB 字段数组 B
     * @return 0.0~1.0 的相似度
     */
    public static double similarity(String[] fieldsA, String[] fieldsB) {
        if (fieldsA == null || fieldsB == null || fieldsA.length == 0 || fieldsB.length == 0) {
            return 0.0;
        }
        String[] a = normalize(fieldsA);
        String[] b = normalize(fieldsB);
        double jaccard = jaccard(a, b);
        double lcs = lcsSimilarity(a, b);
        return Math.max(jaccard, lcs);
    }

    private static String[] normalize(String[] fields) {
        String[] result = new String[fields.length];
        for (int i = 0; i < fields.length; i++) {
            result[i] = fields[i] == null ? "" : fields[i].toLowerCase();
        }
        return result;
    }

    private static double jaccard(String[] a, String[] b) {
        Set<String> setA = new HashSet<>(Arrays.asList(a));
        Set<String> setB = new HashSet<>(Arrays.asList(b));
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        if (union.isEmpty()) {
            return 0.0;
        }
        return (double) intersection.size() / (double) union.size();
    }

    private static double lcsSimilarity(String[] a, String[] b) {
        int m = a.length;
        int n = b.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        int lcsLen = dp[m][n];
        int maxLen = Math.max(m, n);
        if (maxLen == 0) {
            return 0.0;
        }
        return (double) lcsLen / (double) maxLen;
    }
}
