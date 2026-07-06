package io.github.legacygraph.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Graphify 审核规则引擎。
 * <p>
 * 按规则顺序匹配，第一个命中的规则决定决策。
 * 默认行为：无规则命中时返回 REQUIRE_MANUAL_REVIEW。
 * </p>
 * <p>
 * 规则管理约束：
 * - AMBIGUOUS 关系默认不能自动 confirmed
 * - INFERRED 关系只有在已有规则命中且 confidence >= minConfidence 时才可自动 confirmed
 * - 新规则只能从已审核候选关系生成，禁止凭空新增自动确认规则
 * </p>
 */
@Slf4j
@Service
public class GraphifyReviewRuleEngine {

    private final List<GraphifyReviewRule> rules;
    private final List<GraphifyReviewAudit> auditLog;

    public GraphifyReviewRuleEngine() {
        this.rules = new CopyOnWriteArrayList<>();
        this.auditLog = new CopyOnWriteArrayList<>();
    }

    public GraphifyReviewRuleEngine(List<GraphifyReviewRule> rules) {
        this.rules = new CopyOnWriteArrayList<>(rules);
        this.auditLog = new CopyOnWriteArrayList<>();
    }

    /**
     * 对给定的边类型、来源类型和置信度执行规则匹配。
     *
     * @param edgeType    边类型
     * @param sourceType  来源类型
     * @param confidence  置信度
     * @return 第一个命中规则的决策，无命中则返回 REQUIRE_MANUAL_REVIEW
     */
    public GraphifyReviewRule.Decision decide(String edgeType, String sourceType, double confidence) {
        for (GraphifyReviewRule rule : rules) {
            if (rule.matches(edgeType, sourceType, confidence)) {
                log.debug("Rule matched: ruleId={}, decision={} for edgeType={}, sourceType={}, confidence={}",
                    rule.ruleId(), rule.decision(), edgeType, sourceType, confidence);
                return rule.decision();
            }
        }
        log.debug("No rule matched for edgeType={}, sourceType={}, confidence={}, defaulting to REQUIRE_MANUAL_REVIEW",
            edgeType, sourceType, confidence);
        return GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW;
    }

    /**
     * 查找匹配给定边的第一个规则。
     *
     * @param edgeType    边类型
     * @param sourceType  来源类型
     * @param confidence  置信度
     * @return 匹配的规则，无匹配返回 null
     */
    public GraphifyReviewRule findMatchingRule(String edgeType, String sourceType, double confidence) {
        for (GraphifyReviewRule rule : rules) {
            if (rule.matches(edgeType, sourceType, confidence)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 添加审核规则。
     * <p>
     * 安全约束：
     * - AMBIGUOUS 来源类型不允许 CONFIRM 决策
     * - CONFIRM 规则必须来自已审核候选关系（通过 fromReviewedCandidate 参数控制）
     * </p>
     *
     * @param rule                  要添加的规则
     * @param fromReviewedCandidate 是否来自已审核候选关系
     * @throws IllegalArgumentException 如果规则违反安全约束
     */
    public void addRule(GraphifyReviewRule rule, boolean fromReviewedCandidate) {
        if (rule == null) {
            throw new IllegalArgumentException("rule 不能为空");
        }

        // AMBIGUOUS 来源类型不允许自动 CONFIRM
        if ("AMBIGUOUS".equals(rule.sourceType()) && rule.decision() == GraphifyReviewRule.Decision.CONFIRM) {
            throw new IllegalArgumentException("AMBIGUOUS 来源类型不允许自动确认规则");
        }

        // CONFIRM 规则必须来自已审核候选关系
        if (rule.decision() == GraphifyReviewRule.Decision.CONFIRM && !fromReviewedCandidate) {
            throw new IllegalArgumentException("自动确认规则只能从已审核候选关系生成");
        }

        rules.add(rule);
        log.info("Added review rule: ruleId={}, edgeType={}, sourceType={}, minConfidence={}, decision={}",
            rule.ruleId(), rule.edgeType(), rule.sourceType(), rule.minConfidence(), rule.decision());
    }

    /**
     * 添加审核规则（默认允许，不检查来源）。
     * 仅在测试和初始化配置时使用。
     */
    public void addRule(GraphifyReviewRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule 不能为空");
        }
        rules.add(rule);
    }

    /**
     * 记录审核审计日志。
     *
     * @param audit 审计记录
     */
    public void recordAudit(GraphifyReviewAudit audit) {
        if (audit == null) {
            throw new IllegalArgumentException("audit 不能为空");
        }
        auditLog.add(audit);
        log.info("Review audit: candidateId={}, previousStatus={}, newStatus={}, reviewer={}, ruleId={}",
            audit.candidateId(), audit.previousStatus(), audit.newStatus(), audit.reviewer(), audit.ruleId());
    }

    /**
     * 获取当前所有规则（不可变列表）。
     */
    public List<GraphifyReviewRule> getRules() {
        return Collections.unmodifiableList(new ArrayList<>(rules));
    }

    /**
     * 获取审核审计日志（不可变列表）。
     */
    public List<GraphifyReviewAudit> getAuditLog() {
        return Collections.unmodifiableList(new ArrayList<>(auditLog));
    }

    /**
     * 获取规则数量。
     */
    public int ruleCount() {
        return rules.size();
    }
}
