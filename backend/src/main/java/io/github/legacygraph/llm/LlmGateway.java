package io.github.legacygraph.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.AgentRunContract;
import io.github.legacygraph.entity.AgentRun;
import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.entity.PromptRun;
import io.github.legacygraph.repository.AgentRunRepository;
import io.github.legacygraph.repository.PromptRunRepository;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.service.system.LlmProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 网关 — 支持多提供商动态切换。
 * <p>
 * Phase 0 加固：结构化校验（失败进 REVIEW）、失败显式抛出、PromptRun 审计补全。
 * <p>
 * doc 4.3（AgentRun 合约）：每次调用经 {@link AgentRunContract} 记录 schema 版本 / 证据 / 成本 / 自校正，
 * schema 解析失败支持一次自我修复（带原始错误）；doc 4.4（隐私分层）：发往外部模型的 prompt 必须是
 * 脱敏后的内容（pii + secret scan），审计只存 masked input。
 */
@Slf4j
@Component
public class LlmGateway {

    private final ObjectMapper objectMapper;
    private final PromptRunRepository promptRunRepository;
    private final AgentRunRepository agentRunRepository;
    private final PromptTemplateLoader templateLoader;
    private final PiiMaskingService piiMaskingService;
    private final SecretScanService secretScanService;
    private final LlmProviderService llmProviderService;
    private final RetryTemplate llmRetryTemplate;

    /** 缓存已创建的 ChatModel，按 providerCode 缓存 */
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();

    /** LLM 结果缓存（可选）：以 inputHash 去重，命中跳过真实调用。Redis 不可用时为 null，自动降级。 */
    @Autowired(required = false)
    private CacheService cacheService;

    /** LLM 结果缓存 TTL */
    private static final Duration LLM_CACHE_TTL = Duration.ofDays(7);

    /** schema 解析失败时的最大自我修复次数（doc 4.3：限制次数和成本） */
    private static final int MAX_SELF_CORRECTION = 1;

    public LlmGateway(ObjectMapper objectMapper,
                      PromptRunRepository promptRunRepository,
                      AgentRunRepository agentRunRepository,
                      PromptTemplateLoader templateLoader,
                      PiiMaskingService piiMaskingService,
                      SecretScanService secretScanService,
                      LlmProviderService llmProviderService,
                      RetryTemplate llmRetryTemplate) {
        this.objectMapper = objectMapper;
        this.promptRunRepository = promptRunRepository;
        this.agentRunRepository = agentRunRepository;
        this.templateLoader = templateLoader;
        this.piiMaskingService = piiMaskingService;
        this.secretScanService = secretScanService;
        this.llmProviderService = llmProviderService;
        this.llmRetryTemplate = llmRetryTemplate;
    }

    /**
     * 调用 LLM（无显式合约 — 自动构造默认合约，agentType=templateName）。
     * 现有 Agent 无需改动即获得合约审计。
     */
    public <T> T callWithTemplate(String projectId, String templateName,
                                   Map<String, String> variables, Class<T> responseType) {
        AgentRunContract contract = AgentRunContract.builder()
                .projectId(projectId)
                .agentType(templateName)
                .agentName(templateName)
                .inputSchemaVersion("1.0")
                .outputSchemaVersion("1.0")
                .build();
        return callWithTemplate(projectId, templateName, variables, responseType, contract);
    }

    /**
     * 通过 AgentEnvelope 调用 LLM。
     * <p>Envelope 是高风险 Agent 的证据约束 Interface：网关在渲染 prompt、查询 provider、
     * 写 PromptRun/AgentRun 之前先校验 RequiredEvidencePolicy，缺必填证据时直接进入人工复核路径。</p>
     */
    public <T> T callWithEnvelope(AgentEnvelope<?> envelope, String templateName,
                                  Map<String, String> variables, Class<T> responseType) {
        if (envelope == null) {
            throw new IllegalArgumentException("AgentEnvelope must not be null");
        }
        validateEvidencePolicy(envelope);
        AgentRunContract contract = toContract(envelope, templateName);
        return callWithTemplate(envelope.getProjectId(), templateName, variables, responseType, contract);
    }

    /**
     * 调用 LLM 并按 {@link AgentRunContract} 记录合约信息（doc 4.3）。
     *
     * @param contract 调用方填充 agentType / schema 版本 / usedEvidenceIds 等；tokens / cost /
     *                 selfCorrectionCount / needsHumanReview 由网关回填
     * @throws LlmCallException 调用失败或输出经自我修复仍无法反序列化时抛出
     */
    public <T> T callWithTemplate(String projectId, String templateName,
                                   Map<String, String> variables, Class<T> responseType,
                                   AgentRunContract contract) {
        log.info("LLM call: projectId={}, template={}, agent={}",
                projectId, templateName, contract != null ? contract.getAgentType() : templateName);

        if (contract == null) {
            contract = AgentRunContract.builder().agentType(templateName).build();
        }
        if (contract.getContractId() == null) {
            contract.setContractId(UUID.randomUUID().toString());
        }
        if (contract.getAgentType() == null) {
            contract.setAgentType(templateName);
        }
        contract.setProjectId(projectId);
        contract.setStartedAt(LocalDateTime.now());

        // 加载并渲染模板
        String prompt = templateLoader.render(templateName, variables);
        // doc 4.4：发往外部模型的内容必须先脱敏（PII + secret scan），审计只存 masked input
        String safePrompt = redactForEgress(prompt);
        log.debug("Rendered prompt (redacted):\n{}", safePrompt);

        // inputHash 基于原始 prompt，保证脱敏策略调整不影响缓存命中
        String inputHash = sha256(prompt);

        // 命中 LLM 结果缓存：直接反序列化返回，跳过真实调用与 DB 写入
        String cacheKey = llmCacheKey(templateName, inputHash);
        if (cacheService != null && cacheKey != null) {
            String cachedJson = cacheService.getString(cacheKey);
            if (cachedJson != null) {
                try {
                    log.info("LLM cache hit: template={}, inputHash={}", templateName, inputHash);
                    return deserialize(cachedJson, responseType);
                } catch (Exception e) {
                    log.warn("LLM cache deserialize failed, evict and recompute: {}", e.getMessage());
                    cacheService.evict(cacheKey);
                }
            }
        }

        // 获取当前默认提供商信息
        LlmProvider provider = llmProviderService.getActiveDefault();
        String providerCode = provider != null ? provider.getProviderCode() : "unknown";
        String modelId = provider != null ? provider.getModelId() : "unknown";
        contract.setModel(modelId);

        // 创建 PromptRun 审计记录
        PromptRun run = new PromptRun();
        run.setProjectId(projectId);
        run.setTaskType(templateName);
        run.setProviderCode(providerCode);
        run.setModelId(modelId);
        run.setTemplateCode(templateName);
        run.setTemplateVersion(contract.getInputSchemaVersion() != null ? contract.getInputSchemaVersion() : "1.0");
        run.setInputHash(inputHash);
        run.setMaskedInput(safePrompt);
        run.setStatus("RUNNING");
        run.setCreatedAt(LocalDateTime.now());
        promptRunRepository.insert(run);

        // 创建 AgentRun 合约记录
        AgentRun agentRun = toAgentRun(contract, run.getId());

        long startMs = System.currentTimeMillis();
        String response;
        try {
            ChatResponse chatResponse = invokeModel(safePrompt);
            response = extractText(chatResponse);
            run.setLatencyMs((int) (System.currentTimeMillis() - startMs));
            applyUsage(run, chatResponse);
            run.setRawOutput(response);
        } catch (Exception e) {
            run.setLatencyMs((int) (System.currentTimeMillis() - startMs));
            run.setStatus("FAILED");
            safeUpdate(run);
            contract.setFinishedAt(LocalDateTime.now());
            contract.setNeedsHumanReview(false);
            finishAgentRun(agentRun, contract, run, false);
            log.error("LLM call failed: projectId={}, template={}", projectId, templateName, e);
            throw new LlmCallException("LLM call failed: " + e.getMessage(), e, false, run.getId());
        }

        // 字符串类型：无需 schema 校验与自校正
        if (responseType == String.class) {
            run.setStatus("SUCCESS");
            run.setParsedOutput(response);
            safeUpdate(run);
            cacheLlmResult(cacheKey, response);
            contract.setFinishedAt(LocalDateTime.now());
            contract.setNeedsHumanReview(false);
            finishAgentRun(agentRun, contract, run, true);
            return (T) response;
        }

        // 结构化解析：失败时尝试一次自我修复（doc 4.3）
        String cleanResponse = cleanJsonResponse(response);
        try {
            T result = objectMapper.readValue(cleanResponse, responseType);
            run.setStatus("SUCCESS");
            run.setParsedOutput(writeJsonSafe(result));
            safeUpdate(run);
            cacheLlmResult(cacheKey, writeJsonSafe(result));
            contract.setFinishedAt(LocalDateTime.now());
            contract.setNeedsHumanReview(false);
            finishAgentRun(agentRun, contract, run, true);
            log.info("LLM call completed: projectId={}, template={}, agent={}, model={}",
                    projectId, templateName, contract.getAgentType(), modelId);
            return result;
        } catch (Exception parseEx) {
            log.warn("LLM output failed schema validation (attempt 1): target={}, err={}",
                    responseType.getSimpleName(), parseEx.getMessage());
            run.setRawOutput(cleanResponse);

            // 自我修复：把解析错误回灌，重试一次
            if (contract.getSelfCorrectionCount() < MAX_SELF_CORRECTION) {
                try {
                    String repairPrompt = buildSelfCorrectionPrompt(safePrompt, responseType, parseEx);
                    ChatResponse repairChatResponse = invokeModel(repairPrompt);
                    String repairResponse = extractText(repairChatResponse);
                    applyUsage(run, repairChatResponse);
                    String cleanRepair = cleanJsonResponse(repairResponse);
                    T repaired = objectMapper.readValue(cleanRepair, responseType);
                    contract.setSelfCorrectionCount(contract.getSelfCorrectionCount() + 1);
                    run.setRawOutput(repairResponse);
                    run.setParsedOutput(writeJsonSafe(repaired));
                    run.setStatus("SUCCESS");
                    safeUpdate(run);
                    cacheLlmResult(cacheKey, writeJsonSafe(repaired));
                    contract.setFinishedAt(LocalDateTime.now());
                    contract.setNeedsHumanReview(false);
                    finishAgentRun(agentRun, contract, run, true);
                    log.info("LLM self-correction succeeded: template={}, target={}",
                            templateName, responseType.getSimpleName());
                    return repaired;
                } catch (Exception repairEx) {
                    log.warn("LLM self-correction also failed: template={}, err={}",
                            templateName, repairEx.getMessage());
                }
            }

            run.setStatus("REVIEW");
            safeUpdate(run);
            contract.setSelfCorrectionCount(Math.max(contract.getSelfCorrectionCount(), 1));
            contract.setNeedsHumanReview(true);
            contract.setFinishedAt(LocalDateTime.now());
            finishAgentRun(agentRun, contract, run, false);
            throw new LlmCallException(
                    "LLM output failed schema validation for " + responseType.getSimpleName()
                            + " after self-correction: " + parseEx.getMessage(),
                    parseEx, true, run.getId());
        }
    }

    private AgentRunContract toContract(AgentEnvelope<?> envelope, String templateName) {
        AgentEnvelope.EvidenceCatalog catalog = envelope.getEvidenceCatalog();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (catalog != null && catalog.getMetadata() != null) {
            metadata.putAll(catalog.getMetadata());
        }
        if (catalog != null && catalog.getRequiredEvidenceTypes() != null) {
            metadata.put("requiredEvidenceTypes", catalog.getRequiredEvidenceTypes());
        }
        AgentEnvelope.RequiredEvidencePolicy policy = envelope.getPolicy();
        if (policy != null) {
            metadata.put("evidencePolicyMode", policy.getMode());
            metadata.put("failOnMissingEvidence", policy.isFailOnMissing());
            metadata.put("allowAiInference", policy.isAllowAiInference());
        }
        if (envelope.getTaskId() != null) {
            metadata.put("taskId", envelope.getTaskId());
        }

        return AgentRunContract.builder()
                .contractId(envelope.getContractId())
                .projectId(envelope.getProjectId())
                .agentType(hasText(envelope.getAgentType()) ? envelope.getAgentType() : templateName)
                .agentName(hasText(envelope.getAgentType()) ? envelope.getAgentType() : templateName)
                .inputSchemaVersion(hasText(envelope.getSchemaVersion()) ? envelope.getSchemaVersion() : "1.0")
                .outputSchemaVersion("1.0")
                .usedEvidenceIds(catalog != null ? catalog.getUsedEvidenceIds() : null)
                .omittedBecause(catalog != null ? catalog.getOmittedBecause() : null)
                .metadata(metadata.isEmpty() ? null : metadata)
                .build();
    }

    private void validateEvidencePolicy(AgentEnvelope<?> envelope) {
        AgentEnvelope.RequiredEvidencePolicy policy = envelope.getPolicy();
        if (policy == null) {
            return;
        }
        boolean requiresEvidence = policy.isFailOnMissing()
                || "REQUIRE".equalsIgnoreCase(policy.getMode());
        if (!requiresEvidence) {
            return;
        }

        AgentEnvelope.EvidenceCatalog catalog = envelope.getEvidenceCatalog();
        List<String> usedEvidenceIds = catalog != null ? catalog.getUsedEvidenceIds() : null;
        boolean hasEvidenceIds = usedEvidenceIds != null
                && usedEvidenceIds.stream().anyMatch(this::hasText);
        if (hasEvidenceIds) {
            return;
        }

        List<String> requiredTypes = catalog != null ? catalog.getRequiredEvidenceTypes() : null;
        String required = requiredTypes == null || requiredTypes.isEmpty()
                ? "evidence"
                : String.join(",", requiredTypes);
        throw new LlmCallException(
                "Required evidence missing for " + required + " (agent="
                        + (hasText(envelope.getAgentType()) ? envelope.getAgentType() : "unknown")
                        + ", taskId=" + envelope.getTaskId() + ")",
                null,
                true,
                null);
    }

    /**
     * doc 4.4：对发往外部模型的 prompt 做 PII + 密钥脱敏，返回安全内容。
     */
    private String redactForEgress(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return prompt;
        }
        String masked = piiMaskingService.mask(prompt);
        SecretScanService.SecretScanResult scan = secretScanService.scan(masked);
        return scan.getRedacted() != null ? scan.getRedacted() : masked;
    }

    /**
     * 构造自我修复 prompt：附原始解析错误，要求严格按 schema 重出。
     */
    private String buildSelfCorrectionPrompt(String originalPrompt, Class<?> responseType, Exception parseEx) {
        return originalPrompt
                + "\n\n[自我校正] 上一次输出无法解析为 " + responseType.getSimpleName()
                + " 的 JSON，错误: " + truncate(parseEx.getMessage(), 300)
                + "。请严格按输出格式只输出合法 JSON，不要包含 markdown 代码块或额外说明。";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 实际调用 ChatModel，返回 ChatResponse（含 token 用量）。
     * <p>
     * B-S4：用 {@link RetryTemplate} 包裹真实调用（3 次尝试，2s 固定退避）。因本方法为同类内部私有调用，
     * {@code @Retryable} 注解会因 AOP 代理失效，故采用编程式重试。底层 HTTP 超时由
     * {@code legacy-graph.ai.llm-timeout} 在 {@code LlmProviderService#createChatModel} 处注入。
     */
    private ChatResponse invokeModel(String prompt) {
        OpenAiChatModel chatModel = getChatModelWithHealing(null);
        ChatClient chatClient = ChatClient.create(chatModel);
        return llmRetryTemplate.execute(ctx -> {
            if (ctx.getRetryCount() > 0) {
                log.warn("LLM call retry #{} after backoff", ctx.getRetryCount());
            }
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .chatResponse();
        });
    }

    private String extractText(ChatResponse chatResponse) {
        return chatResponse != null && chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null
                ? chatResponse.getResult().getOutput().getText()
                : null;
    }

    /**
     * 从 ChatResponse 提取 token 用量并写入审计记录（容错，缺失则跳过）。
     * 同时回填到 AgentRunContract。
     */
    private void applyUsage(PromptRun run, ChatResponse chatResponse) {
        try {
            // chatResponse 为 null 时（字符串路径）无法提取 token，跳过
            if (chatResponse == null || chatResponse.getMetadata() == null
                    || chatResponse.getMetadata().getUsage() == null) {
                return;
            }
            var usage = chatResponse.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) {
                run.setPromptTokens(usage.getPromptTokens());
            }
            if (usage.getCompletionTokens() != null) {
                run.setCompletionTokens(usage.getCompletionTokens());
            }
        } catch (Exception ignore) {
            // token 用量为可选审计项，提取失败不影响主流程
        }
    }

    // ==================== AgentRun 合约持久化 ====================

    private AgentRun toAgentRun(AgentRunContract contract, Long promptRunId) {
        AgentRun run = new AgentRun();
        run.setContractId(contract.getContractId());
        run.setProjectId(contract.getProjectId());
        run.setAgentType(contract.getAgentType());
        run.setAgentName(contract.getAgentName());
        run.setInputSchemaVersion(contract.getInputSchemaVersion());
        run.setOutputSchemaVersion(contract.getOutputSchemaVersion());
        run.setUsedEvidenceIds(writeJsonSafe(contract.getUsedEvidenceIds()));
        run.setOmittedBecause(writeJsonSafe(contract.getOmittedBecause()));
        run.setNeedsHumanReview(contract.isNeedsHumanReview() ? 1 : 0);
        run.setModel(contract.getModel());
        run.setRetryCount(contract.getRetryCount());
        run.setSelfCorrectionCount(contract.getSelfCorrectionCount());
        run.setQualityScore(contract.getQualityScore());
        run.setPromptRunId(promptRunId);
        run.setStartedAt(contract.getStartedAt());
        run.setMetadata(writeJsonSafe(contract.getMetadata()));
        try {
            agentRunRepository.insert(run);
        } catch (Exception e) {
            log.warn("Failed to persist AgentRun contract: {}", e.getMessage());
        }
        return run;
    }

    /**
     * 调用结束时回填 token / 成本 / 自校正 / 审核标记并更新合约记录。
     */
    private void finishAgentRun(AgentRun agentRun, AgentRunContract contract, PromptRun promptRun, boolean success) {
        if (agentRun == null) return;
        try {
            agentRun.setPromptTokens(promptRun.getPromptTokens());
            agentRun.setCompletionTokens(promptRun.getCompletionTokens());
            agentRun.setSelfCorrectionCount(contract.getSelfCorrectionCount());
            agentRun.setNeedsHumanReview(contract.isNeedsHumanReview() ? 1 : 0);
            agentRun.setQualityScore(contract.getQualityScore());
            agentRun.setFinishedAt(contract.getFinishedAt() != null ? contract.getFinishedAt() : LocalDateTime.now());
            agentRunRepository.updateById(agentRun);
        } catch (Exception e) {
            log.warn("Failed to update AgentRun contract: {}", e.getMessage());
        }
    }

    // ==================== ChatModel 获取 ====================

    /**
     * 获取当前可用的 ChatModel（从 DB 读取默认提供商）
     */
    private OpenAiChatModel getChatModel() {
        return getChatModel(null);
    }

    /**
     * 获取指定提供商的 ChatModel（带缓存）
     */
    private OpenAiChatModel getChatModel(String providerCode) {
        LlmProvider provider;
        if (providerCode != null && !providerCode.isEmpty()) {
            provider = llmProviderService.getByCode(providerCode);
        } else {
            provider = llmProviderService.getActiveDefault();
        }

        if (provider == null) {
            throw new IllegalStateException("没有可用的 LLM 提供商，请先在系统设置中配置");
        }

        String cacheKey = provider.getProviderCode();
        return chatModelCache.computeIfAbsent(cacheKey, k -> {
            OpenAiChatModel model = llmProviderService.createChatModel(provider);
            log.info("创建 ChatModel: provider={}, model={}, endpoint={}",
                    provider.getProviderCode(), provider.getModelId(), provider.getEndpoint());
            return model;
        });
    }

    /**
     * LLM 调用失败时的自愈机制：若错误提示凭证缺失，清空 ChatModel 缓存并重试一次。
     * 解决 apiConfig 更新后内存缓存未同步的 stale cache 问题。
     */
    private OpenAiChatModel getChatModelWithHealing(String providerCode) {
        try {
            return getChatModel(providerCode);
        } catch (IllegalStateException e) {
            // 非凭证问题，直接抛出
            throw e;
        } catch (Exception e) {
            // 凭证类错误：清缓存 + 重试一次
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("credential") || msg.contains("apiKey") || msg.contains("api_key")) {
                log.warn("LLM credential error detected, clearing chatModelCache and retrying: {}", msg);
                chatModelCache.clear();
                // 同时刷新 DB 缓存（Redis/@Cacheable）
                try {
                    llmProviderService.getActiveDefault(); // 触发重新加载
                } catch (Exception ignore) {}
                return getChatModel(providerCode);
            }
            throw e;
        }
    }

    /**
     * 清除缓存（切换提供商后调用）
     */
    public void clearCache() {
        chatModelCache.clear();
        log.info("LLM ChatModel 缓存已清除");
    }

    // ==================== 辅助方法 ====================

    @SuppressWarnings("unchecked")
    private <T> T deserialize(String json, Class<T> responseType) throws Exception {
        if (responseType == String.class) {
            return (T) json;
        }
        return objectMapper.readValue(json, responseType);
    }

    private void safeUpdate(PromptRun run) {
        try {
            if (run.getId() != null) {
                promptRunRepository.updateById(run);
            }
        } catch (Exception e) {
            log.warn("Failed to update PromptRun audit record: {}", e.getMessage());
        }
    }

    private String writeJsonSafe(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256(String input) {
        if (input == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构造 LLM 结果缓存 key：llm:result:{template}:{inputHash}
     */
    private String llmCacheKey(String templateName, String inputHash) {
        if (inputHash == null) {
            return null;
        }
        return "llm:result:" + templateName + ":" + inputHash;
    }

    /**
     * 回填 LLM 结果缓存（容错，Redis 不可用时忽略）
     */
    private void cacheLlmResult(String cacheKey, String json) {
        if (cacheService == null || cacheKey == null || json == null) {
            return;
        }
        cacheService.putString(cacheKey, json, LLM_CACHE_TTL);
    }

    /**
     * 清理JSON响应，移除markdown代码块标记。
     * 若 JSON 被截断（LLM 输出达到 max_tokens），尝试修复后再返回。
     */
    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length());
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length());
        }
        cleaned = cleaned.trim();
        // 尝试修复截断的 JSON（LLM 输出被 max_tokens 截断时常见）
        return repairTruncatedJson(cleaned);
    }

    /**
     * 修复 LLM 输出中被截断的 JSON。
     * <p>常见场景：模型达到 max_tokens 限制，JSON 在字符串中间或对象中间截断。
     * 修复策略：找到最后一个完整的 JSON token，关闭未闭合的字符串和容器。</p>
     */
    String repairTruncatedJson(String json) {
        if (json == null || json.isEmpty()) return "{}";

        // 先尝试直接解析，成功则无需修复
        try {
            objectMapper.readTree(json);
            return json;
        } catch (Exception ignored) {
            // 解析失败，尝试修复
        }

        StringBuilder sb = new StringBuilder(json);
        boolean inString = false;
        boolean escaped = false;
        int braceDepth = 0;
        int bracketDepth = 0;
        char lastChar = 0;

        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                switch (c) {
                    case '"' -> inString = true;
                    case '{' -> braceDepth++;
                    case '}' -> braceDepth--;
                    case '[' -> bracketDepth++;
                    case ']' -> bracketDepth--;
                }
            }
            lastChar = c;
        }

        // 如果截断在字符串中间，关闭字符串
        if (inString) {
            // 找到最后一个非转义的引号位置并截断
            sb.append('"');
            inString = false;
        }

        // 如果最后非空白字符是逗号或冒号，移除它（不完整的键值对）
        int end = sb.length() - 1;
        while (end >= 0 && Character.isWhitespace(sb.charAt(end))) {
            end--;
        }
        if (end >= 0) {
            char last = sb.charAt(end);
            if (last == ',' || last == ':') {
                sb.setLength(end);
            }
        }

        // 闭合未闭合的容器
        while (bracketDepth > 0) {
            sb.append(']');
            bracketDepth--;
        }
        while (braceDepth > 0) {
            sb.append('}');
            braceDepth--;
        }

        String repaired = sb.toString().trim();
        if (repaired.isEmpty()) return "{}";

        // 验证修复结果
        try {
            objectMapper.readTree(repaired);
            log.debug("JSON truncation repaired: original_len={}, repaired_len={}",
                    json.length(), repaired.length());
            return repaired;
        } catch (Exception e) {
            log.warn("JSON repair failed, returning empty object: {}", e.getMessage());
            return "{}";
        }
    }

    // ==================== 流式调用支持 ====================

    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onToken(String token);
        void onComplete(String fullResponse);
        void onError(Throwable error);
    }

    /**
     * 流式调用 LLM，通过回调逐字返回结果
     */
    public void callStream(String projectId, String templateName,
                           Map<String, String> variables, StreamCallback callback) {
        try {
            String prompt = templateLoader.render(templateName, variables);
            String safePrompt = redactForEgress(prompt);
            log.info("LLM stream call: projectId={}, template={}", projectId, templateName);

            OpenAiChatModel chatModel = getChatModelWithHealing(null);
            ChatClient chatClient = ChatClient.create(chatModel);

            // 使用 Reactor Flux 进行流式调用
            Flux<ChatResponse> stream = chatClient.prompt()
                    .user(safePrompt)
                    .stream()
                    .chatResponse();

            StringBuilder fullResponse = new StringBuilder();
            
            stream.subscribe(
                    response -> {
                        String text = extractText(response);
                        if (text != null) {
                            fullResponse.append(text);
                            callback.onToken(text);
                        }
                    },
                    error -> {
                        log.error("Stream error: {}", error.getMessage());
                        callback.onError(error);
                    },
                    () -> {
                        String finalText = fullResponse.toString().trim();
                        if (finalText.isEmpty()) {
                            log.error("Stream completed but response is empty (possible rate limit or API error)");
                            callback.onError(new LlmCallException("LLM 返回空响应，可能触发了 API 限流，请稍后重试", null, false, null));
                        } else {
                            callback.onComplete(fullResponse.toString());
                            log.info("Stream completed, total length: {}", fullResponse.length());
                        }
                    }
            );
        } catch (Exception e) {
            log.error("Failed to start stream: {}", e.getMessage(), e);
            callback.onError(e);
        }
    }

    /**
     * 同步流式调用，返回 Flux 供调用方处理
     */
    public Flux<String> callStreamFlux(String projectId, String templateName,
                                       Map<String, String> variables) {
        try {
            String prompt = templateLoader.render(templateName, variables);
            String safePrompt = redactForEgress(prompt);
            log.info("LLM stream call (Flux): projectId={}, template={}", projectId, templateName);

            OpenAiChatModel chatModel = getChatModelWithHealing(null);
            ChatClient chatClient = ChatClient.create(chatModel);

            return chatClient.prompt()
                    .user(safePrompt)
                    .stream()
                    .chatResponse()
                    .mapNotNull(this::extractText)
                    .onErrorResume(e -> {
                        log.error("Stream error: {}", e.getMessage());
                        return Flux.empty();
                    });
        } catch (Exception e) {
            log.error("Failed to create stream Flux: {}", e.getMessage(), e);
            return Flux.error(e);
        }
    }
}
