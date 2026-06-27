package io.github.legacygraph.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper;
import io.github.legacygraph.dto.GenerateTestCasesRequest;
import io.github.legacygraph.dto.StartTestRunRequest;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TestCaseServiceTest {

    @Mock
    private GraphNodeRepository graphNodeRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private TestCaseService testCaseService;

    private GraphNode apiNode;

    @BeforeEach
    void setUp() {
        apiNode = new GraphNode();
        apiNode.setId("node-1");
        apiNode.setProjectId("project-1");
        apiNode.setVersionId("version-1");
        apiNode.setNodeType("ApiEndpoint");
        apiNode.setNodeKey("POST /api/process/start");
        apiNode.setDisplayName("Start Process");
        apiNode.setStatus("CONFIRMED");
    }

    @Test
    void testGenerateTestCases_EmptyResult() {
        GenerateTestCasesRequest request = new GenerateTestCasesRequest();
        request.setVersionId("version-1");

        LambdaQueryChainWrapper<GraphNode> chain = new LambdaQueryChainWrapper<>(graphNodeRepository);
        when(graphNodeRepository.lambdaQuery()).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any()).list()).thenReturn(Collections.emptyList());

        String executionId = testCaseService.generateTestCases(request);

        assertNotNull(executionId);
        verify(testCaseRepository, never()).insert(any(TestCase.class));
    }

    @Test
    void testGenerateTestCases_WithSingleApiNode() throws JsonProcessingException {
        GenerateTestCasesRequest request = new GenerateTestCasesRequest();
        request.setVersionId("version-1");

        LambdaQueryChainWrapper<GraphNode> chain = new LambdaQueryChainWrapper<>(graphNodeRepository);
        when(graphNodeRepository.lambdaQuery()).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any()).list()).thenReturn(List.of(apiNode));

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        String executionId = testCaseService.generateTestCases(request);

        assertNotNull(executionId);
        verify(testCaseRepository, times(1)).insert(any(TestCase.class));
    }

    @Test
    void testGenerateTestCases_WithCustomScope() {
        GenerateTestCasesRequest request = new GenerateTestCasesRequest();
        request.setVersionId("version-1");
        GenerateTestCasesRequest.Scope scope = new GenerateTestCasesRequest.Scope();
        scope.setNodeTypes(List.of("ApiEndpoint", "DatabaseTable"));
        request.setScope(scope);

        LambdaQueryChainWrapper<GraphNode> chain = new LambdaQueryChainWrapper<>(graphNodeRepository);
        when(graphNodeRepository.lambdaQuery()).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any()).list()).thenReturn(List.of(apiNode));

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        String executionId = testCaseService.generateTestCases(request);

        assertNotNull(executionId);
        verify(testCaseRepository, times(1)).insert(any(TestCase.class));
    }

    @Test
    void testGenerateTestCases_HandlesJsonProcessingException() throws JsonProcessingException {
        GenerateTestCasesRequest request = new GenerateTestCasesRequest();
        request.setVersionId("version-1");

        LambdaQueryChainWrapper<GraphNode> chain = new LambdaQueryChainWrapper<>(graphNodeRepository);
        when(graphNodeRepository.lambdaQuery()).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any()).list()).thenReturn(List.of(apiNode));

        when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("Error") {});

        String executionId = testCaseService.generateTestCases(request);

        assertNotNull(executionId);
        // Should not insert when JSON serialization fails
        verify(testCaseRepository, never()).insert(any(TestCase.class));
    }

    @Test
    void testStartTestRun_Success() {
        StartTestRunRequest request = new StartTestRunRequest();
        request.setVersionId("version-1");
        request.setCaseIds(List.of("case-1", "case-2"));

        TestCase testCase1 = new TestCase();
        testCase1.setId("case-1");
        testCase1.setProjectId("project-1");
        testCase1.setVersionId("version-1");

        TestCase testCase2 = new TestCase();
        testCase2.setId("case-2");
        testCase2.setProjectId("project-1");
        testCase2.setVersionId("version-1");

        when(testCaseRepository.getById("case-1")).thenReturn(testCase1);
        when(testCaseRepository.getById("case-2")).thenReturn(testCase2);
        when(testResultRepository.insert(any(TestResult.class))).thenReturn(1);

        String executionId = testCaseService.startTestRun(request);

        assertNotNull(executionId);
        verify(testResultRepository, times(2)).insert(any(TestResult.class));
    }

    @Test
    void testStartTestRun_SkipsNotFoundCases() {
        StartTestRunRequest request = new StartTestRunRequest();
        request.setVersionId("version-1");
        request.setCaseIds(List.of("case-1", "case-999"));

        TestCase testCase1 = new TestCase();
        testCase1.setId("case-1");
        testCase1.setProjectId("project-1");
        testCase1.setVersionId("version-1");

        when(testCaseRepository.getById("case-1")).thenReturn(testCase1);
        when(testCaseRepository.getById("case-999")).thenReturn(null);
        when(testResultRepository.insert(any(TestResult.class))).thenReturn(1);

        String executionId = testCaseService.startTestRun(request);

        assertNotNull(executionId);
        verify(testResultRepository, times(1)).insert(any(TestResult.class));
    }

    @Test
    void testGeneratedTestCase_HasCorrectFields() throws JsonProcessingException {
        GenerateTestCasesRequest request = new GenerateTestCasesRequest();
        request.setVersionId("version-1");

        LambdaQueryChainWrapper<GraphNode> chain = new LambdaQueryChainWrapper<>(graphNodeRepository);
        when(graphNodeRepository.lambdaQuery()).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any())).thenReturn(chain);
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any()).list()).thenReturn(List.of(apiNode));

        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            Object obj = invocation.getArgument(0);
            return new ObjectMapper().writeValueAsString(obj);
        });

        doAnswer(invocation -> {
            TestCase testCase = invocation.getArgument(0);
            assertEquals("API", testCase.getCaseType());
            assertEquals("node-1", testCase.getTargetNodeId());
            assertEquals("P2", testCase.getPriority());
            assertEquals("AI_AUTO", testCase.getGeneratedBy());
            assertEquals("GENERATED", testCase.getStatus());
            assertNotNull(testCase.getPreconditions());
            assertNotNull(testCase.getSteps());
            assertNotNull(testCase.getExpectedResult());
            assertTrue(testCase.getCaseCode().startsWith("API_"));
            assertTrue(testCase.getCaseName().contains("Start Process"));
            return null;
        }).when(testCaseRepository).insert(any(TestCase.class));

        testCaseService.generateTestCases(request);

        verify(testCaseRepository, times(1)).insert(any(TestCase.class));
    }
}
