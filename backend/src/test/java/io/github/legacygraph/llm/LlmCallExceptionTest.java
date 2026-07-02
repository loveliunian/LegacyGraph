package io.github.legacygraph.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmCallException 单元测试。
 * 验证异常构造函数、字段访问与 needsReview 语义。
 */
@ExtendWith(MockitoExtension.class)
class LlmCallExceptionTest {

    /**
     * 测试双参构造函数。
     */
    @Test
    void constructor_twoArgs_setsFieldsCorrectly() {
        RuntimeException cause = new RuntimeException("网络超时");
        LlmCallException ex = new LlmCallException("LLM 调用失败", cause);

        assertEquals("LLM 调用失败", ex.getMessage());
        assertEquals(cause, ex.getCause());
        assertFalse(ex.isNeedsReview());
        assertNull(ex.getPromptRunId());
    }

    /**
     * 测试全参构造函数（含 needsReview 与 promptRunId）。
     */
    @Test
    void constructor_allArgs_setsFieldsCorrectly() {
        RuntimeException cause = new RuntimeException("校验失败");
        LlmCallException ex = new LlmCallException(
                "输出 schema 不匹配", cause, true, 42L);

        assertEquals("输出 schema 不匹配", ex.getMessage());
        assertEquals(cause, ex.getCause());
        assertTrue(ex.isNeedsReview());
        assertEquals(42L, ex.getPromptRunId());
    }

    /**
     * 测试是 RuntimeException 的子类。
     */
    @Test
    void isRuntimeException() {
        LlmCallException ex = new LlmCallException("test", null);
        assertInstanceOf(RuntimeException.class, ex);
    }

    /**
     * 测试 needsReview=false 且 promptRunId=null 的构造函数。
     */
    @Test
    void constructor_noReview_hasDefaults() {
        LlmCallException ex = new LlmCallException(
                "Service unavailable", new RuntimeException(), false, null);

        assertFalse(ex.isNeedsReview());
        assertNull(ex.getPromptRunId());
    }
}
