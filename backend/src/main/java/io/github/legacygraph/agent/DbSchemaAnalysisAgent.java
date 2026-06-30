package io.github.legacygraph.agent;

import io.github.legacygraph.dto.DbSchemaAnalysis;
import io.github.legacygraph.llm.LlmGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * DbSchemaAnalysisAgent - 数据库 Schema 语义分析。
 *
 * <p>将 JDBC 抽取的原始表结构元数据（表名、列名、类型、注释）输入 LLM，
 * 输出业务域、表级业务描述、隐式关系等语义增强信息。</p>
 */
@Slf4j
@Service
public class DbSchemaAnalysisAgent {

    @Autowired
    private LlmGateway llmGateway;

    /**
     * 分析数据库 Schema 的业务语义。
     *
     * @param projectId  项目 ID
     * @param schemaInfo 格式化的 Schema 摘要文本（表名、列、注释）
     * @return 业务语义分析结果
     */
    public DbSchemaAnalysis analyze(String projectId, String schemaInfo) {
        Map<String, String> variables = new HashMap<>();
        variables.put("schemaInfo", schemaInfo != null ? schemaInfo : "（无Schema信息）");

        return llmGateway.callWithTemplate(projectId, "db-schema-analysis",
                variables, DbSchemaAnalysis.class);
    }
}
