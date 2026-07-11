package io.github.legacygraph.dto.solution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 测试生成请求 DTO（G-18）。
 * <p>由方案触发 TestGenerationAgent 时使用的请求体，
 * 用于指定测试框架（如 JUNIT5）和测试范围（UNIT/INTEGRATION）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestGenerationRequest {

    /** 方案 ID（与路径参数一致，便于请求体独立传递） */
    private String solutionId;

    /** 项目 ID（与路径参数一致） */
    private String projectId;

    /** 测试框架：JUNIT5 / TESTNG / JUNIT4 等，默认 JUNIT5 */
    private String testFramework;

    /** 测试范围：UNIT / INTEGRATION，默认 UNIT */
    private String testScope;
}
