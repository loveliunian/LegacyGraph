package io.github.legacygraph.agent;

import io.github.legacygraph.dto.MigrationConversion;
import io.github.legacygraph.llm.LlmGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * MigrationAgent - 迁移代码自动转换（Phase 4）。
 */
@Slf4j
@Service
public class MigrationAgent {

    @Autowired
    private LlmGateway llmGateway;

    /**
     * 针对目标迁移方向给出转换建议与转换后代码
     *
     * @param projectId          项目ID
     * @param migrationDirection 迁移方向（如 SpringBoot2_to_3）
     * @param sourcePath         源文件路径
     * @param code               代码内容
     * @param customRules        自定义迁移规则（可空）
     */
    public MigrationConversion convert(String projectId, String migrationDirection,
                                       String sourcePath, String code, String customRules) {
        Map<String, String> variables = new HashMap<>();
        variables.put("migrationDirection", migrationDirection != null ? migrationDirection : "SpringBoot2_to_3");
        variables.put("sourcePath", sourcePath != null ? sourcePath : "");
        variables.put("code", code != null ? code : "");
        variables.put("customRules", customRules != null ? customRules : "（无）");

        return llmGateway.callWithTemplate(projectId, "migration-convert",
                variables, MigrationConversion.class);
    }
}
