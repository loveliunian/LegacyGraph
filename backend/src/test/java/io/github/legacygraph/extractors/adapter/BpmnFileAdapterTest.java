package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.bpmn.BpmnModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BpmnFileAdapter 单元测试。
 * 验证 supports/extract/capability,以及与 BpmnModelParser + GraphBuilder 的集成。
 */
@ExtendWith(MockitoExtension.class)
class BpmnFileAdapterTest {

    @TempDir
    Path tempDir;

    @Mock
    private GraphBuilder graphBuilder;

    @InjectMocks
    private BpmnFileAdapter adapter;

    private static final String BPMN_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                         targetNamespace="http://test">
              <process id="leaveProcess" name="请假流程" isExecutable="true">
                <startEvent id="start"/>
                <userTask id="approve" name="审批" camunda:assignee="${manager}"/>
                <serviceTask id="auto" name="自动审批"
                             camunda:expression="${leaveService.approve(task)}"/>
                <exclusiveGateway id="gw" name="判断"/>
                <endEvent id="end"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                <sequenceFlow id="f2" sourceRef="approve" targetRef="gw"/>
                <sequenceFlow id="f3" sourceRef="gw" targetRef="auto">
                  <conditionExpression>${days &gt; 3}</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f4" sourceRef="gw" targetRef="end"/>
                <sequenceFlow id="f5" sourceRef="auto" targetRef="end"/>
              </process>
            </definitions>
            """;

    @Test
    void supports_returnsTrueForBpmnFile() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .file(Path.of("/src/processes/leave.bpmn"))
                .relativePath("src/main/resources/processes/leave.bpmn")
                .fileType("bpmn")
                .build();

        assertTrue(adapter.supports(ctx, asset));
    }

    @Test
    void supports_returnsTrueForBpmn20XmlFile() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .relativePath("src/main/resources/processes/leave.bpmn20.xml")
                .build();

        assertTrue(adapter.supports(ctx, asset));
    }

    @Test
    void supports_returnsFalseForJavaFile() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .relativePath("src/main/java/Foo.java")
                .build();

        assertFalse(adapter.supports(ctx, asset));
    }

    @Test
    void supports_returnsFalseForPomXml() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .relativePath("pom.xml")
                .build();

        assertFalse(adapter.supports(ctx, asset));
    }

    @Test
    void supports_returnsFalseForNullAsset() {
        ScanContext ctx = ScanContext.builder().build();
        assertFalse(adapter.supports(ctx, null));
    }

    @Test
    void extract_parsesBpmnAndCallsGraphBuilder() throws Exception {
        // BpmnFileAdapter 使用真实的 BpmnModelParser(通过构造器注入)
        // 这里重新构造 adapter,使用真实 parser + mock graphBuilder
        BpmnModelParser realParser = new BpmnModelParser();
        BpmnFileAdapter adapterWithRealParser = new BpmnFileAdapter(realParser, graphBuilder);

        Path bpmnFile = tempDir.resolve("leave.bpmn");
        Files.writeString(bpmnFile, BPMN_XML);

        ScanContext ctx = ScanContext.builder()
                .projectId("p1").versionId("v1")
                .build();
        SourceAsset asset = SourceAsset.builder()
                .file(bpmnFile)
                .relativePath("processes/leave.bpmn")
                .fileType("bpmn")
                .build();

        ExtractionResult result = adapterWithRealParser.extract(ctx, asset);

        assertEquals(1, result.getProcessedAssets());
        // 1 ProcessDefinition + 1 UserTask + 1 ServiceTask + 1 Gateway = 4 节点
        assertEquals(4, result.getNodeCount());
        verify(graphBuilder, times(1)).buildBpmnProcessGraph(eq("p1"), eq("v1"), any());
    }

    @Test
    void extract_returnsZeroForInvalidBpmn() throws Exception {
        BpmnModelParser realParser = new BpmnModelParser();
        BpmnFileAdapter adapterWithRealParser = new BpmnFileAdapter(realParser, graphBuilder);

        Path invalidFile = tempDir.resolve("invalid.bpmn");
        Files.writeString(invalidFile, "<not-bpmn/>");

        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .file(invalidFile)
                .relativePath("processes/invalid.bpmn")
                .fileType("bpmn")
                .build();

        ExtractionResult result = adapterWithRealParser.extract(ctx, asset);

        assertEquals(0, result.getProcessedAssets());
        verify(graphBuilder, never()).buildBpmnProcessGraph(anyString(), anyString(), any());
    }

    @Test
    void capability_returnsCorrectInfo() {
        AdapterCapability cap = adapter.capability();

        assertEquals("BpmnFileAdapter", cap.getName());
        assertTrue(cap.getFrameworks().contains("bpmn"));
        assertTrue(cap.getFrameworks().contains("flowable"));
        assertTrue(cap.getFrameworks().contains("camunda"));
        assertTrue(cap.getFrameworks().contains("activiti"));
        assertTrue(cap.getFileTypes().contains("bpmn"));
        assertFalse(cap.isAiEnhanced());
        assertEquals(65, cap.getPriority());
    }
}
