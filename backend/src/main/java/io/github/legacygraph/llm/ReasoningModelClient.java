package io.github.legacygraph.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.service.system.LlmProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 推理模型客户端 — 直接 HTTP 调用 OpenAI 兼容 API，支持 reasoning_content 字段。
 * <p>
 * Spring AI 2.0.0 的 OpenAiChatModel 底层使用 openai-java-core 4.39.1，
 * 该库的 ChatCompletionChunk$Choice$Delta 没有 reasoningContent 字段，
 * DeepSeek 推理模型（deepseek-v4-flash / deepseek-reasoner）的内容输出在 reasoning_content，
 * 会被存入 additionalProperties 但 Spring AI 不读取，导致 getText() 返回空。
 * <p>
 * 此客户端直接解析 SSE 流，同时读取 content 和 reasoning_content，
 * 对于非推理模型（content 有值）也完全兼容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReasoningModelClient {

    private final LlmProviderService llmProviderService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** LLM 调用重试配置 — 指数退避 1s→2s→4s，最多 3 次 */
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    /**
     * 流式调用，返回 Flux<String>，每个元素是一个文本片段（content 或 reasoning_content）。
     * <p>
     * 对于推理模型：先输出 reasoning_content（思考过程），再输出 content（最终答案）。
     * 对于非推理模型：仅输出 content。
     *
     * @param provider LLM 提供商
     * @param prompt   用户 prompt
     * @return Flux of text tokens
     */
    public Flux<String> streamCompletion(LlmProvider provider, String prompt) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        try {
            Map<String, Object> config = provider.getApiConfig();
            if (config == null) config = Map.of();

            String apiKey = LlmProviderService.resolveEnvPlaceholders(
                    (String) config.getOrDefault("api_key", ""));
            String baseUrl = provider.getEndpoint().replaceAll("/+$", "");
            String modelId = provider.getModelId();

            double temperature = config.containsKey("temperature")
                    ? ((Number) config.get("temperature")).doubleValue() : 0.1;
            int maxTokens = config.containsKey("max_tokens")
                    ? ((Number) config.get("max_tokens")).intValue() : 16384;

            // 构建请求体
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", modelId,
                    "messages", new Object[]{Map.of("role", "user", "content", prompt)},
                    "stream", true,
                    "temperature", temperature,
                    "max_tokens", maxTokens
            ));

            String url = baseUrl + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            // 异步发送流式请求
            // hasContent 标记是否收到过 content；reasoningBuffer 缓存思考过程用于兜底
            final boolean[] hasContent = {false};
            final StringBuilder reasoningBuffer = new StringBuilder();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> {
                        if (response.statusCode() != 200) {
                            sink.tryEmitError(new RuntimeException(
                                    "LLM API 返回错误状态码: " + response.statusCode()));
                            return;
                        }
                        response.body()
                                .filter(line -> line.startsWith("data: "))
                                .forEach(line -> {
                                    String data = line.substring(6).trim();
                                    if ("[DONE]".equals(data)) {
                                        return;
                                    }
                                    try {
                                        JsonNode chunk = objectMapper.readTree(data);
                                        JsonNode choices = chunk.path("choices");
                                        if (choices.isArray() && !choices.isEmpty()) {
                                            JsonNode delta = choices.get(0).path("delta");
                                            // QA 场景只需要最终答案 content；
                                            // reasoning_content（思考过程）不混入答案，仅在 content 全空时作兜底。
                                            String content = delta.path("content").asText(null);
                                            if (content != null && !content.isEmpty()) {
                                                hasContent[0] = true;
                                                sink.tryEmitNext(content);
                                            } else {
                                                String reasoning = delta.path("reasoning_content").asText(null);
                                                if (reasoning != null && !reasoning.isEmpty()) {
                                                    reasoningBuffer.append(reasoning);
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        log.debug("Failed to parse SSE chunk: {}", data);
                                    }
                                });
                        // 兜底：如果整个流没有任何 content，仅有 reasoning_content，则输出思考内容
                        // （避免 QA 完全空响应；正常推理模型 content 有值，不会触发此分支）
                        if (!hasContent[0] && reasoningBuffer.length() > 0) {
                            log.warn("推理模型未返回 content，使用 reasoning_content 兜底，长度={}",
                                    reasoningBuffer.length());
                            sink.tryEmitNext(reasoningBuffer.toString());
                        }
                        sink.tryEmitComplete();
                    })
                    .exceptionally(ex -> {
                        log.error("LLM stream request failed: {}", ex.getMessage());
                        sink.tryEmitError(ex);
                        return null;
                    });

        } catch (Exception e) {
            log.error("Failed to create LLM stream request: {}", e.getMessage(), e);
            sink.tryEmitError(e);
        }

        return sink.asFlux();
    }

    /**
     * 非流式调用，返回完整文本（content + reasoning_content 拼接）。
     * 内置指数退避重试：IOException/429/5xx 自动重试，最多 {@value #MAX_RETRIES} 次。
     *
     * @param provider LLM 提供商
     * @param prompt   用户 prompt
     * @return 完整响应文本
     */
    public String callCompletion(LlmProvider provider, String prompt) {
        Exception lastException = null;
        Duration backoff = INITIAL_BACKOFF;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doCallCompletion(provider, prompt);
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES && isRetryable(e)) {
                    log.warn("LLM call failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt, MAX_RETRIES, backoff.toMillis(), e.getMessage());
                    try {
                        Thread.sleep(backoff.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM 调用被中断", ie);
                    }
                    // 指数退避，上限 MAX_BACKOFF
                    long nextMs = (long) (backoff.toMillis() * BACKOFF_MULTIPLIER);
                    backoff = Duration.ofMillis(Math.min(nextMs, MAX_BACKOFF.toMillis()));
                } else {
                    break;
                }
            }
        }
        throw new RuntimeException("LLM 调用失败（已重试 " + MAX_RETRIES + " 次）: "
                + lastException.getMessage(), lastException);
    }

    /**
     * 判断异常是否可重试：IOException（网络）、429（限流）、5xx（服务端错误）可重试；
     * 4xx（客户端错误，如 400/401/403）不重试。
     */
    private boolean isRetryable(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return true; // 未知异常保守重试
        // IOException / timeout
        if (e instanceof java.io.IOException) return true;
        // HTTP 429 Too Many Requests 或 5xx
        if (msg.contains("429") || msg.contains("500") || msg.contains("502")
                || msg.contains("503") || msg.contains("504")) return true;
        return false;
    }

    private String doCallCompletion(LlmProvider provider, String prompt) throws Exception {
        Map<String, Object> config = provider.getApiConfig();
        if (config == null) config = Map.of();

        String apiKey = LlmProviderService.resolveEnvPlaceholders(
                (String) config.getOrDefault("api_key", ""));
        String baseUrl = provider.getEndpoint().replaceAll("/+$", "");
        String modelId = provider.getModelId();

        double temperature = config.containsKey("temperature")
                ? ((Number) config.get("temperature")).doubleValue() : 0.1;
        int maxTokens = config.containsKey("max_tokens")
                ? ((Number) config.get("max_tokens")).intValue() : 16384;

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", modelId,
                "messages", new Object[]{Map.of("role", "user", "content", prompt)},
                "stream", false,
                "temperature", temperature,
                "max_tokens", maxTokens
        ));

        String url = baseUrl + "/chat/completions";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM API 返回错误: " + response.statusCode()
                    + " " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText(null);
            String reasoning = message.path("reasoning_content").asText(null);

            if (content != null && !content.isEmpty()) {
                return content;
            }
            if (reasoning != null && !reasoning.isEmpty()) {
                return reasoning;
            }
        }
        return "";
    }
}
