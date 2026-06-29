package io.github.legacygraph.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private GenerateTestCasesRequest generateRequest;
    private StartTestRunRequest startRequest;

    @BeforeEach
    void setUp() {
        apiNode = new GraphNode();
        apiNode.setId("node-1");
        apiNode.setProjectId("project-1");
        apiNode.setVersionId("v1");
        apiNode.setNodeType("ApiEndpoint");
        apiNode.setNodeKey("POST /api/test/endpoint");
        apiNode.setDisplayName("测试接口");
        apiNode.setStatus("CONFIRMED");

        generateRequest = new GenerateTestCasesRequest();
        generateRequest.setVersionId("v1");
        GenerateTestCasesRequest.Scope scope = new GenerateTestCasesRequest.Scope();
        scope.setNodeTypes(Arrays.asList("ApiEndpoint"));
        generateRequest.setScope(scope);

        startRequest = new StartTestRunRequest();
        startRequest.setVersionId("v1");
        startRequest.setCaseIds(Arrays.asList("case-1", "case-2"));
    }

    @Test
    void testGenerateTestCases_Success() throws JsonProcessingException {
        when(graphNodeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any()).list()).thenReturn(Arrays.asList(apiNode));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(testCaseRepository.insert(any(TestCase.class))).thenReturn(1);

        String executionId = testCaseService.generateTestCases(generateRequest);

        assertNotNull(executionId);
        verify(testCaseRepository, times(1)).insert(any(TestCase.class));
    }

    @Test
    void testGenerateTestCases_EmptyNodes() {
        when(graphNodeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any()).list()).thenReturn(Collections.emptyList());

        String executionId = testCaseService.generateTestCases(generateRequest);

        assertNotNull(executionId);
        verify(testCaseRepository, never()).insert(any(TestCase.class));
    }

    @Test
    void testGenerateTestCases_NullScope() {
        generateRequest.setScope(null);
        when(graphNodeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).in(any(), any()).eq(any(), any()).list()).thenReturn(Arrays.asList(apiNode));

        String executionId = testCaseService.generateTestCases(generateRequest);

        assertNotNull(executionId);
    }

    @Test
    void testStartTestRun_Success() {
        TestCase testCase1 = new TestCase();
        testCase1.setId("case-1");
        testCase1.setProjectId("project-1");
        testCase1.setVersionId("v1");

        TestCase testCase2 = new TestCase();
        testCase2.setId("case-2");
        testCase2.setProjectId("project-1");
        testCase2.setVersionId("v1");

        when(testCaseRepository.getById("case-1")).thenReturn(testCase1);
        when(testCaseRepository.getById("case-2")).thenReturn(testCase2);
        when(testResultRepository.insert(any(TestResult.class))).thenReturn(1);

        String executionId = testCaseService.startTestRun(startRequest);

        assertNotNull(executionId);
        verify(testResultRepository, times(2)).insert(any(TestResult.class));
    }

    @Test
    void testStartTestRun_NotFoundCase() {
        when(testCaseRepository.getById("case-1")).thenReturn(null);
        when(testCaseRepository.getById("case-2")).thenReturn(null);

        String executionId = testCaseService.startTestRun(startRequest);

        assertNotNull(executionId);
        verify(testResultRepository, never()).insert(any(TestResult.class));
    }

    @Test
    void testStartTestRun_MixedResults() {
        TestCase testCase1 = new TestCase();
        testCase1.setId("case-1");
        testCase1.setProjectId("project-1");
        testCase1.setVersionId("v1");

        when(testCaseRepository.getById("case-1")).thenReturn(testCase1);
        when(testCaseRepository.getById("case-2")).thenReturn(null);
        when(testResultRepository.insert(any(TestResult.class))).thenReturn(1);

        String executionId = testCaseService.startTestRun(startRequest);

        assertNotNull(executionId);
        verify(testResultRepository, times(1)).insert(any(TestResult.class));
    }
}
