package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 提供者配置实体 - 支持多模型、多部署方式
 */
@Data
@TableName("lg_llm_provider")
public class LlmProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerCode;  // openai/qwen/glm/local
    private String modelId;
    private String endpoint;
    private String deploymentMode;  // cloud/private/hybrid
    private String apiConfig;  // JSONB
    private LocalDateTime createdAt;
}
