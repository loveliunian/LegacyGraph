package io.github.legacygraph.dto.requirement;

/**
 * 影响层级枚举（G-06）。
 * <p>按节点距变更起点的跳数和影响范围划分为 L0~L4 五个层级，
 * 用于影响分析的分层评估与风险权重计算。</p>
 */
public enum ImpactLevel {

    /** L0 直接：变更起点本身，depth=0 */
    L0("L0直接", 0),

    /** L1 代码：1 跳邻居，直接代码级影响 */
    L1("L1代码", 1),

    /** L2 交互：2 跳，跨模块交互影响 */
    L2("L2交互", 2),

    /** L3 质量：3 跳，质量属性层面影响 */
    L3("L3质量", 3),

    /** L4 架构：4 跳及以上，架构级广泛影响 */
    L4("L4架构", 4);

    /** 层级描述 */
    private final String description;

    /** 对应的路径深度（跳数） */
    private final int depth;

    ImpactLevel(String description, int depth) {
        this.description = description;
        this.depth = depth;
    }

    public String getDescription() {
        return description;
    }

    public int getDepth() {
        return depth;
    }

    /**
     * 根据路径深度推断影响层级。
     *
     * @param depth 距离变更起点的跳数
     * @return 对应的 ImpactLevel（depth>=4 统一归为 L4）
     */
    public static ImpactLevel fromDepth(int depth) {
        if (depth <= 0) {
            return L0;
        }
        return switch (depth) {
            case 1 -> L1;
            case 2 -> L2;
            case 3 -> L3;
            default -> L4;
        };
    }
}
