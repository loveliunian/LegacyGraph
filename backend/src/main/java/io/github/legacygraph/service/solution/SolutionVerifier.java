package io.github.legacygraph.service.solution;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.requirement.ImpactNode;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.solution.SolutionPlan;
import io.github.legacygraph.dto.solution.SolutionPlanStep;
import io.github.legacygraph.dto.solution.SolutionVerificationResult;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方案确定性校验服务（Task 10）。
 * <p>对 {@link SolutionPlan} 的步骤做 8 类确定性校验，输出 {@link SolutionVerificationResult}。
 * 校验通过则方案状态置为 READY_FOR_REVIEW；否则置为 NEEDS_INPUT 并记录错误。</p>
 *
 * <p>校验项：
 * <ol>
 *   <li>文件存在：步骤引用的 filePath 在图谱中存在对应节点（按 sourcePath 查询）</li>
 *   <li>符号存在：步骤引用的 symbolName（解析出类名）在图谱中存在对应节点</li>
 *   <li>高风险覆盖：影响子图中 DIRECT（depth=0）节点都被方案步骤覆盖（按 sourcePath 或 nodeName 匹配）</li>
 *   <li>测试覆盖：每个步骤的 testDescription 非空</li>
 *   <li>证据有效：步骤引用的 evidenceIds 在图谱中存在对应 Evidence 节点</li>
 *   <li>阻塞问题：需求分析中的 openQuestions 为空</li>
 *   <li>代码片段一致性：MODIFY/CREATE 步骤的 codeSnippet 非空，MODIFY 步骤中引用的类名/方法名在影响子图中</li>
 *   <li>成本/风险评估：estimatedCost 非空且 personDays > 0，riskAssessment 非空且 riskLevel 有效</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
public class SolutionVerifier {

    /** 节点查询上限（避免单次查询拉取过多） */
    private static final int NODE_QUERY_LIMIT = 10;

    /** 校验通过状态 */
    public static final String STATUS_PASSED = "READY_FOR_REVIEW";
    /** 校验失败状态 */
    public static final String STATUS_FAILED = "NEEDS_INPUT";

    private final Neo4jGraphDao neo4jGraphDao;

    public SolutionVerifier(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 对方案做确定性校验。
     *
     * @param projectId           项目 ID
     * @param versionId           扫描版本 ID（可为 null）
     * @param requirementAnalysis 需求分析结果（用于 openQuestions 检查）
     * @param impactResult        影响子图结果（用于高风险节点覆盖检查）
     * @param plan                待校验的方案计划
     * @return 校验结果（passed + status + errors）
     */
    public SolutionVerificationResult verify(String projectId, String versionId,
                                              RequirementAnalysis requirementAnalysis,
                                              ImpactResult impactResult,
                                              SolutionPlan plan) {
        SolutionVerificationResult result = new SolutionVerificationResult();
        List<SolutionVerificationResult.VerificationError> errors = new ArrayList<>();

        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            errors.add(new SolutionVerificationResult.VerificationError("EMPTY_PLAN", "方案步骤为空，无法校验"));
            return buildFailed(result, errors);
        }

        List<SolutionPlanStep> steps = plan.getSteps();

        // 1. 文件存在校验
        checkFileExists(projectId, versionId, steps, errors);

        // 2. 符号存在校验
        checkSymbolExists(projectId, versionId, steps, errors);

        // 3. 高风险覆盖校验
        checkHighRiskCoverage(projectId, versionId, impactResult, steps, errors);

        // 4. 测试覆盖校验
        checkTestCoverage(steps, errors);

        // 5. 证据有效性校验
        checkEvidenceValid(projectId, versionId, steps, errors);

        // 6. 阻塞问题校验
        checkBlockingQuestions(requirementAnalysis, errors);

        // 7. 代码片段一致性校验
        checkCodeSnippetConsistency(plan, impactResult, errors);

        // 8. 成本/风险评估校验
        checkCostAndRisk(plan, errors);

        if (errors.isEmpty()) {
            result.setPassed(true);
            result.setStatus(STATUS_PASSED);
            log.info("Solution verification passed: projectId={}, steps={}", projectId, steps.size());
        } else {
            result.setPassed(false);
            result.setStatus(STATUS_FAILED);
            log.info("Solution verification failed: projectId={}, errors={}", projectId, errors.size());
        }
        result.setErrors(errors);
        return result;
    }

    // ==================== 校验 1：文件存在 ====================

    /**
     * 检查步骤引用的 filePath 在图谱中存在对应节点（按 sourcePath 精确匹配）。
     * CREATE 类型的步骤允许文件不存在（新文件），MODIFY/DELETE 必须存在。
     */
    private void checkFileExists(String projectId, String versionId,
                                  List<SolutionPlanStep> steps, List<SolutionVerificationResult.VerificationError> errors) {
        for (int i = 0; i < steps.size(); i++) {
            SolutionPlanStep step = steps.get(i);
            String filePath = step.getFilePath();
            if (filePath == null || filePath.isBlank()) {
                errors.add(new SolutionVerificationResult.VerificationError("FILE_PATH_EMPTY",
                        String.format("步骤[%d] %s：filePath 为空", i, step.getTitle())));
                continue;
            }
            // CREATE 步骤：文件可以不存在（新建文件），跳过存在性检查
            if ("CREATE".equalsIgnoreCase(step.getActionType())) {
                continue;
            }
            // MODIFY / DELETE：必须在图谱中存在
            if (!fileExistsInGraph(projectId, versionId, filePath)) {
                errors.add(new SolutionVerificationResult.VerificationError("FILE_NOT_FOUND",
                        String.format("步骤[%d] %s：文件路径 %s 在图谱中不存在（MODIFY/DELETE 必须引用已存在文件）",
                                i, step.getTitle(), filePath)));
            }
        }
    }

    /**
     * 在图谱中按 sourcePath 精确查找节点，存在则返回 true。
     */
    private boolean fileExistsInGraph(String projectId, String versionId, String filePath) {
        try {
            // queryNodes(projectId, versionId, nodeType, nodeKey, sourceType, minConfidence, status, limit)
            // 无 sourcePath 过滤参数，用 executeReadQuery 兜底
            String cypher = "MATCH (n) WHERE n.projectId=$projectId " +
                    "AND ($versionId IS NULL OR n.versionId=$versionId) " +
                    "AND n.sourcePath=$filePath " +
                    "RETURN n.id AS id LIMIT 1";
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("versionId", versionId != null ? normalizeId(versionId) : null);
            params.put("filePath", filePath);
            List<Map<String, Object>> rows = neo4jGraphDao.executeReadQuery(cypher, params);
            return rows != null && !rows.isEmpty();
        } catch (Exception e) {
            log.warn("fileExistsInGraph failed for path={}: {}", filePath, e.getMessage());
            // 查询失败不阻塞校验，认为存在（避免 Neo4j 不可用时误判）
            return true;
        }
    }

    // ==================== 校验 2：符号存在 ====================

    /**
     * 检查步骤引用的 symbolName 在图谱中存在对应节点。
     * <p>symbolName 格式可能为 "ClassName" 或 "ClassName#methodName"，
     * 取类名部分按 nodeName 精确查找；CREATE 步骤允许符号不存在（新建符号）。</p>
     */
    private void checkSymbolExists(String projectId, String versionId,
                                    List<SolutionPlanStep> steps, List<SolutionVerificationResult.VerificationError> errors) {
        for (int i = 0; i < steps.size(); i++) {
            SolutionPlanStep step = steps.get(i);
            String symbol = step.getSymbolName();
            if (symbol == null || symbol.isBlank()) {
                errors.add(new SolutionVerificationResult.VerificationError("SYMBOL_NAME_EMPTY",
                        String.format("步骤[%d] %s：symbolName 为空", i, step.getTitle())));
                continue;
            }
            // CREATE 步骤：符号可以不存在（新建符号），跳过存在性检查
            if ("CREATE".equalsIgnoreCase(step.getActionType())) {
                continue;
            }
            String className = parseClassName(symbol);
            if (className == null || className.isBlank()) {
                continue;
            }
            if (!symbolExistsInGraph(projectId, versionId, className)) {
                errors.add(new SolutionVerificationResult.VerificationError("SYMBOL_NOT_FOUND",
                        String.format("步骤[%d] %s：符号 %s（类名 %s）在图谱中不存在",
                                i, step.getTitle(), symbol, className)));
            }
        }
    }

    /**
     * 从 symbolName 解析出类名部分（# 之前的内容）。
     * <p>如 "OrderService#exportLast30Days" → "OrderService"；
     * "OrderMapper" → "OrderMapper"。</p>
     */
    String parseClassName(String symbolName) {
        if (symbolName == null) {
            return null;
        }
        int hashIdx = symbolName.indexOf('#');
        String className = hashIdx > 0 ? symbolName.substring(0, hashIdx).trim() : symbolName.trim();
        // 兼容 FQN 形式 "com.example.OrderService#method" → 取最后一段
        int dotIdx = className.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < className.length() - 1) {
            className = className.substring(dotIdx + 1);
        }
        return className;
    }

    /**
     * 在图谱中按 nodeName 精确查找节点（任一类型匹配即存在）。
     */
    private boolean symbolExistsInGraph(String projectId, String versionId, String className) {
        try {
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, null, null, null,
                    null, null, NODE_QUERY_LIMIT);
            for (GraphNode n : nodes) {
                if (className.equals(n.getNodeName())) {
                    return true;
                }
            }
            // queryNodes 不一定能覆盖所有节点，再用 nodeName 精确查询兜底
            String cypher = "MATCH (n) WHERE n.projectId=$projectId " +
                    "AND ($versionId IS NULL OR n.versionId=$versionId) " +
                    "AND n.nodeName=$name " +
                    "RETURN n.id AS id LIMIT 1";
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("versionId", versionId != null ? normalizeId(versionId) : null);
            params.put("name", className);
            List<Map<String, Object>> rows = neo4jGraphDao.executeReadQuery(cypher, params);
            return rows != null && !rows.isEmpty();
        } catch (Exception e) {
            log.warn("symbolExistsInGraph failed for name={}: {}", className, e.getMessage());
            return true; // 查询失败不阻塞
        }
    }

    // ==================== 校验 3：高风险覆盖 ====================

    /**
     * 检查影响子图中 DIRECT（depth=0）节点都被方案步骤覆盖。
     * <p>覆盖判定：步骤的 filePath 等于节点的 sourcePath，
     * 或步骤的 symbolName 类名部分等于节点的 nodeName。</p>
     */
    private void checkHighRiskCoverage(String projectId, String versionId,
                                        ImpactResult impactResult,
                                        List<SolutionPlanStep> steps,
                                        List<SolutionVerificationResult.VerificationError> errors) {
        if (impactResult == null || impactResult.getImpactedNodes() == null
                || impactResult.getImpactedNodes().isEmpty()) {
            return; // 无影响节点，跳过
        }
        // 收集所有 DIRECT（depth=0）节点作为高风险节点
        List<ImpactNode> highRiskNodes = new ArrayList<>();
        for (ImpactNode n : impactResult.getImpactedNodes()) {
            if (n.getDepth() == 0 || "DIRECT".equalsIgnoreCase(n.getImpactType())) {
                highRiskNodes.add(n);
            }
        }
        if (highRiskNodes.isEmpty()) {
            return;
        }

        // 构建步骤覆盖集合：filePath + symbolName 类名
        Set<String> stepFilePaths = new HashSet<>();
        Set<String> stepClassNames = new HashSet<>();
        for (SolutionPlanStep step : steps) {
            if (step.getFilePath() != null && !step.getFilePath().isBlank()) {
                stepFilePaths.add(step.getFilePath());
            }
            String cn = parseClassName(step.getSymbolName());
            if (cn != null && !cn.isBlank()) {
                stepClassNames.add(cn);
            }
        }

        for (ImpactNode highRisk : highRiskNodes) {
            // 查询节点的 sourcePath
            String sourcePath = resolveNodeSourcePath(projectId, versionId, highRisk);
            boolean covered = false;
            if (sourcePath != null && stepFilePaths.contains(sourcePath)) {
                covered = true;
            }
            if (!covered && highRisk.getNodeName() != null
                    && stepClassNames.contains(highRisk.getNodeName())) {
                covered = true;
            }
            if (!covered) {
                errors.add(new SolutionVerificationResult.VerificationError("HIGH_RISK_UNCOVERED",
                        String.format("高风险节点未被方案步骤覆盖：%s（%s）",
                                highRisk.getNodeName(), highRisk.getNodeKey())));
            }
        }
    }

    /**
     * 查询节点的 sourcePath（ImpactNode 不带 sourcePath，按 nodeId 反查）。
     */
    private String resolveNodeSourcePath(String projectId, String versionId, ImpactNode node) {
        if (node == null || node.getNodeId() == null) {
            return null;
        }
        try {
            Optional<GraphNode> found = neo4jGraphDao.findNodeById(node.getNodeId());
            if (found.isPresent()) {
                return found.get().getSourcePath();
            }
        } catch (Exception e) {
            log.debug("resolveNodeSourcePath failed for nodeId={}: {}", node.getNodeId(), e.getMessage());
        }
        return null;
    }

    // ==================== 校验 4：测试覆盖 ====================

    /**
     * 检查每个步骤的 testDescription 非空。
     */
    private void checkTestCoverage(List<SolutionPlanStep> steps, List<SolutionVerificationResult.VerificationError> errors) {
        for (int i = 0; i < steps.size(); i++) {
            SolutionPlanStep step = steps.get(i);
            if (step.getTestDescription() == null || step.getTestDescription().isBlank()) {
                errors.add(new SolutionVerificationResult.VerificationError("TEST_MISSING",
                        String.format("步骤[%d] %s：缺少 testDescription", i, step.getTitle())));
            }
        }
    }

    // ==================== 校验 5：证据有效 ====================

    /**
     * 检查步骤引用的 evidenceIds 在图谱中存在对应 Evidence 节点。
     * <p>查询图谱中 nodeType=Evidence 且 id 或 nodeKey 匹配的节点。
     * 查询失败时不判定为错误（避免 Neo4j 不可用时误判）。</p>
     */
    private void checkEvidenceValid(String projectId, String versionId,
                                     List<SolutionPlanStep> steps, List<SolutionVerificationResult.VerificationError> errors) {
        for (int i = 0; i < steps.size(); i++) {
            SolutionPlanStep step = steps.get(i);
            if (step.getEvidenceIds() == null || step.getEvidenceIds().isEmpty()) {
                continue; // 无证据引用，跳过
            }
            for (String evidenceId : step.getEvidenceIds()) {
                if (evidenceId == null || evidenceId.isBlank()) {
                    continue;
                }
                if (!evidenceExistsInGraph(projectId, versionId, evidenceId)) {
                    errors.add(new SolutionVerificationResult.VerificationError("EVIDENCE_INVALID",
                            String.format("步骤[%d] %s：证据 ID %s 在图谱中不存在",
                                    i, step.getTitle(), evidenceId)));
                }
            }
        }
    }

    /**
     * 在图谱中查找 Evidence 节点（按 nodeKey 或 id 匹配）。
     */
    private boolean evidenceExistsInGraph(String projectId, String versionId, String evidenceId) {
        try {
            // 先按 nodeKey 精确查找 Evidence 节点
            List<GraphNode> byKey = neo4jGraphDao.queryNodes(
                    projectId, versionId, NodeType.Evidence.name(),
                    evidenceId, null, null, null, 1);
            if (byKey != null && !byKey.isEmpty()) {
                return true;
            }
            // 兜底：按 id 查找
            Optional<GraphNode> byId = neo4jGraphDao.findNodeById(evidenceId);
            if (byId.isPresent() && NodeType.Evidence.name().equalsIgnoreCase(byId.get().getNodeType())) {
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("evidenceExistsInGraph failed for id={}: {}", evidenceId, e.getMessage());
            return true; // 查询失败不阻塞
        }
    }

    // ==================== 校验 6：阻塞问题 ====================

    /**
     * 检查需求分析中的 openQuestions 为空。
     */
    private void checkBlockingQuestions(RequirementAnalysis analysis, List<SolutionVerificationResult.VerificationError> errors) {
        if (analysis == null) {
            return;
        }
        List<String> openQuestions = analysis.getOpenQuestions();
        if (openQuestions != null && !openQuestions.isEmpty()) {
            errors.add(new SolutionVerificationResult.VerificationError("BLOCKING_QUESTIONS",
                    String.format("需求存在 %d 个未解决的开放问题（openQuestions）", openQuestions.size())));
        }
    }

    // ==================== 校验 7：代码片段一致性 ====================

    /**
     * 检查步骤的 codeSnippet 一致性。
     * <p>MODIFY/CREATE 步骤必须有 codeSnippet；MODIFY 步骤中提取的类名/方法名
     * 应在影响子图的 impactedNodes 中（对比 nodeName）。DELETE 步骤不校验。</p>
     *
     * @param plan         方案计划
     * @param impactResult 影响子图结果（用于 impactedNodes nodeName 对比）
     * @param errors       错误收集列表
     */
    void checkCodeSnippetConsistency(SolutionPlan plan, ImpactResult impactResult,
                                      List<SolutionVerificationResult.VerificationError> errors) {
        if (plan == null || plan.getSteps() == null) {
            return;
        }
        // 收集 impactedNodes 中的 nodeName 集合
        Set<String> impactedNames = new HashSet<>();
        if (impactResult != null && impactResult.getImpactedNodes() != null) {
            for (ImpactNode n : impactResult.getImpactedNodes()) {
                if (n.getNodeName() != null && !n.getNodeName().isBlank()) {
                    impactedNames.add(n.getNodeName());
                }
            }
        }
        boolean hasImpact = !impactedNames.isEmpty();

        List<SolutionPlanStep> steps = plan.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            SolutionPlanStep step = steps.get(i);
            String action = step.getActionType();
            String snippet = step.getCodeSnippet();

            // DELETE 步骤不需要代码片段
            if ("DELETE".equalsIgnoreCase(action)) {
                continue;
            }

            // CREATE / MODIFY 都需要非空 codeSnippet
            if (snippet == null || snippet.isBlank()) {
                errors.add(new SolutionVerificationResult.VerificationError("CODE_SNIPPET_EMPTY",
                        String.format("步骤[%d] %s：%s 步骤缺少 codeSnippet", i, step.getTitle(), action)));
                continue;
            }

            // CREATE 步骤不做符号存在性校验（新建符号）
            if ("CREATE".equalsIgnoreCase(action)) {
                continue;
            }

            // MODIFY：检查提取的类名/方法名是否在 impactedNodes 中
            if (!"MODIFY".equalsIgnoreCase(action)) {
                continue;
            }
            // 无影响分析数据时无法校验一致性，跳过
            if (!hasImpact) {
                continue;
            }
            Set<String> names = extractClassAndMethodNames(snippet);
            for (String name : names) {
                if (!impactedNames.contains(name)) {
                    errors.add(new SolutionVerificationResult.VerificationError("CODE_SNIPPET_INCONSISTENT",
                            String.format("步骤[%d] %s：代码片段中的名称 %s 不在影响子图的 impactedNodes 中",
                                    i, step.getTitle(), name)));
                }
            }
        }
    }

    /**
     * 从代码片段中提取类名和方法名。
     * <p>类名匹配 {@code class XXX} / {@code interface XXX}；
     * 方法名匹配 {@code (public|private|protected|static) RETURNTYPE methodName(}。</p>
     *
     * @param codeSnippet 代码片段
     * @return 提取到的所有名称（类名 + 方法名）
     */
    Set<String> extractClassAndMethodNames(String codeSnippet) {
        Set<String> names = new HashSet<>();
        if (codeSnippet == null || codeSnippet.isBlank()) {
            return names;
        }
        Pattern classPattern = Pattern.compile("(?:class|interface)\\s+(\\w+)");
        Matcher cm = classPattern.matcher(codeSnippet);
        while (cm.find()) {
            names.add(cm.group(1));
        }
        Pattern methodPattern = Pattern.compile("(?:public|private|protected|static)\\s+\\w+\\s+(\\w+)\\s*\\(");
        Matcher mm = methodPattern.matcher(codeSnippet);
        while (mm.find()) {
            names.add(mm.group(1));
        }
        return names;
    }

    // ==================== 校验 8：成本/风险评估 ====================

    /**
     * 检查方案包含有效的成本估算和风险评估。
     * <p>estimatedCost 非空且 personDays > 0；
     * riskAssessment 非空且 riskLevel 为 LOW/MEDIUM/HIGH 之一。</p>
     */
    void checkCostAndRisk(SolutionPlan plan,
                           List<SolutionVerificationResult.VerificationError> errors) {
        if (plan == null) {
            return;
        }
        // 成本估算校验
        SolutionPlan.CostEstimate cost = plan.getEstimatedCost();
        if (cost == null) {
            errors.add(new SolutionVerificationResult.VerificationError("COST_ESTIMATE_MISSING",
                    "方案缺少 estimatedCost（成本估算）"));
        } else if (cost.getPersonDays() <= 0) {
            errors.add(new SolutionVerificationResult.VerificationError("COST_ESTIMATE_MISSING",
                    "成本估算 personDays 必须 > 0，当前值：" + cost.getPersonDays()));
        }
        // 风险评估校验
        SolutionPlan.RiskAssessment risk = plan.getRiskAssessment();
        if (risk == null) {
            errors.add(new SolutionVerificationResult.VerificationError("RISK_ASSESSMENT_MISSING",
                    "方案缺少 riskAssessment（风险评估）"));
        } else if (risk.getRiskLevel() == null || risk.getRiskLevel().isBlank()) {
            errors.add(new SolutionVerificationResult.VerificationError("RISK_ASSESSMENT_MISSING",
                    "风险评估 riskLevel 不能为空"));
        } else {
            String level = risk.getRiskLevel().toUpperCase();
            if (!level.equals("LOW") && !level.equals("MEDIUM") && !level.equals("HIGH")) {
                errors.add(new SolutionVerificationResult.VerificationError("RISK_ASSESSMENT_MISSING",
                        "风险等级 riskLevel 无效：" + risk.getRiskLevel() + "（应为 LOW/MEDIUM/HIGH）"));
            }
        }
    }

    // ==================== 辅助方法 ====================

    private SolutionVerificationResult buildFailed(SolutionVerificationResult result, List<SolutionVerificationResult.VerificationError> errors) {
        result.setPassed(false);
        result.setStatus(STATUS_FAILED);
        result.setErrors(errors);
        return result;
    }

    private static String normalizeId(String id) {
        if (id == null) return null;
        return id.replace("-", "");
    }
}
