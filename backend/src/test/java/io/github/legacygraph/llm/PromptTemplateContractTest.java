package io.github.legacygraph.llm;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板契约测试 — Phase 0 关键交付物。
 *
 * <p>验证每个生产 prompt 模板声明的变量，都能被对应 Agent 实际传入的变量完整填充；
 * 渲染后不应残留任何 {var} 占位符。这能在编译期之外捕获"模板变量与 Agent 传参不一致"的契约漂移。</p>
 */
class PromptTemplateContractTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)\\}");

    private final PromptTemplateLoader loader = newLoader();

    private static PromptTemplateLoader newLoader() {
        // DB 中无模板时回退到 classpath 文件（契约测试针对生产 .txt 模板）
        io.github.legacygraph.service.PromptTemplateService svc =
                org.mockito.Mockito.mock(io.github.legacygraph.service.PromptTemplateService.class);
        org.mockito.Mockito.when(svc.getActiveByCode(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        return new PromptTemplateLoader(svc);
    }

    /** 提取模板中出现的所有占位符变量名（基于 classpath 直接读取生产模板文件） */
    private Set<String> placeholdersOf(String templateName) {
        String path = "/prompts/" + templateName + ".txt";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assertNotNull(is, "生产模板缺失: " + path);
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> vars = new HashSet<>();
            Matcher m = PLACEHOLDER.matcher(content);
            while (m.find()) {
                vars.add(m.group(1));
            }
            return vars;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 用给定变量集渲染后，断言无残留占位符 */
    private void assertFullyRendered(String templateName, Map<String, String> vars) {
        String rendered = loader.render(templateName, vars);
        Matcher m = PLACEHOLDER.matcher(rendered);
        if (m.find()) {
            // 收集所有残留，便于定位
            StringBuilder leftover = new StringBuilder();
            do {
                leftover.append(m.group(0)).append(" ");
            } while (m.find());
            fail("模板 [" + templateName + "] 渲染后仍存在未填充占位符: " + leftover.toString().trim());
        }
    }

    private Map<String, String> mapOfKeys(Set<String> keys) {
        Map<String, String> vars = new HashMap<>();
        for (String k : keys) {
            vars.put(k, "X");
        }
        return vars;
    }

    @Test
    void codeFactExtraction_variablesCoveredByAgent() {
        // CodeFactAgent 传入: projectId, codeContent, sourcePath
        Set<String> agentVars = Set.of("projectId", "codeContent", "sourcePath");
        Set<String> templateVars = placeholdersOf("code-fact-extraction");
        assertTrue(agentVars.containsAll(templateVars),
                "code-fact-extraction 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("code-fact-extraction", mapOfKeys(agentVars));
    }

    @Test
    void docUnderstanding_variablesCoveredByAgent() {
        // DocUnderstandingAgent 传入: docContent, sourcePath
        Set<String> agentVars = Set.of("docContent", "sourcePath");
        Set<String> templateVars = placeholdersOf("doc-understanding");
        assertTrue(agentVars.containsAll(templateVars),
                "doc-understanding 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("doc-understanding", mapOfKeys(agentVars));
    }

    @Test
    void featureMapping_variablesCoveredByAgent() {
        // FeatureMappingAgent 传入: vueCode, apiDefinitions, controllerCode, permissionInfo, productDoc
        Set<String> agentVars = Set.of("vueCode", "apiDefinitions", "controllerCode",
                "permissionInfo", "productDoc");
        Set<String> templateVars = placeholdersOf("feature-mapping");
        assertTrue(agentVars.containsAll(templateVars),
                "feature-mapping 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("feature-mapping", mapOfKeys(agentVars));
    }

    @Test
    void graphMergeDecision_variablesCoveredByAgent() {
        // GraphMergeAgent 传入: candidateAKey/candidateAInfo/candidateBKey/candidateBInfo + 5 个分数
        Set<String> agentVars = Set.of("candidateAKey", "candidateAInfo", "candidateBKey",
                "candidateBInfo", "nameScore", "semanticScore", "structScore",
                "neighborScore", "evidenceScore");
        Set<String> templateVars = placeholdersOf("graph-merge-decision");
        assertTrue(agentVars.containsAll(templateVars),
                "graph-merge-decision 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("graph-merge-decision", mapOfKeys(agentVars));
    }

    @Test
    void reviewSuggestion_variablesCoveredByAgent() {
        // ReviewAgent 传入: targetType, targetDescription, supportingEvidence, conflictingEvidence, currentConfidence
        Set<String> agentVars = Set.of("targetType", "targetDescription", "supportingEvidence",
                "conflictingEvidence", "currentConfidence");
        Set<String> templateVars = placeholdersOf("review-suggestion");
        assertTrue(agentVars.containsAll(templateVars),
                "review-suggestion 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("review-suggestion", mapOfKeys(agentVars));
    }

    @Test
    void testCaseGeneration_variablesCoveredByAgent() {
        // TestCaseAgent 传入: featureKey, featureName, apiEndpoint, httpMethod, requestSchema, relatedTables, businessRules
        Set<String> agentVars = Set.of("featureKey", "featureName", "apiEndpoint", "httpMethod",
                "requestSchema", "relatedTables", "businessRules");
        Set<String> templateVars = placeholdersOf("test-case-generation");
        assertTrue(agentVars.containsAll(templateVars),
                "test-case-generation 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("test-case-generation", mapOfKeys(agentVars));
    }

    @Test
    void qaAnswer_variablesCoveredByAgent() {
        // QaAgent 传入: question, context
        Set<String> agentVars = Set.of("question", "context");
        Set<String> templateVars = placeholdersOf("qa-answer");
        assertTrue(agentVars.containsAll(templateVars),
                "qa-answer 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("qa-answer", mapOfKeys(agentVars));
    }

    @Test
    void sqlAdvisor_variablesCoveredByAgent() {
        // SqlAdvisorAgent 传入: sqlKey, sql, schemaInfo
        Set<String> agentVars = Set.of("sqlKey", "sql", "schemaInfo");
        Set<String> templateVars = placeholdersOf("sql-advisor");
        assertTrue(agentVars.containsAll(templateVars),
                "sql-advisor 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("sql-advisor", mapOfKeys(agentVars));
    }

    @Test
    void testFailureAnalysis_variablesCoveredByAgent() {
        // TestFailureAnalysisAgent 传入: caseName, targetNode, request, response, errorMessage, graphPath, recentTrace
        Set<String> agentVars = Set.of("caseName", "targetNode", "request", "response",
                "errorMessage", "graphPath", "recentTrace");
        Set<String> templateVars = placeholdersOf("test-failure-analysis");
        assertTrue(agentVars.containsAll(templateVars),
                "test-failure-analysis 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("test-failure-analysis", mapOfKeys(agentVars));
    }

    @Test
    void reportInsight_variablesCoveredByAgent() {
        // ReportInsightAgent 传入: metrics, gaps
        Set<String> agentVars = Set.of("metrics", "gaps");
        Set<String> templateVars = placeholdersOf("report-insight");
        assertTrue(agentVars.containsAll(templateVars),
                "report-insight 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("report-insight", mapOfKeys(agentVars));
    }

    @Test
    void refactorSuggestion_variablesCoveredByAgent() {
        Set<String> agentVars = Set.of("target", "smellType", "code");
        Set<String> templateVars = placeholdersOf("refactor-suggestion");
        assertTrue(agentVars.containsAll(templateVars),
                "refactor-suggestion 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("refactor-suggestion", mapOfKeys(agentVars));
    }

    @Test
    void changeImpact_variablesCoveredByAgent() {
        Set<String> agentVars = Set.of("changeTarget", "changeDescription", "dependencies");
        Set<String> templateVars = placeholdersOf("change-impact");
        assertTrue(agentVars.containsAll(templateVars),
                "change-impact 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("change-impact", mapOfKeys(agentVars));
    }

    @Test
    void migrationConvert_variablesCoveredByAgent() {
        Set<String> agentVars = Set.of("migrationDirection", "sourcePath", "code", "customRules");
        Set<String> templateVars = placeholdersOf("migration-convert");
        assertTrue(agentVars.containsAll(templateVars),
                "migration-convert 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("migration-convert", mapOfKeys(agentVars));
    }

    @Test
    void prDescription_variablesCoveredByAgent() {
        Set<String> agentVars = Set.of("branch", "issue", "diff");
        Set<String> templateVars = placeholdersOf("pr-description");
        assertTrue(agentVars.containsAll(templateVars),
                "pr-description 含 Agent 未提供的变量: " + diff(templateVars, agentVars));
        assertFullyRendered("pr-description", mapOfKeys(agentVars));
    }

    private Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> d = new HashSet<>(a);
        d.removeAll(b);
        return d;
    }
}
