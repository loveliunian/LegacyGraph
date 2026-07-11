package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.entity.Requirement;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.AcceptanceCriterionRepository;
import io.github.legacygraph.repository.RequirementItemRepository;
import io.github.legacygraph.repository.RequirementRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.github.legacygraph.service.requirement.AcceptanceVerificationService;
import io.github.legacygraph.service.requirement.ContractGeneratorService;
import io.github.legacygraph.service.requirement.ImpactSubgraphService;
import io.github.legacygraph.service.requirement.RequirementDataLineageService;
import io.github.legacygraph.service.requirement.RequirementExtractionService;
import io.github.legacygraph.service.requirement.RequirementGraphBuilder;
import io.github.legacygraph.service.requirement.RequirementLinkingService;
import io.github.legacygraph.service.requirement.RequirementPatchService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementControllerTest {

    @Test
    void clarifyWithValidAnswersReturnsUpdatedAnalysis() {
        RequirementExtractionService extractionService = mock(RequirementExtractionService.class);
        RequirementGraphBuilder graphBuilder = mock(RequirementGraphBuilder.class);
        RequirementLinkingService linkingService = mock(RequirementLinkingService.class);
        ImpactSubgraphService impactService = mock(ImpactSubgraphService.class);
        RequirementRepository requirementRepository = mock(RequirementRepository.class);
        RequirementItemRepository itemRepository = mock(RequirementItemRepository.class);
        AcceptanceCriterionRepository criterionRepository = mock(AcceptanceCriterionRepository.class);
        ScanVersionRepository scanVersionRepository = mock(ScanVersionRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        RequirementController controller = new RequirementController(
                extractionService, graphBuilder, linkingService, impactService,
                mock(AcceptanceVerificationService.class),
                mock(RequirementPatchService.class),
                requirementRepository, itemRepository, criterionRepository,
                scanVersionRepository, objectMapper,
                mock(RequirementDataLineageService.class));

        // 原需求
        Requirement req = new Requirement();
        req.setId("req-1");
        req.setProjectId("project-1");
        req.setText("原始需求文本");
        req.setGoal("原始目标");
        when(requirementRepository.selectById("req-1")).thenReturn(req);

        // LLM 重新抽取
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setGoal("更新后的目标");
        RequirementItemDTO item = new RequirementItemDTO();
        item.setCode("R1");
        item.setText("更新后的条目");
        item.setAcceptanceCriteria(List.of("验收标准1"));
        item.setConstraints(List.of("约束1"));
        analysis.setItems(new ArrayList<>(List.of(item)));
        analysis.setOpenQuestions(new ArrayList<>());
        when(extractionService.extract(eq("project-1"), anyString())).thenReturn(analysis);

        // 扫描版本
        ScanVersion version = new ScanVersion();
        version.setId("version-1");
        when(scanVersionRepository.selectOne(any())).thenReturn(version);

        // 无旧条目
        when(itemRepository.selectList(any())).thenReturn(List.of());

        // 图谱构建
        RequirementGraphBuilder.BuildResult buildResult =
                new RequirementGraphBuilder.BuildResult(List.of("node-1"), 1);
        when(graphBuilder.build(eq("project-1"), eq("version-1"), eq("req-1"), any()))
                .thenReturn(buildResult);

        // 构造请求
        RequirementController.ClarifyRequest request = new RequirementController.ClarifyRequest();
        request.setAnswers(Map.of("问题1", "回答1"));

        // 调用
        Result<RequirementController.RequirementResponse> result =
                controller.clarify("project-1", "req-1", request);

        // 验证
        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getData().getRequirementId()).isEqualTo("req-1");
        assertThat(result.getData().getAnalysis().getGoal()).isEqualTo("更新后的目标");
        assertThat(result.getData().getItemCount()).isEqualTo(1);
        verify(requirementRepository).updateById(any(Requirement.class));
        verify(itemRepository).insert(any(io.github.legacygraph.entity.RequirementItem.class));
    }

    @Test
    void clarifyWithNonExistentRequirementThrowsException() {
        RequirementExtractionService extractionService = mock(RequirementExtractionService.class);
        RequirementGraphBuilder graphBuilder = mock(RequirementGraphBuilder.class);
        RequirementLinkingService linkingService = mock(RequirementLinkingService.class);
        ImpactSubgraphService impactService = mock(ImpactSubgraphService.class);
        RequirementRepository requirementRepository = mock(RequirementRepository.class);
        RequirementItemRepository itemRepository = mock(RequirementItemRepository.class);
        AcceptanceCriterionRepository criterionRepository = mock(AcceptanceCriterionRepository.class);
        ScanVersionRepository scanVersionRepository = mock(ScanVersionRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        RequirementController controller = new RequirementController(
                extractionService, graphBuilder, linkingService, impactService,
                mock(AcceptanceVerificationService.class),
                mock(RequirementPatchService.class),
                requirementRepository, itemRepository, criterionRepository,
                scanVersionRepository, objectMapper,
                mock(RequirementDataLineageService.class));

        when(requirementRepository.selectById("non-existent")).thenReturn(null);

        RequirementController.ClarifyRequest request = new RequirementController.ClarifyRequest();
        request.setAnswers(Map.of("问题1", "回答1"));

        assertThatThrownBy(() -> controller.clarify("project-1", "non-existent", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需求不存在");
    }
}
