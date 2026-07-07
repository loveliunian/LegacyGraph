package io.github.legacygraph.builder;

import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.DocChunk;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.DocChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 业务图谱构建器
 * 将文档抽取的业务事实构建为业务图谱节点和关系（直写 Neo4j）
 * 所有节点/边创建时自动关联证据（证据仍走 PostgreSQL）
 */
@Slf4j
@Component
public class BusinessGraphBuilder {

    private final Neo4jGraphDao neo4jGraphDao;
    private final DocChunkRepository docChunkRepository;
    private final EvidenceGraphWriter writer;
    private final FeatureIdentityNormalizer featureIdentityNormalizer;

    public BusinessGraphBuilder(Neo4jGraphDao neo4jGraphDao,
                              DocChunkRepository docChunkRepository,
                              EvidenceGraphWriter writer,
                              FeatureIdentityNormalizer featureIdentityNormalizer) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.docChunkRepository = docChunkRepository;
        this.writer = writer;
        this.featureIdentityNormalizer = featureIdentityNormalizer;
    }

    /**
     * 保存文档切片
     */
    @Transactional
    public void saveDocumentChunks(String projectId, String versionId, String docName, String docPath,
            List<io.github.legacygraph.extractors.DocumentExtractor.DocumentChunk> chunks) {
        for (var chunk : chunks) {
            DocChunk dc = new DocChunk();
            dc.setId(UUID.randomUUID().toString());
            dc.setProjectId(projectId);
            dc.setVersionId(versionId);
            dc.setDocName(docName);
            dc.setDocPath(docPath);
            dc.setChunkIndex(chunk.getIndex());
            dc.setTitlePath(chunk.getTitlePath());
            dc.setContent(chunk.getContent());
            dc.setTokenCount(chunk.getTokenCount());
            dc.setCreatedAt(LocalDateTime.now());
            docChunkRepository.insert(dc);
        }
        log.info("Saved {} document chunks for {}", chunks.size(), docName);
    }

    /**
     * 构建业务图谱节点
     * 自动创建证据关联，并将业务流程关联到所属业务域（按顺序匹配）
     */
    @Transactional
    public void buildBusinessGraph(String projectId, String versionId, DocUnderstandingAgent.BusinessFactExtraction facts) {
        buildBusinessGraph(projectId, versionId, facts, null, SourceType.DOC_AI.name());
    }

    /**
     * 构建业务图谱节点，并保留文档来源路径用于 AI 证据追溯。
     */
    @Transactional
    public void buildBusinessGraph(String projectId, String versionId,
                                   DocUnderstandingAgent.BusinessFactExtraction facts,
                                   String sourcePath) {
        buildBusinessGraph(projectId, versionId, facts, sourcePath, SourceType.DOC_AI.name());
    }

    /**
     * 构建业务图谱节点，显式指定来源类型和来源路径。
     *
     * @param sourceType 节点来源类型（DOC_AI / CODE_AI），用于区分文档抽取与代码抽取的事实来源
     * @param sourcePath 来源文件路径（可为 null）
     */
    @Transactional
    public void buildBusinessGraph(String projectId, String versionId,
                                   DocUnderstandingAgent.BusinessFactExtraction facts,
                                   String sourcePath,
                                   String sourceType) {
        List<GraphNode> domainNodes = new ArrayList<>();

        // 构建业务域（先存列表用于后续关联）
        for (var domain : facts.getBusinessDomains()) {
            GraphNode domainNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.BusinessDomain.name(),
                    domain.getName(),
                    domain.getName(),
                    domain.getName(),
                    domain.getDescription(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(domain.getConfidence()),
                    domain.getConfidence() >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
            );
            domainNodes.add(domainNode);
        }

        // 构建业务流程
        // 不再轮询分配到业务域：LLM 输出中有明确 domain 属性时才建确定边。
        // 当前 BusinessProcess 无 domain 字段，因此流程节点保持孤立，状态 PENDING_CONFIRM 等待用户确认。
        for (var process : facts.getBusinessProcesses()) {
            GraphNode processNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.BusinessProcess.name(),
                    process.getName(),
                    process.getName(),
                    process.getName(),
                    process.getDescription(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(process.getConfidence()),
                    process.getConfidence() >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
            );

            // P1-A：流程本身即一个粗粒度功能，落 Feature 节点并建 CONTAINS 边。
            // 不再要求 steps 非空 —— 避免 LLM 未吐 steps 时一个 Feature 都不产生。
            String processFeatureKey = normalizeFeatureKey(process.getName());
            GraphNode processFeatureNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.Feature.name(),
                    processFeatureKey,
                    process.getName(),
                    process.getName(),
                    process.getDescription(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(process.getConfidence() * 0.9),
                    NodeStatus.PENDING_CONFIRM
            );
            createEdge(projectId, versionId,
                    processNode.getId(), processFeatureNode.getId(),
                    EdgeType.CONTAINS.name(),
                    processNode.getNodeKey() + "->contains->" + processFeatureNode.getNodeKey(),
                    sourceType,
                    BigDecimal.valueOf(process.getConfidence() * 0.9),
                    NodeStatus.PENDING_CONFIRM
            );

            // steps 存在时再派生细粒度子功能
            if (process.getSteps() != null) {
                for (String step : process.getSteps()) {
                    if (step == null || step.isBlank()) {
                        continue;
                    }
                    String stepFeatureKey = normalizeFeatureKey(process.getName() + "/" + step);
                    GraphNode featureNode = findOrCreateNode(
                            projectId, versionId,
                            NodeType.Feature.name(),
                            stepFeatureKey,
                            step,
                            step,
                            null,
                            sourceType,
                            sourcePath,
                            null,
                            null,
                            BigDecimal.valueOf(process.getConfidence() * 0.9),
                            NodeStatus.PENDING_CONFIRM
                    );
                    // 业务流程包含步骤功能
                    createEdge(projectId, versionId,
                            processNode.getId(), featureNode.getId(),
                            EdgeType.CONTAINS.name(),
                            processNode.getNodeKey() + "->contains->" + featureNode.getNodeKey(),
                            sourceType,
                            BigDecimal.valueOf(process.getConfidence() * 0.9),
                            NodeStatus.PENDING_CONFIRM
                    );
                }
            }
        }

        // 构建业务对象
        for (var obj : facts.getBusinessObjects()) {
            findOrCreateNode(
                    projectId, versionId,
                    NodeType.BusinessObject.name(),
                    obj.getName(),
                    obj.getName(),
                    obj.getName(),
                    obj.getDescription(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(obj.getConfidence()),
                    obj.getConfidence() >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
            );
        }

        // 构建业务规则
        for (var rule : facts.getBusinessRules()) {
            findOrCreateNode(
                    projectId, versionId,
                    NodeType.BusinessRule.name(),
                    rule.getName(),
                    rule.getName(),
                    rule.getName(),
                    rule.getExpression(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(rule.getConfidence()),
                    rule.getConfidence() >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
            );
        }

        // 构建角色
        for (String roleName : facts.getRoles()) {
            findOrCreateNode(
                    projectId, versionId,
                    NodeType.Role.name(),
                    roleName,
                    roleName,
                    roleName,
                    null,
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(0.8),
                    NodeStatus.PENDING_CONFIRM
            );
        }

        // P0-B：消费 LLM 直接给出的功能清单（此前仅落 Fact 表，业务图谱丢失）。
        // 与流程派生的 Feature 独立，覆盖 LLM 未组织成 BusinessProcess 的功能点。
        if (facts.getFeatures() != null) {
            for (String feature : facts.getFeatures()) {
                if (feature == null || feature.isBlank()) {
                    continue;
                }
                String normalizedFeatureKey = normalizeFeatureKey(feature);
                findOrCreateNode(
                        projectId, versionId,
                        NodeType.Feature.name(),
                        normalizedFeatureKey,
                        feature,
                        feature,
                        null,
                        sourceType,
                        sourcePath,
                        null,
                        null,
                        BigDecimal.valueOf(0.7),
                        NodeStatus.PENDING_CONFIRM
                );
            }
        }

        log.info("Built business graph: {} domains, {} processes, {} objects, {} rules, {} features",
                facts.getBusinessDomains().size(),
                facts.getBusinessProcesses().size(),
                facts.getBusinessObjects().size(),
                facts.getBusinessRules().size(),
                facts.getFeatures() != null ? facts.getFeatures().size() : 0);
    }

    /**
     * 功能映射：将文档中的功能映射到已有的前端页面和后端接口。
     * <p>收集所有匹配边后批量 MERGE，避免逐条创建导致的大量 Neo4j 往返。</p>
     */
    @Transactional
    public int mapFeaturesToCode(String projectId, String versionId) {
        // 获取所有 Feature 节点（含 DOC_AI 和 CODE_AI 来源），避免代码抽取的 Feature 成为孤岛
        List<GraphNode> docFeatures = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(), null, null, null, 0);

        // 获取所有已有的Page节点
        List<GraphNode> pages = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Page.name(), null, null, null, 0);

        // 获取所有已有的ApiEndpoint节点
        List<GraphNode> apis = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.ApiEndpoint.name(), null, null, null, 0);

        List<GraphEdge> candidateEdges = new ArrayList<>();
        // 预计算每个名称的 token 集合（按名去重缓存），避免在 feature×page×api 笛卡尔积里重复分词
        Map<String, Set<String>> tokensByName = new HashMap<>();
        // 跨语言名称匹配：中文 Feature 名经 CN_ALIAS 翻成英文子词后，与英文 API/Page 名做 token 重叠
        // （见 tokenSimilarity / matchTokens）。后续可进一步替换为向量语义检索提升精度。
        for (GraphNode feature : safeList(docFeatures)) {
            String featureName = normalizeSearchName(feature);
            if (featureName.isBlank()) {
                continue;
            }
            Set<String> featTokens = tokensByName.computeIfAbsent(featureName, this::matchTokens);

            // 匹配Page
            for (GraphNode page : safeList(pages)) {
                String pageName = normalizeSearchName(page);
                if (pageName.isBlank()) {
                    continue;
                }
                Set<String> pageTokens = tokensByName.computeIfAbsent(pageName, this::matchTokens);
                double score = tokenSimilarity(featTokens, pageTokens, featureName, pageName);
                if (score > 0.6) {
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            feature.getId(), page.getId(),
                            EdgeType.EXPOSED_BY.name(),
                            feature.getNodeKey() + "->exposed_by->" + page.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score * 0.8),
                            score >= 0.8 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }

            // 匹配API
            for (GraphNode api : safeList(apis)) {
                String apiName = normalizeSearchName(api);
                if (apiName.isBlank()) {
                    continue;
                }
                Set<String> apiTokens = tokensByName.computeIfAbsent(apiName, this::matchTokens);
                double score = tokenSimilarity(featTokens, apiTokens, featureName, apiName);
                if (score > 0.5) {
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            feature.getId(), api.getId(),
                            EdgeType.IMPLEMENTED_BY.name(),
                            feature.getNodeKey() + "->implemented_by->" + api.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score * 0.7),
                            score >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }
        }

        // 批量 MERGE 所有匹配边（避免空列表调用）
        if (candidateEdges.isEmpty()) {
            log.info("Mapped 0 feature-doc to code (no matches)");
            return 0;
        }
        int mappedCount = neo4jGraphDao.mergeEdgesBatch(candidateEdges);
        log.info("Mapped {} feature-doc to code (batch merged)", mappedCount);
        return mappedCount;
    }

    /**
     * P2：业务对象 ↔ 技术实体对齐。
     *
     * <p>将文档抽取的 BusinessObject（如"订单"）按名称相似度对齐到数据库 Table（如 orders）
     * 与代码实体类（Service/Mapper），建立 {@link EdgeType#MAPS_TO} 桥接边，
     * 连通业务层与技术层，使"业务视图"不再是与代码割裂的孤岛。</p>
     *
     * <p>名称相似度对中英文混合命名（订单/order/orders/t_order）做归一化处理，
     * 低置信匹配置为 PENDING_CONFIRM 等待人工确认，不直接作为确定事实。</p>
     */
    @Transactional
    public int mapBusinessObjectsToTables(String projectId, String versionId) {
        List<GraphNode> businessObjects = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.BusinessObject.name(), null, null, null, 0);
        if (businessObjects == null || businessObjects.isEmpty()) {
            log.info("Skip business-object mapping: no business objects found");
            return 0;
        }

        // 技术层候选：数据库表
        List<GraphNode> tables = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Table.name(), null, null, null, 0);
        List<GraphNode> services = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Service.name(), null, null, null, 0);
        List<GraphNode> mappers = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Mapper.name(), null, null, null, 0);
        List<GraphNode> controllers = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Controller.name(), null, null, null, 0);

        List<GraphNode> techEntities = new ArrayList<>();
        if (tables != null) techEntities.addAll(tables);
        if (services != null) techEntities.addAll(services);
        if (mappers != null) techEntities.addAll(mappers);
        if (controllers != null) techEntities.addAll(controllers);

        if (techEntities.isEmpty()) {
            log.info("Skip business-object mapping: no tables/services/mappers/controllers found");
            return 0;
        }

        List<GraphEdge> candidateEdges = new ArrayList<>();
        for (GraphNode obj : businessObjects) {
            String objName = normalizeSearchName(obj);
            if (objName.isBlank()) continue;
            for (GraphNode tech : techEntities) {
                String techName = normalizeSearchName(tech);
                if (techName.isBlank()) continue;
                double score = nameSimilarity(objName, techName);
                boolean isCodeEntity = NodeType.Service.name().equals(tech.getNodeType())
                        || NodeType.Mapper.name().equals(tech.getNodeType())
                        || NodeType.Controller.name().equals(tech.getNodeType());
                double threshold = isCodeEntity ? 0.65 : 0.6;
                if (score > threshold) {
                    String edgeLabel = isCodeEntity ? "IMPLEMENTED_BY" : EdgeType.MAPS_TO.name();
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            obj.getId(), tech.getId(),
                            edgeLabel,
                            obj.getNodeKey() + "->" + edgeLabel.toLowerCase() + "->" + tech.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score * 0.8),
                            score >= 0.85 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }
        }

        // 批量 MERGE 所有匹配边（避免空列表调用）
        if (candidateEdges.isEmpty()) {
            log.info("Mapped business-object: 0 edges (no matches, projectId={})", projectId);
            return 0;
        }
        int totalMapped = neo4jGraphDao.mergeEdgesBatch(candidateEdges);
        log.info("Mapped business-object: {} edges (batch merged, projectId={})", totalMapped, projectId);
        return totalMapped;
    }

    /**
     * 归一化 Feature 节点 key，委托给 {@link FeatureIdentityNormalizer}。
     *
     * <p>统一 trim、小写、中文标点、来源前缀，确保跨来源（文档/代码/流程派生）
     * 的相同语义 Feature 合并为同一图节点。</p>
     */
    private String normalizeFeatureKey(String rawName) {
        if (featureIdentityNormalizer != null) {
            String key = featureIdentityNormalizer.toFeatureKey(rawName);
            if (key != null) {
                return key;
            }
        }
        // 降级：normalizer 不可用时使用基础归一化
        if (rawName == null || rawName.isBlank()) {
            return "feature:unnamed";
        }
        return FeatureIdentityNormalizer.FEATURE_KEY_PREFIX + rawName.trim();
    }

    /**
     * 归一化实体名，便于业务对象与表名跨命名风格匹配：
     * 转小写、去表名常见前缀(t_/tb_/sys_)、去下划线。
     */
    private String normalizeEntityName(String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim().toLowerCase();
        for (String prefix : new String[]{"t_", "tb_", "sys_", "biz_"}) {
            if (n.startsWith(prefix)) {
                n = n.substring(prefix.length());
                break;
            }
        }
        return n.replace("_", "");
    }

    private String normalizeSearchName(GraphNode node) {
        // 保留原始大小写，供 nameSimilarity 拆分 camelCase；不再在此处小写/去前缀，
        // 分词与归一化统一交给 matchTokens 处理。
        return nodeDisplayName(node).trim();
    }

    /**
     * 中文业务术语 → 英文关键词（camelCase 拆分后的子词形式）映射表。
     * <p>用于跨语言名称匹配：文档抽取的 Feature 名为中文（如"出金审核列表"），
     * 代码抽取的 ApiEndpoint 名为英文路径（如"getCashOutAuditList"），
     * 直接做字符级 Jaccard 必然为 0。这里把中文术语翻成英文子词，使两侧 token 可重叠。</p>
     * <p>这是一个领域词典起点，可按需扩充；后续可替换为向量语义检索。</p>
     */
    private static final Map<String, List<String>> CN_ALIAS = Map.ofEntries(
            Map.entry("出金", List.of("cash", "out")),
            Map.entry("入金", List.of("gold", "in")),
            Map.entry("保证金", List.of("margin", "earnest")),
            Map.entry("诚意金", List.of("earnest")),
            Map.entry("违约", List.of("penalty", "breach")),
            Map.entry("审核", List.of("audit")),
            Map.entry("初审", List.of("first", "trial")),
            Map.entry("复审", List.of("recheck")),
            Map.entry("审批", List.of("approve", "audit")),
            Map.entry("通过", List.of("approved", "pass")),
            Map.entry("提交", List.of("submit")),
            Map.entry("导出", List.of("export", "excel")),
            Map.entry("冻结", List.of("frozen", "freeze", "lock")),
            Map.entry("释放", List.of("free", "release", "unlock")),
            Map.entry("解锁", List.of("unlock", "free")),
            Map.entry("锁定", List.of("lock")),
            Map.entry("白名单", List.of("white")),
            Map.entry("异常", List.of("exception")),
            Map.entry("账户", List.of("account", "acct")),
            Map.entry("主账户", List.of("master", "account")),
            Map.entry("子账户", List.of("sub", "account")),
            Map.entry("销户", List.of("closing", "close")),
            Map.entry("开户", List.of("open", "account")),
            Map.entry("会员", List.of("corp", "member")),
            Map.entry("企业", List.of("corp")),
            Map.entry("余额", List.of("balance")),
            Map.entry("记账", List.of("billing")),
            Map.entry("明细", List.of("detail", "details")),
            Map.entry("对账", List.of("reconciliation", "recheck")),
            Map.entry("流水", List.of("flow", "stream", "trans")),
            Map.entry("银行", List.of("bank")),
            Map.entry("利息", List.of("fee", "interest")),
            Map.entry("历史", List.of("his", "history")),
            Map.entry("每日", List.of("daily")),
            Map.entry("统计", List.of("statistics", "count", "amount")),
            Map.entry("汇总", List.of("summary", "count")),
            Map.entry("列表", List.of("list")),
            Map.entry("查询", List.of("find", "query", "get")),
            Map.entry("详情", List.of("detail", "byid")),
            Map.entry("信息", List.of("info")),
            Map.entry("角色", List.of("role")),
            Map.entry("菜单", List.of("menu")),
            Map.entry("系统参数", List.of("sys", "param")),
            Map.entry("参数", List.of("param")),
            Map.entry("短信验证码", List.of("sms", "ver", "code")),
            Map.entry("短信", List.of("sms")),
            Map.entry("验证码", List.of("ver", "code")),
            Map.entry("登录", List.of("login")),
            Map.entry("登出", List.of("logout")),
            Map.entry("同步", List.of("sync")),
            Map.entry("风控", List.of("risk")),
            Map.entry("风险", List.of("risk")),
            Map.entry("焦煤", List.of("jmzx", "coking")),
            Map.entry("农信", List.of("nx")),
            Map.entry("划转", List.of("transfer", "pay")),
            Map.entry("备注", List.of("remark")),
            Map.entry("手动", List.of("hand", "manual")),
            Map.entry("线下", List.of("hand", "offline")),
            Map.entry("原路退回", List.of("sendback", "send", "back")),
            Map.entry("业务", List.of("business", "biz")),
            Map.entry("申请", List.of("apply", "audit")),
            Map.entry("添加", List.of("add", "save")),
            Map.entry("删除", List.of("del", "delete")),
            Map.entry("更新", List.of("upd", "update", "save")),
            Map.entry("修改", List.of("upd", "update")),
            Map.entry("日志", List.of("log")),
            Map.entry("调用", List.of("call")),
            Map.entry("接口", List.of("interface", "api")),
            Map.entry("用户", List.of("user")),
            Map.entry("获取", List.of("get")),
            Map.entry("规则", List.of("rule"))
    );
    /** CN_ALIAS 的 key 按长度降序，匹配时最长优先，避免"主账户"被"账户"抢先消费。 */
    private static final List<String> CN_ALIAS_KEYS_DESC;
    static {
        List<String> keys = new ArrayList<>(CN_ALIAS.keySet());
        keys.sort((x, y) -> Integer.compare(y.length(), x.length()));
        CN_ALIAS_KEYS_DESC = keys;
    }

    private String nodeDisplayName(GraphNode node) {
        if (node == null) {
            return "";
        }
        if (node.getDisplayName() != null && !node.getDisplayName().isBlank()) {
            return node.getDisplayName();
        }
        if (node.getNodeName() != null && !node.getNodeName().isBlank()) {
            return node.getNodeName();
        }
        return "";
    }

    private List<GraphNode> safeList(List<GraphNode> nodes) {
        return nodes != null ? nodes : List.of();
    }

    /**
     * 跨语言名称相似度：中文术语翻成英文子词后做 token 重叠度匹配。
     * <p>用重叠系数（intersection / min(|A|,|B|)）而非 Jaccard——中文术语展开后 token 数
     * 与英文路径不对等，Jaccard 会被稀释。</p>
     * <p>守卫：较小集合（&lt;3 token）必须被完全包含，避免单 token 共享导致的误匹配
     * （如"出金"与"入金"都含 gold 相关 token）。</p>
     */
    private double nameSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0;
        }
        return tokenSimilarity(matchTokens(a), matchTokens(b), a, b);
    }

    /**
     * 基于 token 集合的相似度（核心逻辑），供 nameSimilarity 与 mapFeaturesToCode 预计算后复用。
     * <p>两侧 token 非空时用重叠系数（intersection / min(|A|,|B|)）+ 小集合守卫；
     * 否则退回 contains/equals 兜底。</p>
     */
    private double tokenSimilarity(Set<String> ta, Set<String> tb, String a, String b) {
        if (!ta.isEmpty() && !tb.isEmpty()) {
            int inter = 0;
            for (String t : ta) {
                if (tb.contains(t)) {
                    inter++;
                }
            }
            if (inter == 0) {
                return 0;
            }
            int min = Math.min(ta.size(), tb.size());
            // 1-token 集合太弱（如"农信接口调用日志"仅翻出 {nx} 会误匹配所有含 nx 的接口），不做匹配
            if (min < 2) {
                return 0;
            }
            // 小集合（<3 token）必须完全包含，杜绝单 token 误匹配
            if (inter != min && min < 3) {
                return 0;
            }
            return (double) inter / min;
        }
        // 兜底：两侧无法分词或 token 无交集时用包含关系；要求长度≥3，避免 "/" 等单字符假匹配
        if (a.length() >= 3 && b.length() >= 3 && (a.contains(b) || b.contains(a))) {
            return 0.7;
        }
        return a.equalsIgnoreCase(b) ? 0.9 : 0;
    }

    /**
     * 将名称拆分为英文关键词 token 集合，用于跨语言相似度匹配。
     * <ol>
     *   <li>拆分 camelCase 边界（getCashOutAuditList → get/cash/out/audit/list）</li>
     *   <li>中文术语按 {@link #CN_ALIAS} 翻成英文子词（出金审核列表 → cash/out/audit/list）</li>
     *   <li>剩余英文片段按非字母数字分割，长度≥2 的纳入</li>
     * </ol>
     */
    private Set<String> matchTokens(String name) {
        Set<String> tokens = new HashSet<>();
        if (name == null || name.isBlank()) {
            return tokens;
        }
        // 1) 拆 camelCase（保留大小写先拆，再统一小写）
        String expanded = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        String lower = expanded.toLowerCase();
        // 2) 中文术语 → 英文子词（最长优先）。无中文字符时跳过词典扫描（英文 API 名走 camelCase 拆分即可）
        if (hasCjk(lower)) {
            for (String cn : CN_ALIAS_KEYS_DESC) {
                int idx = lower.indexOf(cn);
                while (idx >= 0) {
                    List<String> en = CN_ALIAS.get(cn);
                    if (en != null) {
                        tokens.addAll(en);
                    }
                    lower = lower.substring(0, idx) + " " + lower.substring(idx + cn.length());
                    idx = lower.indexOf(cn);
                }
            }
        }
        // 3) 剩余英文片段
        for (String t : lower.split("[^a-z0-9]+")) {
            if (t.length() >= 2) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** 是否含 CJK 汉字（用于 matchTokens 跳过英文名称的词典扫描）。 */
    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '一' && c <= '鿿') {
                return true;
            }
        }
        return false;
    }

    /**
     * 查找或创建节点（委托给 EvidenceGraphWriter）。
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status) {
        return writer.upsertNode(GraphNodeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .nodeType(nodeType)
                .nodeKey(nodeKey)
                .nodeName(nodeName)
                .displayName(displayName)
                .description(description)
                .sourceType(sourceType)
                .sourcePath(sourcePath)
                .startLine(startLine)
                .endLine(endLine)
                .confidence(confidence)
                .status(status != null ? status.name() : null)
                .build());
    }

    /**
     * 创建边（委托给 EvidenceGraphWriter，自动去重+证据继承）。
     */
    private GraphEdge createEdge(String projectId, String versionId,
            String fromNodeId, String toNodeId,
            String edgeType, String edgeKey,
            String sourceType, BigDecimal confidence,
            NodeStatus status) {
        return writer.upsertEdge(GraphEdgeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .fromNodeId(fromNodeId)
                .toNodeId(toNodeId)
                .edgeType(edgeType)
                .edgeKey(edgeKey)
                .sourceType(sourceType)
                .confidence(confidence)
                .status(status != null ? status.name() : null)
                .build());
    }

    /**
     * 构建边 POJO（不写 Neo4j），供批量 mergeEdgesBatch 使用。
     * 与 {@link #createEdge} 的区别：不调用 writer.upsertEdge，
     * 仅构造 GraphEdge 对象用于后续批量 MERGE。
     */
    private GraphEdge buildEdgePOJO(String projectId, String versionId,
            String fromNodeId, String toNodeId,
            String edgeType, String edgeKey,
            String sourceType, BigDecimal confidence,
            NodeStatus status) {
        GraphEdge edge = new GraphEdge();
        edge.setId(UUID.randomUUID().toString());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setEdgeType(edgeType);
        edge.setEdgeKey(edgeKey);
        edge.setSourceType(sourceType);
        edge.setConfidence(confidence);
        edge.setStatus(status != null ? status.name() : null);
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        return edge;
    }
}
