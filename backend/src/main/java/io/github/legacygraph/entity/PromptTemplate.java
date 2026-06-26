package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 模板实体 - 版本化管理 Prompt
 */
@Data
@TableName("lg_prompt_template")
public class PromptTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String templateCode;
    private String version;
    private String scene;  // code/doc/merge/test/review
    private String systemPrompt;
    private String domainPrompt;
    private String taskPrompt;
    private String outputSchema; // JSONB
    private Boolean isActive;
    private LocalDateTime createdAt;
}
