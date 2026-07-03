package io.github.legacygraph.understanding;

import io.github.legacygraph.entity.ToolEvidenceEntity;
import io.github.legacygraph.entity.ToolRunEntity;
import io.github.legacygraph.repository.ToolEvidenceRepository;
import io.github.legacygraph.repository.ToolRunRepository;
import io.github.legacygraph.understanding.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ToolRunRecorder 单元测试 —— 验证工具运行记录和证据持久化逻辑。
 *
 * <p>测试场景：
 * <ul>
 *   <li>sha256 计算正确性</li>
 *   <li>truncate 超过长度时截断</li>
 *   <li>recordRunStart 调用 repository.insert</li>
 *   <li>recordRunComplete 调用 repository.updateById</li>
 *   <li>recordEvidence 调用 evidenceRepository.insert</li>
 * </ul>
 *
 * <p>注意：Spring Boot 4.0 移除了 @MockBean，使用纯 Mockito + @InjectMocks。
 * MyBatis-Plus BaseMapper 有重载的 insert/updateById 方法，使用 ArgumentCaptor 避免歧义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolRunRecorder 工具运行记录器测试")
class ToolRunRecorderTest {

    @Mock
    private ToolRunRepository toolRunRepository;

    @Mock
    private ToolEvidenceRepository toolEvidenceRepository;

    @InjectMocks
    private ToolRunRecorder recorder;

    @BeforeEach
    void setUp() {
        // @InjectMocks 已自动注入 mock
    }

    // ========================================================
    // 场景 1：sha256 计算正确性
    // ========================================================

    @Test
    @DisplayName("sha256 应正确计算字符串哈希")
    void shouldCalculateSha256Correctly() {
        // given: 已知输入和期望的 SHA-256 十六进制
        String input = "hello world";
        String expectedHash = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";

        // when
        String actualHash = ToolRunRecorder.sha256(input);

        // then
        assertThat(actualHash).isEqualTo(expectedHash);
        assertThat(actualHash).hasSize(64); // SHA-256 输出 64 字符
    }

    @Test
    @DisplayName("sha256 null 输入应返回 null")
    void shouldReturnNullForNullSha256() {
        // when
        String hash = ToolRunRecorder.sha256(null);

        // then
        assertThat(hash).isNull();
    }

    @Test
    @DisplayName("sha256 空字符串输入应返回 null")
    void shouldReturnNullForEmptySha256() {
        // when
        String hash = ToolRunRecorder.sha256("");

        // then
        assertThat(hash).isNull();
    }

    // ========================================================
    // 场景 2：recordRunStart 调用 repository
    // ========================================================

    @Test
    @DisplayName("recordRunStart 应调用 repository.insert 并设置 PENDING 状态")
    void shouldRecordRunStartWithPendingStatus() {
        // given: 模拟 repository.insert 行为（MyBatis-Plus insert 后自动回填 ID）
        doAnswer(invocation -> {
            ToolRunEntity entity = invocation.getArgument(0);
            entity.setId("run-123");
            return 1;
        }).when(toolRunRepository).insert(any(ToolRunEntity.class));

        // when
        ToolRunEntity run = recorder.recordRunStart(
                "proj-1", "v1", "codebase-memory-mcp", "MCP", "SEARCH_SYMBOL", "abc123hash");

        // then
        assertThat(run).isNotNull();
        assertThat(run.getId()).isEqualTo("run-123");
        assertThat(run.getProjectId()).isEqualTo("proj-1");
        assertThat(run.getVersionId()).isEqualTo("v1");
        assertThat(run.getToolName()).isEqualTo("codebase-memory-mcp");
        assertThat(run.getToolKind()).isEqualTo("MCP");
        assertThat(run.getOperation()).isEqualTo("SEARCH_SYMBOL");
        assertThat(run.getQueryHash()).isEqualTo("abc123hash");
        assertThat(run.getStatus()).isEqualTo("PENDING");
        assertThat(run.getCreatedAt()).isNotNull();

        verify(toolRunRepository, times(1)).insert(any(ToolRunEntity.class));
    }

    // ========================================================
    // 场景 3：recordRunComplete 调用 repository.updateById
    // ========================================================

    @Test
    @DisplayName("recordRunComplete 应调用 repository.updateById 更新状态")
    void shouldRecordRunCompleteWithStatusUpdate() {
        // given: 构建 ToolResult
        ToolResult result = ToolResult.builder()
                .toolName("codebase-memory-mcp")
                .status("SUCCESS")
                .exitCode(0)
                .elapsedMs(1500L)
                .indexFreshness("FRESH")
                .stdoutSha256("sha256abc")
                .stdoutExcerpt("output excerpt...")
                .errorExcerpt(null)
                .build();

        // when
        recorder.recordRunComplete("run-123", result);

        // then: 使用 ArgumentCaptor 验证 updateById 参数（避免 MyBatis-Plus 重载歧义）
        ArgumentCaptor<ToolRunEntity> captor = ArgumentCaptor.forClass(ToolRunEntity.class);
        verify(toolRunRepository).updateById(captor.capture());
        ToolRunEntity entity = captor.getValue();

        assertThat(entity.getId()).isEqualTo("run-123");
        assertThat(entity.getStatus()).isEqualTo("SUCCESS");
        assertThat(entity.getExitCode()).isEqualTo(0);
        assertThat(entity.getElapsedMs()).isEqualTo(1500L);
        assertThat(entity.getIndexFreshness()).isEqualTo("FRESH");
    }

    // ========================================================
    // 场景 4：truncate 超过长度时截断
    // ========================================================

    @Test
    @DisplayName("truncate 超过 2000 字符时应截断并追加 [截断] 标记")
    void shouldTruncateLongExcerpt() {
        // given: 构建 3000 个字符的字符串
        String longText = "A".repeat(3000);

        // when
        ToolResult result = ToolResult.builder()
                .toolName("test-tool")
                .status("SUCCESS")
                .stdoutExcerpt(longText)
                .errorExcerpt(longText)
                .build();
        recorder.recordRunComplete("run-456", result);

        // then: 使用 ArgumentCaptor 验证截断行为
        ArgumentCaptor<ToolRunEntity> captor = ArgumentCaptor.forClass(ToolRunEntity.class);
        verify(toolRunRepository).updateById(captor.capture());
        ToolRunEntity entity = captor.getValue();

        String excerpt = entity.getStdoutExcerpt();
        assertThat(excerpt).isNotNull();
        assertThat(excerpt).endsWith("...[截断]");
        assertThat(excerpt.length()).isLessThanOrEqualTo(2000 + "...[截断]".length());
    }

    // ========================================================
    // 场景 5：recordEvidence 调用 evidenceRepository
    // ========================================================

    @Test
    @DisplayName("recordEvidence 应为每条证据调用 repository.insert")
    void shouldRecordEachEvidence() {
        // given: 两条证据记录
        List<Map<String, Object>> evidenceRecords = List.of(
                Map.<String, Object>of(
                        "evidenceType", "SOURCE_SNIPPET",
                        "sourcePath", "src/main/UserService.java",
                        "symbolQn", "com.example.UserService",
                        "excerpt", "public class UserService {"
                ),
                Map.<String, Object>of(
                        "evidenceType", "CALL_PATH",
                        "sourcePath", "src/main/OrderMapper.java",
                        "symbolQn", "com.example.OrderMapper",
                        "excerpt", "public interface OrderMapper {"
                )
        );

        // when
        recorder.recordEvidence("run-123", evidenceRecords);

        // then: 应调用 insert 两次
        verify(toolEvidenceRepository, times(2)).insert(any(ToolEvidenceEntity.class));
    }

    @Test
    @DisplayName("recordEvidence 空/null 列表不应调用 repository")
    void shouldSkipEmptyEvidence() {
        // when
        recorder.recordEvidence("run-123", List.of());
        recorder.recordEvidence("run-123", null);

        // then: 不应调用 repository
        verify(toolEvidenceRepository, never()).insert(any(ToolEvidenceEntity.class));
    }
}
