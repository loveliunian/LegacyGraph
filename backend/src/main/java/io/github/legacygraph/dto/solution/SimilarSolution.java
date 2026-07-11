package io.github.legacygraph.dto.solution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 相似历史方案 DTO（G-15）。
 * <p>由 {@code SolutionSimilarityService} 检索得到，
 * 用于在方案规划阶段为用户提供可参考的历史相似方案。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimilarSolution {

    /** 方案 ID */
    private String solutionId;

    /** 项目 ID */
    private String projectId;

    /** 方案总览摘要 */
    private String summary;

    /** 方案目标（用于展示参考意图） */
    private String goal;

    /** 关键步骤列表 */
    @Builder.Default
    private List<String> keySteps = new ArrayList<>();

    /** 相似度分数（0.0 ~ 1.0） */
    private double similarityScore;

    /** 参考价值计数（被引用为有用的次数） */
    private int usefulCount;
}
