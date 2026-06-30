package io.github.legacygraph.agent;

import io.github.legacygraph.dto.SqlAdvisorResult;
import io.github.legacygraph.llm.LlmGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * SqlAdvisorAgent - SQL 性能优化顾问
 *
 * <p>分析 MyBatis / JDBC SQL，识别 SELECT *、缺索引、N+1、前缀模糊匹配等问题，
 * 给出优化建议与优化后 SQL。迁移场景实用。</p>
 */
@Slf4j
@Service
public class SqlAdvisorAgent {

    @Autowired
    private LlmGateway llmGateway;

    /**
     * 分析单条 SQL 的性能问题
     *
     * @param projectId  项目ID
     * @param sqlKey     SQL 来源标识（如 Mapper#方法）
     * @param sql        SQL 语句
     * @param schemaInfo 相关表结构/索引信息（可为空）
     */
    public SqlAdvisorResult analyze(String projectId, String sqlKey, String sql, String schemaInfo) {
        Map<String, String> variables = new HashMap<>();
        variables.put("sqlKey", sqlKey != null ? sqlKey : "");
        variables.put("sql", sql != null ? sql : "");
        variables.put("schemaInfo", schemaInfo != null ? schemaInfo : "（无表结构信息）");

        return llmGateway.callWithTemplate(projectId, "sql-advisor", variables, SqlAdvisorResult.class);
    }
}
