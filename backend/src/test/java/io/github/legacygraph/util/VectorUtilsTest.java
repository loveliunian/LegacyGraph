package io.github.legacygraph.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VectorUtils} 单元测试 — 验证 float[] 与 pgvector 文本格式 / Double 列表之间的转换。
 */
class VectorUtilsTest {

    // ==================== floatArrayToVectorLiteral ====================

    @Test
    void floatArrayToVectorLiteral_正常数组转换() {
        float[] arr = {0.1f, 0.2f, 0.3f};

        String literal = VectorUtils.floatArrayToVectorLiteral(arr);

        assertEquals("[0.1,0.2,0.3]", literal);
    }

    @Test
    void floatArrayToVectorLiteral_空数组返回空括号() {
        assertEquals("[]", VectorUtils.floatArrayToVectorLiteral(new float[0]));
    }

    @Test
    void floatArrayToVectorLiteral_单元素数组() {
        assertEquals("[0.5]", VectorUtils.floatArrayToVectorLiteral(new float[]{0.5f}));
    }

    @Test
    void floatArrayToVectorLiteral_包含负数与零() {
        float[] arr = {-1.5f, 0.0f, 2.5f};

        assertEquals("[-1.5,0.0,2.5]", VectorUtils.floatArrayToVectorLiteral(arr));
    }

    // ==================== floatArrayToDoubleList ====================

    @Test
    void floatArrayToDoubleList_正常数组转换() {
        float[] arr = {0.1f, 0.2f, 0.3f};

        List<Double> result = VectorUtils.floatArrayToDoubleList(arr);

        assertEquals(3, result.size());
        assertEquals((double) 0.1f, result.get(0));
        assertEquals((double) 0.2f, result.get(1));
        assertEquals((double) 0.3f, result.get(2));
    }

    @Test
    void floatArrayToDoubleList_空数组返回空列表() {
        assertTrue(VectorUtils.floatArrayToDoubleList(new float[0]).isEmpty());
    }

    @Test
    void floatArrayToDoubleList_单元素数组() {
        List<Double> result = VectorUtils.floatArrayToDoubleList(new float[]{0.5f});

        assertEquals(1, result.size());
        assertEquals((double) 0.5f, result.get(0));
    }

    @Test
    void floatArrayToDoubleList_验证float到double精度差异() {
        List<Double> result = VectorUtils.floatArrayToDoubleList(new float[]{0.1f});

        // float 0.1f 提升为 double 后，值为其最近浮点表示 0.10000000149011612，并非精确的 0.1d
        assertEquals((double) 0.1f, result.get(0));
        assertEquals(0.10000000149011612d, result.get(0));
        assertNotEquals(0.1d, result.get(0), "float 0.1f 提升为 double 后不等于 double 字面量 0.1");
    }

    @Test
    void floatArrayToDoubleList_结果可修改() {
        List<Double> result = VectorUtils.floatArrayToDoubleList(new float[]{1.0f, 2.0f});

        // 返回的是 ArrayList，支持修改（区别于 List.of 不可变列表）
        result.add(3.0);
        assertEquals(3, result.size());
        assertEquals(3.0, result.get(2));
    }
}
