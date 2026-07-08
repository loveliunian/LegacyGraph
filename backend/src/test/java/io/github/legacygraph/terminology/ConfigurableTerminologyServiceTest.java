package io.github.legacygraph.terminology;

import io.github.legacygraph.entity.TerminologyMapping;
import io.github.legacygraph.repository.TerminologyMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ConfigurableTerminologyService 测试 —— 验证跨语言分词、术语映射与 token 重叠相似度。
 * 覆盖从 BusinessGraphBuilder 迁移的行为：中文术语翻译、camelCase 拆分、小集合守卫、兜底包含匹配。
 * 术语映射改为从 lg_terminology_mapping 表加载，测试通过 mock repository 提供术语行。
 */
class ConfigurableTerminologyServiceTest {

    private ConfigurableTerminologyService service;

    @BeforeEach
    void setUp() {
        TerminologyMappingRepository repository = mock(TerminologyMappingRepository.class);
        when(repository.selectList(any())).thenReturn(rows(
                mapping("出金", 1, "cash", "out"),
                mapping("审核", 2, "audit"),
                mapping("列表", 3, "list"),
                mapping("账户", 4, "account"),
                mapping("主账户", 5, "master", "account"),
                mapping("农信", 6, "nx")
        ));
        service = new ConfigurableTerminologyService(new TerminologyProperties(), repository);
        service.reload();
    }

    private static TerminologyMapping mapping(String source, int order, String... targets) {
        TerminologyMapping m = new TerminologyMapping();
        m.setSourceTerm(source);
        m.setTargetTerms(List.of(targets));
        m.setSortOrder(order);
        m.setStatus("ACTIVE");
        return m;
    }

    private static List<TerminologyMapping> rows(TerminologyMapping... items) {
        return new ArrayList<>(List.of(items));
    }

    @Test
    void tokenize_translatesChineseTermsToEnglishSubwords() {
        assertTrue(service.tokenize("出金审核列表").containsAll(List.of("cash", "out", "audit", "list")));
    }

    @Test
    void tokenize_splitsCamelCaseEnglish() {
        assertTrue(service.tokenize("getCashOutAuditList").containsAll(List.of("get", "cash", "out", "audit", "list")));
    }

    @Test
    void calculateSimilarity_crossLanguageMatchesAboveThreshold() {
        // 中文 Feature 名与英文 API 名经术语翻译后 token 高度重叠
        double score = service.calculateSimilarity("出金审核列表", "getCashOutAuditList");
        assertTrue(score > 0.6, "expected cross-language match > 0.6, got " + score);
    }

    @Test
    void calculateSimilarity_longestTermWinsForNestedTerms() {
        // "主账户" 应优先于 "账户"，翻出 master+account 而非仅 account
        assertTrue(service.tokenize("主账户").contains("master"));
    }

    @Test
    void calculateSimilarity_shortSingleTokenDoesNotFalseMatch() {
        // "农信" 仅翻出短 token {nx}(<4)，不应与含 nx 的英文名误判为完全匹配
        double score = service.calculateSimilarity("农信", "nxService");
        assertEquals(0.0, score, "short single token should not match");
    }

    @Test
    void calculateSimilarity_englishPluralStemFolding() {
        // order 与 orders 经词干归一折叠为同一 token
        double score = service.calculateSimilarity("order", "orders");
        assertEquals(1.0, score);
    }

    @Test
    void calculateSimilarity_blankReturnsZero() {
        assertEquals(0.0, service.calculateSimilarity("", "order"));
        assertEquals(0.0, service.calculateSimilarity(null, "order"));
    }

    @Test
    void calculateSimilarity_fallbackContainsMatch() {
        // 两侧无法翻译为英文 token（纯中文且无词典映射）时走包含关系兜底
        double score = service.calculateSimilarity("订单管理", "订单管理页面");
        assertEquals(0.7, score);
    }
}
