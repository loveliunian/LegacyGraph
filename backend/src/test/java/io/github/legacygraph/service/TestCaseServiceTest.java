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
import io.github.legacygraph.test.ApiTestExecutor;
import io.github.legacygraph.test.DbAssertionExecutor;
import io.github.legacygraph.test.E2eTestExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

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
    @Mock
    private ApiTestExecutor apiTestExecutor;
    @Mock
    private DbAssertionExecutor dbAssertionExecutor;
    @Mock
    private E2eTestExecutor e2eTestExecutor;
    @Mock
    private TestResultUpdateService testResultUpdateService;

    private TestCaseService testCaseService;

    private GraphNode apiNode;
    private GraphNode tableNode;
    private GraphNode pageNode;
    private GenerateTestCasesRequest generateRequest;
    private StartTestRunRequest startRequest;

    @BeforeEach
    void setUp() {
        testCaseService = new TestCaseService(
                graphNodeRepository, testCaseRepository, testResultRepository, objectMapper,
                apiTestExecutor, dbAssertionExecutor, e2eTestExecutor, testResultUpdateService
        );

        apiNode = new GraphNode();
        apiNode.setId("api-1");
        apiNode.setProjectId("project-1");
        apiNode.setVersionId("v1");
        apiNode.setNodeType("ApiEndpoint");
        apiNode.setNodeKey("GET /api/users");
        apiNode.setNodeName("getUsers");
        apiNode.setDisplayName("GET /api/users");
        apiNode.setStatus("CONFIRMED");
        apiNode.setConfidence(BigDecimal.valueOf(0.9));

        tableNode = new GraphNode();
        tableNode.setId("table-1");
        tableNode.setNodeType("Table");
        tableNode.setNodeName("t_user");
        tableNode.setStatus("CONFIRMED");

        pageNode = new GraphNode();
        pageNode.setId("page-1");
        pageNode.setNodeType("Page");
        pageNode.setNodeName("UserList");
        pageNode.setStatus("CONFIRMED");

        GenerateTestCasesRequest.Scope scope = new GenerateTestCasesRequest.Scope();
        scope.setNodeTypes(Arrays.asList("ApiEndpoint", "Table", "Page"));
        generateRequest = new GenerateTestCasesRequest();
        generateRequest.setVersionId("v1");
        generateRequest.setScope(scope);

        startRequest = new StartTestRunRequest();
        startRequest.setVersionId("v1");
        startRequest.setCaseIds(Arrays.asList("case-1", "case-2"));
    }

    @SuppressWarnings("unchecked")
    private com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<GraphNode> mockChainWrapper() {
        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<GraphNode> chain =
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        when(graphNodeRepository.lambdaQuery()).thenReturn(chain);
        // 链式调用 - 所有 eq 和 in 都返回 chain 自身
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.in(any(), ArgumentMatchers.<java.util.Collection<?>>any())).thenReturn(chain);
        return chain;
    }

    @Test
    void testGenerateTestCases_Success() throws JsonProcessingException {
        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<GraphNode> chain = mockChainWrapper();
        when(chain.list()).thenReturn(Arrays.asList(apiNode));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(testCaseRepository.insert(any(TestCase.class))).thenReturn(1);

        String executionId = testCaseService.generateTestCases(generateRequest);

        assertNotNull(executionId);
        // ApiEndpoint generates 3 test cases (NORMAL, UNAUTHORIZED, BAD_PARAM)
        verify(testCaseRepository, atLeastOnce()).insert(any(TestCase.class));
    }

    @Test
    void testGenerateTestCases_EmptyNodes() {
        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<GraphNode> chain = mockChainWrapper();
        when(chain.list()).thenReturn(Collections.emptyList());

        String executionId = testCaseService.generateTestCases(generateRequest);

        assertNotNull(executionId);
        verify(testCaseRepository, never()).insert(any(TestCase.class));
    }

    @Test
    void testGenerateTestCases_NullScope() {
        generateRequest.setScope(null);
        // NullScope should use default nodeTypes = ["ApiEndpoint"]
        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<GraphNode> chain = mockChainWrapper();
        when(chain.list()).thenReturn(Arrays.asList(apiNode));
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        when(testCaseRepository.insert(any(TestCase.class))).thenReturn(1);

        String executionId = testCaseService.generateTestCases(generateRequest);

        assertNotNull(executionId);
        verify(testCaseRepository, atLeastOnce()).insert(any(TestCase.class));
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
