package io.github.legacygraph.understanding;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowledgeClaimDraftMapper 单元测试 —— 验证证据记录到 Claim Draft 的映射规则。
 *
 * <p>测试场景：
 * <ul>
 *   <li>SOURCE_SNIPPET → CODE Claim draft</li>
 *   <li>无 symbolQn 和 sourcePath 时返回 null</li>
 *   <li>SYMBOL → DEFINED_AS 谓词</li>
 *   <li>CALL_PATH → CALLS 谓词</li>
 *   <li>带 symbolQn 时根据 QN 推断 subjectType</li>
 * </ul>
 */
@DisplayName("KnowledgeClaimDraftMapper 映射测试")
class KnowledgeClaimDraftMapperTest {

    // ========================================================
    // 场景 1：SOURCE_SNIPPET → CODE Claim draft
    // ========================================================

    @Test
    @DisplayName("SOURCE_SNIPPET 证据 → subjectType=CodeSnippet, predicate=CONTAINS")
    void shouldMapSourceSnippetToCodeClaim() {
        // given: SOURCE_SNIPPET 类型证据，包含 symbolQn 和 sourcePath
        Map<String, Object> raw = Map.of(
                "evidenceType", "SOURCE_SNIPPET",
                "symbolQn", "com.example.UserController",
                "sourcePath", "src/main/UserController.java",
                "lineStart", 10,
                "lineEnd", 25,
                "excerpt", "public class UserController {",
                "confidence", 0.95
        );

        // when
        KnowledgeClaimDraft draft = KnowledgeClaimDraftMapper.toClaimDraft(
                raw, "CODE", "CONFIRMED", "proj-1", "v1", "run-001");

        // then
        assertThat(draft).isNotNull();
        assertThat(draft.getProjectId()).isEqualTo("proj-1");
        assertThat(draft.getVersionId()).isEqualTo("v1");
        assertThat(draft.getSubjectType()).isEqualTo("ApiEndpoint"); // symbolQn 含 "controller"
        assertThat(draft.getSubjectKey()).isEqualTo("com.example.UserController");
        assertThat(draft.getPredicate()).isEqualTo("CONTAINS");
        assertThat(draft.getSourceType()).isEqualTo("CODE");
        assertThat(draft.getExtractor()).isEqualTo("CodeUnderstandingTool");
        assertThat(draft.getConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.95));
        assertThat(draft.getEvidenceIds()).containsExactly("run-001");
    }

    // ========================================================
    // 场景 2：无 symbolQn 和 sourcePath 时返回 null
    // ========================================================

    @Test
    @DisplayName("无 symbolQn 和 sourcePath 时返回 null")
    void shouldReturnNullWhenNoSymbolQnAndNoSourcePath() {
        // given: 证据记录缺少 symbolQn 和 sourcePath
        Map<String, Object> raw = Map.of(
                "evidenceType", "SUMMARY",
                "excerpt", "This is a summary..."
        );

        // when
        KnowledgeClaimDraft draft = KnowledgeClaimDraftMapper.toClaimDraft(
                raw, "TOOL_SUMMARY", "PENDING_CONFIRM", "proj-1", "v1", "run-002");

        // then: 缺少定位信息，应跳过
        assertThat(draft).isNull();
    }

    // ========================================================
    // 场景 3：SYMBOL → DEFINED_AS 谓词
    // ========================================================

    @Test
    @DisplayName("SYMBOL evidenceType → predicate=DEFINED_AS")
    void shouldMapSymbolEvidenceToDefinedAs() {
        // given: SYMBOL 类型证据
        Map<String, Object> raw = Map.of(
                "evidenceType", "SYMBOL",
                "symbolQn", "com.example.UserService",
                "sourcePath", "src/main/UserService.java",
                "excerpt", "public class UserService {"
        );

        // when
        KnowledgeClaimDraft draft = KnowledgeClaimDraftMapper.toClaimDraft(
                raw, "CODE_GRAPH", "CONFIRMED", "proj-1", "v1", "run-003");

        // then: SYMBOL 应映射为 DEFINED_AS
        assertThat(draft).isNotNull();
        assertThat(draft.getPredicate()).isEqualTo("DEFINED_AS");
        assertThat(draft.getSubjectType()).isEqualTo("Service"); // symbolQn 含 "service"
    }

    // ========================================================
    // 场景 4：CALL_PATH → CALLS 谓词
    // ========================================================

    @Test
    @DisplayName("CALL_PATH evidenceType → predicate=CALLS")
    void shouldMapCallPathEvidenceToCalls() {
        // given: CALL_PATH 类型证据
        Map<String, Object> raw = Map.of(
                "evidenceType", "CALL_PATH",
                "symbolQn", "com.example.OrderMapper",
                "sourcePath", "src/main/OrderMapper.java",
                "excerpt", "public interface OrderMapper {"
        );

        // when
        KnowledgeClaimDraft draft = KnowledgeClaimDraftMapper.toClaimDraft(
                raw, "CODE_GRAPH", "CONFIRMED", "proj-1", "v1", "run-004");

        // then: CALL_PATH 应映射为 CALLS
        assertThat(draft).isNotNull();
        assertThat(draft.getPredicate()).isEqualTo("CALLS");
        assertThat(draft.getSubjectType()).isEqualTo("DataAccess"); // symbolQn 含 "mapper"
    }

    // ========================================================
    // 场景 5：REPO_MAP → STRUCTURED_AS
    // ========================================================

    @Test
    @DisplayName("REPO_MAP evidenceType → predicate=STRUCTURED_AS")
    void shouldMapRepoMapEvidenceToStructuredAs() {
        // given: REPO_MAP 类型证据
        Map<String, Object> raw = Map.of(
                "evidenceType", "REPO_MAP",
                "sourcePath", "src/main/module",
                "excerpt", "Module structure..."
        );

        // when: 没有 symbolQn，使用 sourcePath 作为 subjectKey
        KnowledgeClaimDraft draft = KnowledgeClaimDraftMapper.toClaimDraft(
                raw, "CODE", "CONFIRMED", "proj-1", "v1", "run-005");

        // then: REPO_MAP 应映射为 STRUCTURED_AS
        assertThat(draft).isNotNull();
        assertThat(draft.getPredicate()).isEqualTo("STRUCTURED_AS");
        assertThat(draft.getSubjectType()).isEqualTo("Module");
        assertThat(draft.getSubjectKey()).isEqualTo("src/main/module");
    }

    // ========================================================
    // 场景 6：未知 evidenceType → 默认谓词 RELATED_TO
    // ========================================================

    @Test
    @DisplayName("未知 evidenceType → predicate=RELATED_TO")
    void shouldDefaultToRelatedToForUnknownType() {
        // given: 未知证据类型
        Map<String, Object> raw = Map.of(
                "evidenceType", "UNKNOWN_TYPE",
                "symbolQn", "SomeClass",
                "sourcePath", "src/SomeClass.java"
        );

        // when
        KnowledgeClaimDraft draft = KnowledgeClaimDraftMapper.toClaimDraft(
                raw, "CODE", "CONFIRMED", "proj-1", "v1", "run-006");

        // then: 未知类型默认 RELATED_TO
        assertThat(draft).isNotNull();
        assertThat(draft.getPredicate()).isEqualTo("RELATED_TO");
        assertThat(draft.getSubjectType()).isEqualTo("CodeElement");
    }
}
