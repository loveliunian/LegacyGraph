package io.github.legacygraph.controller;

import io.github.legacygraph.agent.QaAgent;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.QaAnswer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 自然语言知识库问答控制器。
 *
 * <p>基于 RAG（向量召回 + 图邻域 + LLM 生成）回答关于代码、文档、图谱的自然语言问题，
 * 回答附带证据列表、相关节点和置信度。</p>
 */
@Slf4j
@RestController
@RequestMapping("/qa")
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Tag(name = "知识库问答 API", description = "基于图谱与文档的自然语言问答")
public class GraphQaController {

    private final QaAgent qaAgent;

    @PostMapping("/ask")
    @Operation(summary = "提问", description = "基于图谱与文档检索增强生成回答，返回答案、证据与置信度")
    public Result<QaAnswer> ask(@RequestBody QaRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            return Result.badRequest("问题不能为空");
        }
        log.info("QA ask: projectId={}, versionId={}, question={}",
                request.getProjectId(), request.getVersionId(), request.getQuestion());
        QaAnswer answer = qaAgent.answer(request.getProjectId(), request.getVersionId(), request.getQuestion());
        return Result.ok(answer);
    }

    @Data
    public static class QaRequest {
        @NotBlank(message = "项目ID不能为空")
        private String projectId;
        private String versionId;
        @NotBlank(message = "问题不能为空")
        private String question;
    }
}
