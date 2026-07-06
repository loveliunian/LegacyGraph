package io.github.legacygraph.federation;

import io.github.legacygraph.common.EdgeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 跨仓库关系链接器，用于发现和生成不同项目/仓库之间的潜在关联
 */
@Slf4j
@Component
public class CrossRepositoryLinker {
    
    /**
     * 共享表访问记录
     */
    public record TableAccess(
        String projectId,       // 项目ID
        String nodeKey,         // 节点键（如 method:createOrder）
        String tableName,       // 表名
        String accessType       // 访问类型：READ 或 WRITE
    ) {}
    
    /**
     * 外部API调用记录
     */
    public record ExternalApiCall(
        String projectId,       // 项目ID
        String nodeKey,         // 节点键
        String apiEndpoint,     // API端点
        String httpMethod,      // HTTP方法
        boolean isProvider      // 是否为提供者（true=提供，false=调用）
    ) {}
    
    /**
     * 消息Topic记录
     */
    public record TopicAccess(
        String projectId,       // 项目ID
        String nodeKey,         // 节点键
        String topicName,       // Topic名称
        String accessType       // 访问类型：PRODUCE 或 CONSUME
    ) {}
    
    /**
     * 基于共享表访问生成跨仓库 AFFECTS 关系
     * 
     * 规则：writer -> reader 生成 AFFECTS 候选关系
     */
    public List<CrossRepositoryLinkCandidate> linkSharedTableAccess(List<TableAccess> accesses) {
        log.debug("开始分析共享表访问，共 {} 条记录", accesses.size());
        
        List<CrossRepositoryLinkCandidate> candidates = new ArrayList<>();
        
        // 按表名分组
        Map<String, List<TableAccess>> byTable = accesses.stream()
            .collect(Collectors.groupingBy(TableAccess::tableName));
        
        for (Map.Entry<String, List<TableAccess>> entry : byTable.entrySet()) {
            String tableName = entry.getKey();
            List<TableAccess> tableAccesses = entry.getValue();
            
            // 找出所有 writer 和 reader
            List<TableAccess> writers = tableAccesses.stream()
                .filter(a -> "WRITE".equals(a.accessType))
                .toList();
            List<TableAccess> readers = tableAccesses.stream()
                .filter(a -> "READ".equals(a.accessType))
                .toList();
            
            // 对每个 writer，生成到所有 reader 的 AFFECTS 关系（跨项目）
            for (TableAccess writer : writers) {
                for (TableAccess reader : readers) {
                    // 只生成跨项目的关系
                    if (!writer.projectId.equals(reader.projectId)) {
                        String evidenceId = String.format("shared-table:%s:%s->%s", 
                            tableName, writer.projectId, reader.projectId);
                        
                        candidates.add(new CrossRepositoryLinkCandidate(
                            writer.projectId,
                            writer.nodeKey,
                            reader.projectId,
                            reader.nodeKey,
                            EdgeType.AFFECTS,
                            0.95,  // 共享表证据置信度高
                            String.format("共享表 '%s': %s 写入，%s 读取", 
                                tableName, writer.nodeKey, reader.nodeKey),
                            Set.of(evidenceId)
                        ));
                    }
                }
            }
        }
        
        log.info("共享表分析完成，生成 {} 个跨仓库候选关系", candidates.size());
        return candidates;
    }
    
    /**
     * 基于外部API调用生成跨仓库 CALLS_EXTERNAL 关系
     * 
     * 规则：provider -> caller 生成 CALLS_EXTERNAL 候选关系
     */
    public List<CrossRepositoryLinkCandidate> linkExternalApiCalls(List<ExternalApiCall> calls) {
        log.debug("开始分析外部API调用，共 {} 条记录", calls.size());
        
        List<CrossRepositoryLinkCandidate> candidates = new ArrayList<>();
        
        // 按 API 端点分组
        Map<String, List<ExternalApiCall>> byEndpoint = calls.stream()
            .collect(Collectors.groupingBy(ExternalApiCall::apiEndpoint));
        
        for (Map.Entry<String, List<ExternalApiCall>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<ExternalApiCall> endpointCalls = entry.getValue();
            
            // 找出 provider 和 caller
            List<ExternalApiCall> providers = endpointCalls.stream()
                .filter(c -> c.isProvider)
                .toList();
            List<ExternalApiCall> callers = endpointCalls.stream()
                .filter(c -> !c.isProvider)
                .toList();
            
            // 对每个 provider，生成到所有 caller 的 CALLS_EXTERNAL 关系（跨项目）
            for (ExternalApiCall provider : providers) {
                for (ExternalApiCall caller : callers) {
                    if (!provider.projectId.equals(caller.projectId)) {
                        String evidenceId = String.format("external-api:%s:%s->%s", 
                            endpoint, provider.projectId, caller.projectId);
                        
                        candidates.add(new CrossRepositoryLinkCandidate(
                            provider.projectId,
                            provider.nodeKey,
                            caller.projectId,
                            caller.nodeKey,
                            EdgeType.CALLS_EXTERNAL,
                            0.90,  // API 证据置信度较高
                            String.format("外部API '%s': %s 提供，%s 调用", 
                                endpoint, provider.nodeKey, caller.nodeKey),
                            Set.of(evidenceId)
                        ));
                    }
                }
            }
        }
        
        log.info("外部API分析完成，生成 {} 个跨仓库候选关系", candidates.size());
        return candidates;
    }
    
    /**
     * 基于消息Topic生成跨仓库 TRIGGERS 关系
     * 
     * 规则：producer -> consumer 生成 TRIGGERS 候选关系
     */
    public List<CrossRepositoryLinkCandidate> linkTopicMessages(List<TopicAccess> topics) {
        log.debug("开始分析消息Topic，共 {} 条记录", topics.size());
        
        List<CrossRepositoryLinkCandidate> candidates = new ArrayList<>();
        
        // 按 Topic 名称分组
        Map<String, List<TopicAccess>> byTopic = topics.stream()
            .collect(Collectors.groupingBy(TopicAccess::topicName));
        
        for (Map.Entry<String, List<TopicAccess>> entry : byTopic.entrySet()) {
            String topicName = entry.getKey();
            List<TopicAccess> topicAccesses = entry.getValue();
            
            // 找出 producer 和 consumer
            List<TopicAccess> producers = topicAccesses.stream()
                .filter(t -> "PRODUCE".equals(t.accessType))
                .toList();
            List<TopicAccess> consumers = topicAccesses.stream()
                .filter(t -> "CONSUME".equals(t.accessType))
                .toList();
            
            // 对每个 producer，生成到所有 consumer 的 TRIGGERS 关系（跨项目）
            for (TopicAccess producer : producers) {
                for (TopicAccess consumer : consumers) {
                    if (!producer.projectId.equals(consumer.projectId)) {
                        String evidenceId = String.format("topic:%s:%s->%s", 
                            topicName, producer.projectId, consumer.projectId);
                        
                        candidates.add(new CrossRepositoryLinkCandidate(
                            producer.projectId,
                            producer.nodeKey,
                            consumer.projectId,
                            consumer.nodeKey,
                            EdgeType.TRIGGERS,
                            0.85,  // Topic 证据置信度中等
                            String.format("消息Topic '%s': %s 生产，%s 消费", 
                                topicName, producer.nodeKey, consumer.nodeKey),
                            Set.of(evidenceId)
                        ));
                    }
                }
            }
        }
        
        log.info("消息Topic分析完成，生成 {} 个跨仓库候选关系", candidates.size());
        return candidates;
    }
    
    /**
     * 综合所有类型的跨仓库关系生成
     */
    public List<CrossRepositoryLinkCandidate> linkAll(
            List<TableAccess> tableAccesses,
            List<ExternalApiCall> apiCalls,
            List<TopicAccess> topicAccesses) {
        
        List<CrossRepositoryLinkCandidate> all = new ArrayList<>();
        all.addAll(linkSharedTableAccess(tableAccesses));
        all.addAll(linkExternalApiCalls(apiCalls));
        all.addAll(linkTopicMessages(topicAccesses));
        
        log.info("跨仓库链接分析完成，共生成 {} 个候选关系", all.size());
        return all;
    }
}
