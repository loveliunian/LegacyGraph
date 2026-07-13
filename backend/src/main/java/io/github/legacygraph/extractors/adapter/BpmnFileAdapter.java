package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.bpmn.BpmnModelParser;
import io.github.legacygraph.extractors.bpmn.BpmnProcessFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * BPMN 文件适配器 — 解析源码仓库中的 .bpmn / .bpmn20.xml 流程定义文件。
 * <p>
 * 使用 camunda-bpmn-model 解析 BPMN 2.0 XML,提取 ProcessDefinition / UserTask /
 * ServiceTask / Gateway 节点及 SequenceFlow 流转边,
 * 并通过 ServiceTask 的 class/expression/delegateExpression 和 Listener 的类引用,
 * 把流程节点与现有 Service/Method 节点连通。
 * </p>
 * <p>
 * 对流程引擎数据库中已部署的流程定义和运行时数据,由 {@code BpmnEngineDbAdapter} 处理。
 * </p>
 */
@Slf4j
@Component
public class BpmnFileAdapter implements ExtractionAdapter {

    private final BpmnModelParser parser;
    private final GraphBuilder graphBuilder;

    public BpmnFileAdapter(BpmnModelParser parser, GraphBuilder graphBuilder) {
        this.parser = parser;
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        if (asset == null || asset.getRelativePath() == null) return false;
        String path = asset.getRelativePath();
        return path.endsWith(".bpmn") || path.endsWith(".bpmn20.xml");
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        try {
            BpmnProcessFact fact = parser.parseFromFile(asset.getFile().toFile());
            if (fact == null || fact.getNodes() == null || fact.getNodes().isEmpty()) {
                return ExtractionResult.builder().processedAssets(0).build();
            }
            graphBuilder.buildBpmnProcessGraph(
                    context.getProjectId(), context.getVersionId(), fact);
            int nodeCount = fact.getNodes().size() + 1; // +1 for ProcessDefinition
            int edgeCount = fact.getFlows().size() + fact.getNodes().size()
                    + fact.getClassRefs().size() + fact.getExprRefs().size();
            log.info("Scanned BPMN process {} from {}: {} nodes, {} flows",
                    fact.getProcessKey(), asset.getRelativePath(),
                    fact.getNodes().size(), fact.getFlows().size());
            return ExtractionResult.builder()
                    .processedAssets(1)
                    .nodeCount(nodeCount)
                    .edgeCount(edgeCount)
                    .summary("Scanned BPMN process: " + fact.getProcessKey())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to extract BPMN from {}: {}", asset.getRelativePath(), e.getMessage());
            return ExtractionResult.builder().processedAssets(0).build();
        }
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("BpmnFileAdapter")
                .languages(Set.of("xml"))
                .fileTypes(Set.of("bpmn", "bpmn20.xml"))
                .frameworks(Set.of("bpmn", "flowable", "camunda", "activiti"))
                .aiEnhanced(false)
                .priority(65) // 略高于 BusinessProcessAdapter(70),先于推断执行
                .build();
    }
}
