package io.github.legacygraph.task;

import io.github.legacygraph.dto.ScanPhase;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描阶段注册表 — 定义所有扫描环节的顺序、中文名称和权重。
 * <p>
 * 前后端共用此定义，确保环节名称和顺序一致。
 * <p>
 * 权重用于进度计算，权重越高的阶段完成时进度增长越多：
 * - ADAPTER_SCAN（代码结构抽取）权重4，占总权重约25%
 * - AI_ORCHESTRATION（AI 智能分析）权重5，占总权重约31%
 * - 两者合计占比约56%，更准确反映实际耗时
 * </p>
 */
public final class ScanPhaseRegistry {

    private ScanPhaseRegistry() {}

    /** 按执行顺序排列的所有阶段 */
    private static final LinkedHashMap<String, ScanPhase> PHASES = new LinkedHashMap<>();

    static {
        register("DB_DISCOVERY", "数据库连接发现", 0, 1);
        register("PATH_DISCOVERY", "前后端路径检测", 1, 1);
        register("DOC_DISCOVERY", "文档自动发现", 2, 1);
        register("ADAPTER_SCAN", "代码结构抽取", 3, 4);
        register("DATABASE_SCAN", "数据库元数据扫描", 4, 2);
        register("GRAPHIFY_ANALYZE", "Graphify 代码分析", 5, 1);
        register("GRAPH_BUILD", "知识图谱构建", 6, 1);
        register("AI_ORCHESTRATION", "AI 智能分析", 7, 5);
    }

    private static void register(String taskType, String phaseName, int order, int weight) {
        PHASES.put(taskType, ScanPhase.builder()
                .taskType(taskType)
                .phaseName(phaseName)
                .order(order)
                .weight(weight)
                .build());
    }

    /** 获取所有阶段（按执行顺序） */
    public static List<ScanPhase> getAllPhases() {
        return List.copyOf(PHASES.values());
    }

    /** 根据 taskType 获取阶段定义 */
    public static ScanPhase getPhase(String taskType) {
        return PHASES.get(taskType);
    }

    /** 根据 taskType 获取阶段中文名，未注册时返回 taskType 本身 */
    public static String getPhaseName(String taskType) {
        ScanPhase phase = PHASES.get(taskType);
        return phase != null ? phase.getPhaseName() : taskType;
    }

    /** 根据 taskType 获取阶段序号，未注册时返回 -1 */
    public static int getOrder(String taskType) {
        ScanPhase phase = PHASES.get(taskType);
        return phase != null ? phase.getOrder() : -1;
    }

    // ==================== AI 子阶段定义 ====================

    /**
     * AI 子阶段定义 — 用于在 AI_ORCHESTRATION 阶段下方展开显示子环节。
     * <p>
     * 并行执行的 AI_DOC_EXTRACT + AI_CODE_EXTRACT 合并为一个子阶段展示；
     * 其余串行步骤各自独立展示。共 6 个子阶段。
     * </p>
     */
    public static final class AiSubPhaseDef {
        /** 展示用 taskType（合并阶段用合成名） */
        public final String displayTaskType;
        /** 展示中文名 */
        public final String displayName;
        /** 数据源 taskType 列表（合并阶段有多个，单步阶段仅一个） */
        public final List<String> sourceTaskTypes;

        AiSubPhaseDef(String displayTaskType, String displayName, String... sourceTaskTypes) {
            this.displayTaskType = displayTaskType;
            this.displayName = displayName;
            this.sourceTaskTypes = Collections.unmodifiableList(Arrays.asList(sourceTaskTypes));
        }
    }

    /** AI 子阶段定义列表（按执行顺序，合并并行步骤后共 6 个） */
    private static final List<AiSubPhaseDef> AI_SUB_PHASES = List.of(
            new AiSubPhaseDef("AI_DOC_CODE_EXTRACT", "文档与代码事实抽取", "AI_DOC_EXTRACT", "AI_CODE_EXTRACT"),
            new AiSubPhaseDef("AI_FEATURE_MAPPING", "功能映射对齐", "AI_FEATURE_MAPPING"),
            new AiSubPhaseDef("AI_TEST_GENERATE", "测试用例生成", "AI_TEST_GENERATE"),
            new AiSubPhaseDef("AI_REVIEW_PREPARE", "低置信节点审核准备", "AI_REVIEW_PREPARE"),
            new AiSubPhaseDef("AI_GAP_FINDING", "知识缺口扫描", "AI_GAP_FINDING"),
            new AiSubPhaseDef("AI_CODE_UNDERSTANDING", "代码理解增强", "AI_CODE_UNDERSTANDING")
    );

    /** 获取 AI 子阶段定义列表（按执行顺序） */
    public static List<AiSubPhaseDef> getAiSubPhaseDefs() {
        return AI_SUB_PHASES;
    }

    /** 根据 AI_* taskType 获取子阶段中文名，未注册时返回 taskType 本身 */
    public static String getAiSubPhaseName(String taskType) {
        for (AiSubPhaseDef def : AI_SUB_PHASES) {
            if (def.sourceTaskTypes.contains(taskType)) {
                return def.displayName;
            }
        }
        return taskType;
    }
}
