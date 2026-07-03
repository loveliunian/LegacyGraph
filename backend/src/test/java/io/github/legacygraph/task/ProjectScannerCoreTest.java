package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectScanner 核心路径单元测试。
 * 见 doc/前后端架构整体分析与改进建议.md B-S5。
 */
@ExtendWith(MockitoExtension.class)
class ProjectScannerCoreTest {

    @Mock private ScanVersionRepository scanVersionRepository;
    @Mock private ScanTaskRepository scanTaskRepository;
    @Mock private FactRepository factRepository;
    @Mock private DbConnectionRepository dbConnectionRepository;
    @Mock private CodeRepoRepository codeRepoRepository;
    @Mock private DocumentRepository documentRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证 ProjectScanner 构造函数不抛异常。
     * （适配器注入路径另有 ProjectScannerAdapterTest 覆盖）
     */
    @Test
    void shouldConstructWithoutAdapters() {
        assertDoesNotThrow(() -> new ProjectScanner(
                scanVersionRepository,
                scanTaskRepository,
                factRepository,
                dbConnectionRepository,
                codeRepoRepository,
                documentRepository,
                null,  // graphBuilder
                null,  // frontendGraphBuilder
                null,  // neo4jGraphDao
                objectMapper,
                null,  // aiScanOrchestrator
                null,  // dbSchemaAnalysisAgent
                null,  // extractionAdapterRegistry
                null,  // scanTaskRecorder
                null   // adapterExecutionService
        ));
    }

    @Test
    void resolveAiConfigForScan_respectsExplicitDisableWhenDocParseSelected() {
        AiScanConfig config = ProjectScanner.resolveAiConfigForScan(
                "{\"scanTypes\":[\"DOC_PARSE\"],\"enableAi\":false}",
                List.of("DOC_PARSE"),
                objectMapper,
                false,
                false,
                0.6);

        assertFalse(config.isEnableAi());
    }

    @Test
    void resolveAiConfigForScan_defaultsDocParseToAiWhenSwitchMissing() {
        AiScanConfig config = ProjectScanner.resolveAiConfigForScan(
                "{\"scanTypes\":[\"DOC_PARSE\"]}",
                List.of("DOC_PARSE"),
                objectMapper,
                false,
                false,
                0.6);

        assertTrue(config.isEnableAi());
    }
}
