package io.github.legacygraph.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量工具类 - 提供 float[] 与 pgvector 文本格式 / Double 列表之间的转换
 */
public final class VectorUtils {

    private VectorUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 将 float[] 转为 pgvector 文本格式，如 "[0.1,0.2,0.3]"
     */
    public static String floatArrayToVectorLiteral(float[] floats) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < floats.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(floats[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 将 float[] 转为 List&lt;Double&gt;
     */
    public static List<Double> floatArrayToDoubleList(float[] floats) {
        List<Double> result = new ArrayList<>(floats.length);
        for (float f : floats) {
            result.add((double) f);
        }
        return result;
    }
}
