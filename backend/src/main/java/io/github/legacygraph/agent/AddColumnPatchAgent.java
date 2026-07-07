package io.github.legacygraph.agent;

import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.llm.LlmGateway;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ADD_COLUMN 补丁生成 Agent — 基于表结构变更与影响子图，生成加字段执行计划。
 * <p>
 * 薄 LLM shim：组模板变量转发 {@link LlmGateway#callWithEnvelope}，模板名 add-column-patch。
 * LLM 不可用时由 {@code AddColumnPatchAgentAdapter} 降级为人工复核骨架计划。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddColumnPatchAgent {

    private final LlmGateway llmGateway;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddColumnInput {
        private String tableName;
        private String columnName;
        private String columnType;
        private boolean nullable;
        private String defaultValue;
        private String impactedFilesSummary;
    }

    public PatchPlan generate(AgentEnvelope<AddColumnInput> envelope) {
        AddColumnInput input = envelope.getInput();
        if (input == null) {
            log.warn("AddColumnPatchAgent: empty input in envelope {}", envelope.getContractId());
            return null;
        }
        Map<String, String> variables = new HashMap<>();
        variables.put("tableName", input.getTableName() != null ? input.getTableName() : "");
        variables.put("columnName", input.getColumnName() != null ? input.getColumnName() : "");
        variables.put("columnType", input.getColumnType() != null ? input.getColumnType() : "");
        variables.put("nullable", String.valueOf(input.isNullable()));
        variables.put("defaultValue", Objects.toString(input.getDefaultValue(), ""));
        variables.put("impactedFiles", input.getImpactedFilesSummary() != null ? input.getImpactedFilesSummary() : "");
        return llmGateway.callWithEnvelope(envelope, "add-column-patch", variables, PatchPlan.class);
    }
}
