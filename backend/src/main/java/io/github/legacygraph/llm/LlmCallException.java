package io.github.legacygraph.llm;

/**
 * LLM 调用异常 — 显式表达失败，避免把失败默默转换为空对象。
 *
 * <p>当 needsReview 为 true 时，表示 LLM 调用本身成功但输出未通过结构化/证据校验，
 * 应进入人工审核流程（对应 PromptRun.status = REVIEW）。</p>
 */
public class LlmCallException extends RuntimeException {

    /** 是否需要人工审核（输出无效但调用成功） */
    private final boolean needsReview;

    /** 失败时关联的 PromptRun 记录 ID（便于审计追溯） */
    private final Long promptRunId;

    public LlmCallException(String message, Throwable cause) {
        this(message, cause, false, null);
    }

    public LlmCallException(String message, Throwable cause, boolean needsReview, Long promptRunId) {
        super(message, cause);
        this.needsReview = needsReview;
        this.promptRunId = promptRunId;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public Long getPromptRunId() {
        return promptRunId;
    }
}
