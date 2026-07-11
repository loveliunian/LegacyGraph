package io.github.legacygraph.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link QueryIntent} 单元测试 — 验证 CHANGE_IMPACT 意图与 GraphRAG 互斥。
 */
class QueryIntentTest {

    @Test
    void changeImpact_doesNotRequirePlanner() {
        assertFalse(QueryIntent.CHANGE_IMPACT.requiresPlanner(),
                "CHANGE_IMPACT 走变更影响专用链路，不触发 GraphRAG Planner");
    }

    @Test
    void changeImpact_requiresChangeImpact() {
        assertTrue(QueryIntent.CHANGE_IMPACT.requiresChangeImpact());
    }

    @Test
    void otherIntents_doNotRequireChangeImpact() {
        assertFalse(QueryIntent.FACT_LOOKUP.requiresChangeImpact());
        assertFalse(QueryIntent.STRUCTURAL.requiresChangeImpact());
        assertFalse(QueryIntent.RELATIONAL.requiresChangeImpact());
        assertFalse(QueryIntent.EXPLANATION.requiresChangeImpact());
    }

    @Test
    void changeImpact_depth3() {
        assertEquals(3, QueryIntent.CHANGE_IMPACT.getRecommendedGraphDepth());
    }

    @Test
    void structuralAndRelational_requirePlanner() {
        assertTrue(QueryIntent.STRUCTURAL.requiresPlanner());
        assertTrue(QueryIntent.RELATIONAL.requiresPlanner());
        assertTrue(QueryIntent.FACT_LOOKUP.requiresPlanner());
        assertTrue(QueryIntent.COMPARATIVE.requiresPlanner());
    }

    @Test
    void factLookupAndComparative_requirePlanner() {
        assertTrue(QueryIntent.FACT_LOOKUP.requiresPlanner(),
                "FACT_LOOKUP 走 GraphRAG Planner 获取结构化事实检索");
        assertTrue(QueryIntent.COMPARATIVE.requiresPlanner(),
                "COMPARATIVE 走 GraphRAG Planner 支持跨版本对比");
    }

    @Test
    void requiresPlannerReturnsTrueForSolutionEvaluation() {
        assertTrue(QueryIntent.SOLUTION_EVALUATION.requiresPlanner(),
                "SOLUTION_EVALUATION 走 GraphRAG Planner 评估方案上下文");
    }

    @Test
    void requiresDiagnosisReturnsTrueOnlyForDiagnosis() {
        assertTrue(QueryIntent.DIAGNOSIS.requiresDiagnosis(),
                "DIAGNOSIS 走问题诊断专用链路");
        assertFalse(QueryIntent.SOLUTION_EVALUATION.requiresDiagnosis());
        assertFalse(QueryIntent.CHANGE_IMPACT.requiresDiagnosis());
        assertFalse(QueryIntent.FACT_LOOKUP.requiresDiagnosis());
        assertFalse(QueryIntent.STRUCTURAL.requiresDiagnosis());
    }

    @Test
    void getRecommendedGraphDepthForNewIntents() {
        assertEquals(2, QueryIntent.SOLUTION_EVALUATION.getRecommendedGraphDepth(),
                "SOLUTION_EVALUATION 推荐深度 2");
        assertEquals(3, QueryIntent.DIAGNOSIS.getRecommendedGraphDepth(),
                "DIAGNOSIS 推荐深度 3（需更深路径分析根因）");
    }
}
