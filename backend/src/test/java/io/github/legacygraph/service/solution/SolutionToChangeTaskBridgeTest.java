package io.github.legacygraph.service.solution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.solution.SolutionPlan;
import io.github.legacygraph.dto.solution.SolutionPlanStep;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.repository.SolutionRepository;
import io.github.legacygraph.service.change.ChangeTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link SolutionToChangeTaskBridge} 单元测试（mock SolutionRepository / ChangeTaskService）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SolutionToChangeTaskBridgeTest {

    @Mock
    private SolutionRepository solutionRepository;

    @Mock
    private ChangeTaskService changeTaskService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SolutionToChangeTaskBridge bridge;

    private static final String PROJECT_ID = "proj-001";
    private static final String VERSION_ID = "ver-001";
    private static final String SOLUTION_ID = "sol-001";

    @BeforeEach
    void setUp() {
        bridge = new SolutionToChangeTaskBridge(solutionRepository, changeTaskService, objectMapper);
    }

    private Solution approvedSolution() {
        Solution s = new Solution();
        s.setId(SOLUTION_ID);
        s.setProjectId(PROJECT_ID);
        s.setStatus("APPROVED");
        s.setSummary("重构 OrderService 导出能力");
        return s;
    }

    private ChangeTask mockChangeTask() {
        ChangeTask t = new ChangeTask();
        t.setId("ct-001");
        t.setProjectId(PROJECT_ID);
        t.setVersionId(VERSION_ID);
        t.setTaskType("REFACTOR");
        t.setTitle("重构 OrderService 导出能力");
        t.setStatus("OPEN");
        return t;
    }

    @Test
    void bridgeApprovedSolutionCreatesChangeTask() {
        Solution solution = approvedSolution();
        when(solutionRepository.selectById(SOLUTION_ID)).thenReturn(solution);
        when(changeTaskService.createTask(eq(PROJECT_ID), eq(VERSION_ID), anyString(),
                anyString(), anyString()))
                .thenReturn(mockChangeTask());

        ChangeTask result = bridge.bridge(SOLUTION_ID, PROJECT_ID, VERSION_ID);

        assertNotNull(result);
        assertEquals("ct-001", result.getId());
        // 验证 createTask 被调用且 taskType 为 REFACTOR
        ArgumentCaptor<String> taskTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(changeTaskService).createTask(eq(PROJECT_ID), eq(VERSION_ID),
                taskTypeCaptor.capture(), anyString(), anyString());
        assertEquals("REFACTOR", taskTypeCaptor.getValue());
        // 验证 solution 的 changeTaskId 被回写并更新
        assertEquals("ct-001", solution.getChangeTaskId());
        verify(solutionRepository).updateById(solution);
    }

    @Test
    void bridgeNonApprovedSolutionThrowsException() {
        Solution solution = approvedSolution();
        solution.setStatus("DRAFT");
        when(solutionRepository.selectById(SOLUTION_ID)).thenReturn(solution);

        assertThrows(IllegalStateException.class,
                () -> bridge.bridge(SOLUTION_ID, PROJECT_ID, VERSION_ID));
        verify(changeTaskService, never()).createTask(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void inferTaskTypeAllCreateReturnsRefactor() {
        SolutionPlan plan = new SolutionPlan();
        SolutionPlanStep s1 = new SolutionPlanStep();
        s1.setActionType("CREATE");
        s1.setFilePath("src/main/java/io/github/legacygraph/Main.java");
        SolutionPlanStep s2 = new SolutionPlanStep();
        s2.setActionType("CREATE");
        s2.setFilePath("src/main/java/io/github/legacygraph/Util.java");
        plan.setSteps(List.of(s1, s2));

        assertEquals("REFACTOR", bridge.inferTaskType(plan));
    }

    @Test
    void inferTaskTypeWithSqlReturnsUpgrade() {
        SolutionPlan plan = new SolutionPlan();
        SolutionPlanStep s1 = new SolutionPlanStep();
        s1.setActionType("MODIFY");
        s1.setFilePath("db/migration/V1__add_column.sql");
        SolutionPlanStep s2 = new SolutionPlanStep();
        s2.setActionType("MODIFY");
        s2.setFilePath("src/main/java/io/github/legacygraph/Main.java");
        plan.setSteps(List.of(s1, s2));

        assertEquals("UPGRADE", bridge.inferTaskType(plan));
    }

    @Test
    void inferTaskTypeDefaultReturnsRefactor() {
        SolutionPlan plan = new SolutionPlan();
        SolutionPlanStep s1 = new SolutionPlanStep();
        s1.setActionType("MODIFY");
        s1.setFilePath("src/main/java/io/github/legacygraph/Main.java");
        SolutionPlanStep s2 = new SolutionPlanStep();
        s2.setActionType("DELETE");
        s2.setFilePath("src/main/java/io/github/legacygraph/Old.java");
        plan.setSteps(List.of(s1, s2));

        assertEquals("REFACTOR", bridge.inferTaskType(plan));
    }
}
