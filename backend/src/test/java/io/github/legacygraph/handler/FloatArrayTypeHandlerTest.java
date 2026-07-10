package io.github.legacygraph.handler;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link FloatArrayTypeHandler} 单元测试 — 验证 List&lt;Double&gt; 与 pgvector
 * 字符串格式 "[0.1,0.2,0.3]" 之间的双向转换。
 */
@ExtendWith(MockitoExtension.class)
class FloatArrayTypeHandlerTest {

    @Mock
    private PreparedStatement ps;

    @Mock
    private ResultSet rs;

    @Mock
    private CallableStatement cs;

    private final FloatArrayTypeHandler handler = new FloatArrayTypeHandler();

    // ==================== setNonNullParameter ====================

    @Test
    void setNonNullParameter_正常列表转为pgvector字符串格式() throws SQLException {
        List<Double> vec = List.of(0.1, 0.2, 0.3);

        handler.setNonNullParameter(ps, 1, vec, JdbcType.OTHER);

        verify(ps).setString(1, "[0.1,0.2,0.3]");
    }

    @Test
    void setNonNullParameter_空列表转为空数组字符串() throws SQLException {
        handler.setNonNullParameter(ps, 1, Collections.emptyList(), JdbcType.OTHER);

        verify(ps).setString(1, "[]");
    }

    @Test
    void setNonNullParameter_单元素列表转换() throws SQLException {
        handler.setNonNullParameter(ps, 1, List.of(0.5), JdbcType.OTHER);

        verify(ps).setString(1, "[0.5]");
    }

    @Test
    void setNonNullParameter_指定参数索引被透传() throws SQLException {
        handler.setNonNullParameter(ps, 7, List.of(0.1, 0.2), JdbcType.OTHER);

        verify(ps).setString(7, "[0.1,0.2]");
    }

    // ==================== getNullableResult ====================

    @Test
    void getNullableResult_通过列名解析向量字符串() throws SQLException {
        when(rs.getString("embedding")).thenReturn("[0.1,0.2,0.3]");

        List<Double> result = handler.getNullableResult(rs, "embedding");

        assertEquals(List.of(0.1, 0.2, 0.3), result);
    }

    @Test
    void getNullableResult_通过列索引解析向量字符串() throws SQLException {
        when(rs.getString(1)).thenReturn("[0.5]");

        List<Double> result = handler.getNullableResult(rs, 1);

        assertEquals(List.of(0.5), result);
    }

    @Test
    void getNullableResult_通过CallableStatement解析向量字符串() throws SQLException {
        when(cs.getString(2)).thenReturn("[1.1,2.2,3.3]");

        List<Double> result = handler.getNullableResult(cs, 2);

        assertEquals(List.of(1.1, 2.2, 3.3), result);
    }

    @Test
    void getNullableResult_null值返回null() throws SQLException {
        when(rs.getString("col")).thenReturn(null);

        assertNull(handler.getNullableResult(rs, "col"));
    }

    @Test
    void getNullableResult_空字符串返回null() throws SQLException {
        when(rs.getString("col")).thenReturn("");

        assertNull(handler.getNullableResult(rs, "col"));
    }

    // ==================== parseVector 逻辑（经 getNullableResult 间接验证私有方法） ====================

    @Test
    void parseVector_正常格式解析() throws SQLException {
        when(rs.getString("col")).thenReturn("[0.1,0.2,0.3]");

        List<Double> result = handler.getNullableResult(rs, "col");

        assertEquals(List.of(0.1, 0.2, 0.3), result);
    }

    @Test
    void parseVector_带空格格式解析() throws SQLException {
        when(rs.getString("col")).thenReturn("[0.1, 0.2, 0.3]");

        List<Double> result = handler.getNullableResult(rs, "col");

        assertEquals(List.of(0.1, 0.2, 0.3), result);
    }

    @Test
    void parseVector_空数组返回空列表() throws SQLException {
        when(rs.getString("col")).thenReturn("[]");

        List<Double> result = handler.getNullableResult(rs, "col");

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(Collections.emptyList(), result);
    }

    @Test
    void parseVector_空白括号内容返回空列表() throws SQLException {
        when(rs.getString("col")).thenReturn("  [  ]  ");

        List<Double> result = handler.getNullableResult(rs, "col");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseVector_null返回null() throws SQLException {
        when(rs.getString("col")).thenReturn(null);

        assertNull(handler.getNullableResult(rs, "col"));
    }
}
