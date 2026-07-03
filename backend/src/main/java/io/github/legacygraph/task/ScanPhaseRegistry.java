package io.github.legacygraph.task;

import io.github.legacygraph.dto.ScanPhase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描阶段注册表 — 定义所有扫描环节的顺序和中文名称。
 * <p>
 * 前后端共用此定义，确保环节名称和顺序一致。
 * </p>
 */
public final class ScanPhaseRegistry {

    private ScanPhaseRegistry() {}

    /** 按执行顺序排列的所有阶段 */
    private static final LinkedHashMap<String, ScanPhase> PHASES = new LinkedHashMap<>();

    static {
        register("DB_DISCOVERY", "数据库连接发现", 0);
        register("PATH_DISCOVERY", "前后端路径检测", 1);
        register("DOC_DISCOVERY", "文档自动发现", 2);
        register("ADAPTER_SCAN", "代码结构抽取", 3);
        register("DATABASE_SCAN", "数据库元数据扫描", 4);
        register("GRAPH_BUILD", "知识图谱构建", 5);
        register("AI_ORCHESTRATION", "AI 智能分析", 6);
    }

    private static void register(String taskType, String phaseName, int order) {
        PHASES.put(taskType, ScanPhase.builder()
                .taskType(taskType)
                .phaseName(phaseName)
                .order(order)
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
}
