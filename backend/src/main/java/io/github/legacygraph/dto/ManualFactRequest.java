package io.github.legacygraph.dto;

import io.github.legacygraph.agent.DocUnderstandingAgent;
import lombok.Data;

import java.util.List;

/**
 * 手动创建业务事实请求（不依赖AI）
 */
@Data
public class ManualFactRequest {
    
    /**
     * 版本ID（可选，如果不指定则自动使用最新版本）
     */
    private String versionId;
    
    /**
     * 业务域列表
     */
    private List<DocUnderstandingAgent.BusinessDomain> domains;
    
    /**
     * 业务流程列表
     */
    private List<DocUnderstandingAgent.BusinessProcess> processes;
    
    /**
     * 业务对象列表
     */
    private List<DocUnderstandingAgent.BusinessObject> objects;
    
    /**
     * 业务规则列表
     */
    private List<DocUnderstandingAgent.BusinessRule> rules;
}
