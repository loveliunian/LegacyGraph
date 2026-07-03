package io.github.legacygraph.understanding.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolPolicy 单元测试 —— 验证默认值和 Builder 行为。
 *
 * <p>ToolPolicy 使用 Lombok @Data @Builder，需验证：
 * <ul>
 *   <li>默认构造：executionMode=READ_ONLY, allowExternalNetwork=false, maxToolRuns=30</li>
 *   <li>Builder 自定义：可覆盖默认值</li>
 *   <li>边界：maxOutputBytes 默认 200000</li>
 * </ul>
 */
@DisplayName("ToolPolicy 策略配置测试")
class ToolPolicyTest {

    // ========================================================
    // 场景 1：默认构造
    // ========================================================

    @Test
    @DisplayName("默认构造：executionMode=READ_ONLY, allowExternalNetwork=false, maxToolRuns=30")
    void shouldHaveCorrectDefaults() {
        // when: 使用 Builder 不传任何字段
        ToolPolicy policy = ToolPolicy.builder().build();

        // then: 验证所有默认值
        assertThat(policy.getExecutionMode()).isEqualTo("READ_ONLY");
        assertThat(policy.isAllowExternalNetwork()).isFalse();
        assertThat(policy.isAllowAiInference()).isTrue();
        assertThat(policy.getMaxFilesToRead()).isEqualTo(20);
        assertThat(policy.getMaxToolRuns()).isEqualTo(30);
        assertThat(policy.getMaxSeconds()).isEqualTo(180);
        assertThat(policy.getMaxOutputBytes()).isEqualTo(200_000);
    }

    // ========================================================
    // 场景 2：Builder 自定义覆盖默认值
    // ========================================================

    @Test
    @DisplayName("Builder 自定义：可覆盖默认值")
    void shouldAllowCustomValues() {
        // when: 使用 Builder 覆盖所有字段
        ToolPolicy policy = ToolPolicy.builder()
                .enabledToolKinds(List.of(ToolKind.MCP, ToolKind.CLI))
                .allowedTools(List.of("codebase-memory-mcp"))
                .executionMode("READ_ONLY")
                .allowExternalNetwork(false)
                .allowAiInference(false)
                .maxFilesToRead(50)
                .maxToolRuns(100)
                .maxSeconds(600)
                .maxOutputBytes(500_000)
                .build();

        // then: 所有字段应为自定义值
        assertThat(policy.getEnabledToolKinds()).containsExactly(ToolKind.MCP, ToolKind.CLI);
        assertThat(policy.getAllowedTools()).containsExactly("codebase-memory-mcp");
        assertThat(policy.getExecutionMode()).isEqualTo("READ_ONLY");
        assertThat(policy.isAllowExternalNetwork()).isFalse();
        assertThat(policy.isAllowAiInference()).isFalse();
        assertThat(policy.getMaxFilesToRead()).isEqualTo(50);
        assertThat(policy.getMaxToolRuns()).isEqualTo(100);
        assertThat(policy.getMaxSeconds()).isEqualTo(600);
        assertThat(policy.getMaxOutputBytes()).isEqualTo(500_000);
    }

    // ========================================================
    // 场景 3：maxOutputBytes 边界
    // ========================================================

    @Test
    @DisplayName("maxOutputBytes 默认 200000，可设为更大值")
    void shouldAllowLargeMaxOutputBytes() {
        // when: 设置 maxOutputBytes 为 1MB
        ToolPolicy policy = ToolPolicy.builder()
                .maxOutputBytes(1_000_000)
                .build();

        // then
        assertThat(policy.getMaxOutputBytes()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("enabledToolKinds 默认 null，表示所有工具类型可用")
    void shouldAllowAllToolKindsWhenNull() {
        // when: 不设置 enabledToolKinds
        ToolPolicy policy = ToolPolicy.builder().build();

        // then: enabledToolKinds 为 null，业务层判定不限制工具类型
        assertThat(policy.getEnabledToolKinds()).isNull();
    }

    @Test
    @DisplayName("allowExternalNetwork 默认为 false，代表安全策略")
    void shouldDefaultToNoExternalNetwork() {
        // when
        ToolPolicy policy = ToolPolicy.builder().build();

        // then: MVP 阶段默认不允许外部网络
        assertThat(policy.isAllowExternalNetwork()).isFalse();
    }
}
