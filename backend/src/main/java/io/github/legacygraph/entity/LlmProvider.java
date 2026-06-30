package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * LLM 提供者配置实体 - 支持多模型、多部署方式、可切换
 */
@Data
@TableName(value = "lg_llm_provider", autoResultMap = true)
public class LlmProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerCode;     // openai / deepseek / qwen / glm / local
    private String modelId;
    private String endpoint;
    private String deploymentMode;   // cloud / private / hybrid

    /**
     * API 配置 (JSONB)
     * 存储 api_key, temperature, max_tokens, top_p 等参数
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> apiConfig;

    /** 是否默认提供商 */
    private Boolean isDefault;

    /** 是否启用 */
    private Boolean isActive;

    private LocalDateTime createdAt;
}
