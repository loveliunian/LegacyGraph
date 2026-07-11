package io.github.legacygraph.dto.solution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试生成结果 DTO（G-18）。
 * <p>由 {@code TestGenerationAgent} 基于方案（Solution）及其步骤（SolutionStep）
 * 生成的测试骨架结果，包含每个步骤对应的测试类与方法骨架代码。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestGenerationResult {

    /** 方案 ID */
    private String solutionId;

    /** 生成的测试列表 */
    @Builder.Default
    private List<GeneratedTest> generatedTests = new ArrayList<>();

    /** 覆盖说明（描述已覆盖的步骤与未覆盖的步骤） */
    private String coverageNote;

    /** 状态：SUCCESS / PARTIAL / NO_TESTS */
    private String status;

    /**
     * 单个步骤生成的测试骨架。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeneratedTest {

        /** 步骤序号（对应 SolutionStep.stepIndex） */
        private Integer stepIndex;

        /** 测试文件路径（基于目标文件派生） */
        private String testFilePath;

        /** 测试类名（{ClassName}Test） */
        private String testClassName;

        /** 测试方法名（test_{stepIndex}_{actionType}） */
        private String testMethodName;

        /** 测试代码骨架（JUnit 5 风格） */
        private String testCode;
    }
}
