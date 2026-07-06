package io.github.legacygraph.query;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.governance.GraphifyAccessPolicy;
import io.github.legacygraph.governance.GraphifyProvenanceRedactor;
import io.github.legacygraph.governance.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Graphify 问答服务，为 Agent/RAG 提供受控的图谱查询能力
 */
@Slf4j
@Service
public class GraphifyQuestionService {

    private static final List<String> DEFAULT_SOURCE_TYPES = List.of("GRAPHIFY_AST", "GRAPHIFY_SEMANTIC");
    private static final Set<String> STOP_WORDS = Set.of(
        "what", "which", "where", "when", "why", "how", "the", "and", "or",
        "is", "are", "of", "to", "in", "for", "with", "show", "me"
    );

    private final GraphifyAccessPolicy accessPolicy;
    private final GraphifyProvenanceRedactor provenanceRedactor;
    private final Neo4jGraphDao graphDao;

    public GraphifyQuestionService(GraphifyAccessPolicy accessPolicy,
                                   GraphifyProvenanceRedactor provenanceRedactor) {
        this(accessPolicy, provenanceRedactor, null);
    }

    @Autowired
    public GraphifyQuestionService(GraphifyAccessPolicy accessPolicy,
                                   GraphifyProvenanceRedactor provenanceRedactor,
                                   Neo4jGraphDao graphDao) {
        this.accessPolicy = accessPolicy;
        this.provenanceRedactor = provenanceRedactor;
        this.graphDao = graphDao;
    }

    /**
     * 回答 Agent/RAG 提出的问题
     */
    public GraphifyQuestionAnswer answer(GraphifyQuestionRequest request) {
        long startTime = System.currentTimeMillis();
        
        // 空问题检查
        if (request.question() == null || request.question().isBlank()) {
            logQuery(request, "Question is empty.", 0.0, 0, Instant.now());
            return GraphifyQuestionAnswer.emptyQuestion();
        }

        // 解析调用方角色
        Set<Role> roles = parseRoles(request.callerRoles());

        // 检查权限
        if (!accessPolicy.canViewGraph(roles)) {
            logQuery(request, "Access denied", 0.0, 0, Instant.now());
            return new GraphifyQuestionAnswer(
                "Access denied: insufficient permissions to query graph.",
                Set.of(),
                List.of(),
                0.0,
                List.of("User lacks GRAPH_VIEWER role")
            );
        }

        try {
            QueryResult result = queryGraph(request, roles);
            
            // 脱敏源码路径
            List<String> redactedPaths = result.sourcePaths.stream()
                .map(path -> {
                    if (accessPolicy.canViewRawEvidence(roles)) {
                        return path;  // 有权限查看原始路径
                    } else {
                        return provenanceRedactor.redactPath(path);  // 脱敏
                    }
                })
                .collect(Collectors.toList());

            // 限制证据数量
            Set<String> limitedEvidence = result.evidenceIds.stream()
                .limit(request.maxEvidence())
                .collect(Collectors.toSet());

            GraphifyQuestionAnswer answer = new GraphifyQuestionAnswer(
                result.answer,
                limitedEvidence,
                redactedPaths,
                result.confidence,
                result.warnings
            );

            long durationMs = System.currentTimeMillis() - startTime;
            logQuery(request, answer.answer(), answer.confidence(), answer.evidenceIds().size(), Instant.now());
            log.info("Query completed in {}ms: projectId={}, confidence={}, evidenceCount={}",
                durationMs, request.projectId(), answer.confidence(), answer.evidenceIds().size());

            return answer;

        } catch (Exception e) {
            log.error("Query failed: projectId={}, question={}", request.projectId(), request.question(), e);
            return new GraphifyQuestionAnswer(
                "Query failed: " + e.getMessage(),
                Set.of(),
                List.of(),
                0.0,
                List.of("Internal error: " + e.getClass().getSimpleName())
            );
        }
    }

    /**
     * 从 Neo4j 查询 Graphify 证据节点。
     */
    private QueryResult queryGraph(GraphifyQuestionRequest request, Set<Role> roles) {
        if (graphDao == null) {
            return new QueryResult(
                GraphifyQuestionAnswer.noEvidenceFound("graph store is not configured").answer(),
                Set.of(),
                List.of(),
                0.2,
                List.of("Low confidence due to missing graph store")
            );
        }

        List<String> sourceTypes = normalizeSourceTypes(request.allowedSourceTypes());
        List<String> tokens = tokenizeQuestion(request.question());
        int fetchLimit = Math.max(10, request.maxEvidence() * 3);

        List<GraphNode> candidates = new ArrayList<>();
        for (String sourceType : sourceTypes) {
            candidates.addAll(graphDao.queryNodes(
                request.projectId(),
                null,
                null,
                null,
                sourceType,
                null,
                null,
                fetchLimit
            ));
        }

        List<ScoredNode> matchedNodes = candidates.stream()
            .map(node -> new ScoredNode(node, score(node, tokens)))
            .filter(scored -> scored.score() > 0)
            .sorted(Comparator
                .comparingInt(ScoredNode::score).reversed()
                .thenComparing(scored -> safe(scored.node().getNodeKey())))
            .limit(request.maxEvidence())
            .toList();

        if (matchedNodes.isEmpty()) {
            return new QueryResult(
                GraphifyQuestionAnswer.noEvidenceFound("no matching Graphify nodes").answer(),
                Set.of(),
                List.of(),
                0.2,
                List.of("Low confidence due to insufficient evidence")
            );
        }

        Set<String> evidenceIds = matchedNodes.stream()
            .map(ScoredNode::node)
            .map(this::evidenceId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> sourcePaths = matchedNodes.stream()
            .map(ScoredNode::node)
            .map(GraphNode::getSourcePath)
            .filter(path -> path != null && !path.isBlank())
            .distinct()
            .toList();
        double confidence = matchedNodes.stream()
            .map(ScoredNode::node)
            .map(GraphNode::getConfidence)
            .filter(Objects::nonNull)
            .mapToDouble(BigDecimal::doubleValue)
            .average()
            .orElse(0.6);
        confidence = Math.max(0.1, Math.min(1.0, confidence));

        String evidenceSummary = matchedNodes.stream()
            .map(ScoredNode::node)
            .map(this::describeNode)
            .collect(Collectors.joining("; "));

        return new QueryResult(
            "Found " + matchedNodes.size() + " graph evidence item(s): " + evidenceSummary,
            evidenceIds,
            sourcePaths,
            confidence,
            List.of()
        );
    }

    private List<String> normalizeSourceTypes(List<String> requestedSourceTypes) {
        List<String> sourceTypes = requestedSourceTypes == null ? List.of() : requestedSourceTypes.stream()
            .filter(type -> type != null && !type.isBlank())
            .distinct()
            .toList();
        return sourceTypes.isEmpty() ? DEFAULT_SOURCE_TYPES : sourceTypes;
    }

    private List<String> tokenizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        return Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_:$./-]+"))
            .map(String::trim)
            .filter(token -> token.length() >= 2)
            .filter(token -> !STOP_WORDS.contains(token))
            .distinct()
            .toList();
    }

    private int score(GraphNode node, List<String> tokens) {
        if (node == null || tokens.isEmpty()) {
            return 0;
        }
        String haystack = String.join(" ",
            safe(node.getNodeKey()),
            safe(node.getNodeName()),
            safe(node.getDisplayName()),
            safe(node.getDescription()),
            safe(node.getSourcePath()),
            safe(node.getClassName())
        ).toLowerCase(Locale.ROOT);

        int score = 0;
        for (String token : tokens) {
            if (haystack.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private String evidenceId(GraphNode node) {
        if (node.getId() != null && !node.getId().isBlank()) {
            return node.getId();
        }
        if (node.getNodeKey() != null && !node.getNodeKey().isBlank()) {
            return node.getNodeKey();
        }
        return null;
    }

    private String describeNode(GraphNode node) {
        String label = firstNonBlank(node.getDisplayName(), node.getNodeName(), node.getNodeKey(), node.getId());
        String type = firstNonBlank(node.getNodeType(), node.getSourceType(), "node");
        String path = firstNonBlank(node.getSourcePath(), "[unknown-path]");
        return type + " " + label + " at " + path;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 解析角色字符串为 Role 枚举
     */
    private Set<Role> parseRoles(Set<String> roleStrings) {
        return roleStrings.stream()
            .map(s -> {
                try {
                    return Role.valueOf(s);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown role: {}", s);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    /**
     * 记录查询日志
     */
    private void logQuery(GraphifyQuestionRequest request, String answer, 
                         double confidence, int evidenceCount, Instant timestamp) {
        // 实际应写入数据库或日志系统
        log.debug("Query log: projectId={}, roles={}, confidence={}, evidenceCount={}, timestamp={}",
            request.projectId(), request.callerRoles(), confidence, evidenceCount, timestamp);
    }

    /**
     * 内部查询结果
     */
    private record QueryResult(
        String answer,
        Set<String> evidenceIds,
        List<String> sourcePaths,
        double confidence,
        List<String> warnings
    ) {}

    private record ScoredNode(GraphNode node, int score) {}
}
