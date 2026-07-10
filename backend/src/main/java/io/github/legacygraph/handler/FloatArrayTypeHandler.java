package io.github.legacygraph.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * MyBatis TypeHandler: List<Double> <-> pgvector 向量
 * <p>
 * 用于语义缓存表的 question_embedding 字段（vector(1024)）。
 * pgvector 接受字符串格式 "[0.1,0.2,0.3]" 或数组格式。
 * </p>
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class FloatArrayTypeHandler extends BaseTypeHandler<List<Double>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Double> parameter, JdbcType jdbcType)
            throws SQLException {
        // 转换为 pgvector 字符串格式: [0.1,0.2,0.3]
        StringBuilder sb = new StringBuilder("[");
        for (int j = 0; j < parameter.size(); j++) {
            if (j > 0) sb.append(",");
            sb.append(parameter.get(j));
        }
        sb.append("]");
        ps.setString(i, sb.toString());
    }

    @Override
    public List<Double> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseVector(rs.getString(columnName));
    }

    @Override
    public List<Double> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseVector(rs.getString(columnIndex));
    }

    @Override
    public List<Double> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseVector(cs.getString(columnIndex));
    }

    /**
     * 解析 pgvector 返回的字符串格式 "[0.1,0.2,0.3]"
     */
    private List<Double> parseVector(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        // 去除首尾括号
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        
        if (trimmed.isEmpty() || trimmed.isBlank()) {
            return java.util.Collections.emptyList();
        }
        
        String[] parts = trimmed.split(",");
        List<Double> result = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(Double.parseDouble(part.trim()));
        }
        return result;
    }
}
