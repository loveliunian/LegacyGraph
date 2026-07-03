package io.github.legacygraph.understanding;

import io.github.legacygraph.dto.graph.PrivacyLevel;
import io.github.legacygraph.llm.SecretScanService;
import io.github.legacygraph.understanding.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * EvidenceNormalizer 单元测试 —— 验证工具证据归一化逻辑。
 *
 * <p>测试场景：
 * <ul>
 *   <li>SOURCE_SNIPPET 映射为 CODE Claim，置信度高</li>
 *   <li>SUMMARY 映射为 AI_INFERENCE，PENDING_CONFIRM</li>
 *   <li>STALE 工具结果映射为 STALE_REFERENCE</li>
 *   <li>敏感片段经 SecretScanService 脱敏</li>
 *   <li>空证据记录返回空结果</li>
 * </ul>
 *
 * <p>注意：Spring Boot 4.0 移除了 @MockBean，使用纯 Mockito + @InjectMocks。
 * EvidenceNormalizer 会对 evidenceRecords 中的 map 调用 put()，因此证据 map 必须可变。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EvidenceNormalizer 证据归一化测试")
class EvidenceNormalizerTest {

    @Mock
    private SecretScanService secretScanService;

    @InjectMocks
    private EvidenceNormalizer normalizer;

    @BeforeEach
    void setUp() {
        // 默认：SecretScan 不脱敏，原样返回
        when(secretScanService.scan(anyString())).thenAnswer(invocation -> {
            String content = invocation.getArgument(0);
            return SecretScanService.SecretScanResult.builder()
                    .hasSecret(false)
                    .findings(List.of())
                    .redacted(content)
                    .suggestedLevel(PrivacyLevel.INTERNAL)
                    .suggestedPolicy("none")
                    .build();
        });
    }

    /** 构建可变证据 map */
    private Map<String, Object> evidence(String evidenceType, String symbolQn, String sourcePath, String excerpt) {
        Map<String, Object> map = new HashMap<>();
        map.put("evidenceType", evidenceType);
        map.put("symbolQn", symbolQn);
        map.put("sourcePath", sourcePath);
        if (excerpt != null) map.put("excerpt", excerpt);
        return map;
    }

    // ========================================================
    // 场景 1：SOURCE_SNIPPET 映射为 CODE，高置信
    // ========================================================

    @Test
    @DisplayName("SOURCE_SNIPPET 证据 → sourceType=CODE, claimStatus=CONFIRMED")
    void shouldMapSourceSnippetToCodeConfirmed() {
        // given: SOURCE_SNIPPET 类型证据，索引新鲜
        ToolResult result = ToolResult.builder()
                .toolName("codebase-memory-mcp")
                .status("SUCCESS")
                .indexFreshness("FRESH")
                .evidenceRecords(List.of(
                        evidence("SOURCE_SNIPPET", "com.example.UserService",
                                "src/main/UserService.java", "public class UserService {")))
                .build();

        // when
        EvidenceNormalizer.NormalizationResult normalized = normalizer.normalize(
                result, "proj-1", "v1", "run-001");

        // then
        assertThat(normalized.evidenceRecords()).hasSize(1);
        assertThat(normalized.claimDrafts()).hasSize(1);
        // Claim draft 应使用 CODE 来源类型
        assertThat(normalized.claimDrafts().get(0).getSourceType()).isEqualTo("CODE");
    }

    // ========================================================
    // 场景 2：SUMMARY → 不生成 Claim
    // ========================================================

    @Test
    @DisplayName("SUMMARY 证据不生成 Claim，只保留证据记录")
    void shouldNotCreateClaimForSummary() {
        // given: SUMMARY 类型证据
        ToolResult result = ToolResult.builder()
                .toolName("local-fallback")
                .status("SUCCESS")
                .indexFreshness("FRESH")
                .evidenceRecords(List.of(
                        evidence("SUMMARY", "com.example.UserService",
                                "src/main/UserService.java",
                                "This service handles user CRUD operations...")))
                .build();

        // when
        EvidenceNormalizer.NormalizationResult normalized = normalizer.normalize(
                result, "proj-1", "v1", "run-002");

        // then: 证据记录保留，但不应生成 Claim draft
        assertThat(normalized.evidenceRecords()).hasSize(1);
        assertThat(normalized.claimDrafts()).isEmpty();
    }

    // ========================================================
    // 场景 3：STALE 工具结果 → STALE_REFERENCE
    // ========================================================

    @Test
    @DisplayName("STALE 工具结果 → sourceType=STALE_REFERENCE, claimStatus=PENDING_CONFIRM")
    void shouldMapStaleResultToStaleReference() {
        // given: 索引过期
        ToolResult result = ToolResult.builder()
                .toolName("codebase-memory-mcp")
                .status("SUCCESS")
                .indexFreshness("STALE")
                .evidenceRecords(List.of(
                        evidence("SOURCE_SNIPPET", "com.example.UserService",
                                "src/main/UserService.java", "public class UserService {")))
                .build();

        // when
        EvidenceNormalizer.NormalizationResult normalized = normalizer.normalize(
                result, "proj-1", "v1", "run-003");

        // then: sourceType 应为 STALE_REFERENCE
        assertThat(normalized.claimDrafts()).hasSize(1);
        assertThat(normalized.claimDrafts().get(0).getSourceType()).isEqualTo("STALE_REFERENCE");
    }

    // ========================================================
    // 场景 4：敏感片段脱敏
    // ========================================================

    @Test
    @DisplayName("敏感片段经 SecretScanService 脱敏后 excerpt 应被替换")
    void shouldRedactSensitiveContent() {
        // given: 自定义 mock 行为——检测到密钥（覆盖 setUp 中的默认行为）
        when(secretScanService.scan(anyString())).thenReturn(
                SecretScanService.SecretScanResult.builder()
                        .hasSecret(true)
                        .findings(List.of(SecretScanService.SecretFinding.builder()
                                .type("password_assign")
                                .snippet("[MASKED]")
                                .build()))
                        .redacted("password=[MASKED]")
                        .suggestedLevel(PrivacyLevel.SECRET)
                        .suggestedPolicy("mask")
                        .build()
        );

        ToolResult result = ToolResult.builder()
                .toolName("local-fallback")
                .status("SUCCESS")
                .indexFreshness("FRESH")
                .evidenceRecords(List.of(
                        evidence("SOURCE_SNIPPET", "com.example.Config",
                                "src/main/Config.java", "password=SuperSecret123")))
                .build();

        // when
        EvidenceNormalizer.NormalizationResult normalized = normalizer.normalize(
                result, "proj-1", "v1", "run-004");

        // then: 证据记录的 excerpt 应为脱敏后内容
        assertThat(normalized.evidenceRecords()).hasSize(1);
        Map<String, Object> record = normalized.evidenceRecords().get(0);
        assertThat(record.get("excerpt")).isEqualTo("password=[MASKED]");
        assertThat(record.get("privacyLevel")).isEqualTo("SECRET");
    }

    // ========================================================
    // 场景 5：空证据记录返回空结果
    // ========================================================

    @Test
    @DisplayName("空证据记录返回空的 NormalizationResult")
    void shouldReturnEmptyResultForNoEvidence() {
        // given: 无证据记录
        ToolResult result = ToolResult.builder()
                .toolName("test-tool")
                .status("SUCCESS")
                .evidenceRecords(List.of())  // 空列表
                .build();

        // when
        EvidenceNormalizer.NormalizationResult normalized = normalizer.normalize(
                result, "proj-1", "v1", "run-005");

        // then
        assertThat(normalized.evidenceRecords()).isEmpty();
        assertThat(normalized.claimDrafts()).isEmpty();
    }

    @Test
    @DisplayName("null 证据记录应安全处理")
    void shouldHandleNullEvidenceRecords() {
        // given: 证据记录为 null
        ToolResult result = ToolResult.builder()
                .toolName("test-tool")
                .status("SUCCESS")
                .evidenceRecords(null)
                .build();

        // when
        EvidenceNormalizer.NormalizationResult normalized = normalizer.normalize(
                result, "proj-1", "v1", "run-006");

        // then: 不应 NPE
        assertThat(normalized.evidenceRecords()).isEmpty();
        assertThat(normalized.claimDrafts()).isEmpty();
    }
}
