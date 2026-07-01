package io.github.legacygraph.task;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.DbSchemaAnalysisAgent;
import io.github.legacygraph.builder.FrontendGraphBuilder;
import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.extractors.adapter.ExtractionAdapterRegistry;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectScannerProgressLoggingTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogAppender() {
        logger = (Logger) LoggerFactory.getLogger(ProjectScanner.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void startFullScanLogsTaskStartAndStillRunningHeartbeat(@TempDir Path tempDir) {
        ScanVersionRepository scanVersionRepository = mock(ScanVersionRepository.class);
        ScanTaskRepository scanTaskRepository = mock(ScanTaskRepository.class);
        FactRepository factRepository = mock(FactRepository.class);
        DbConnectionRepository dbConnectionRepository = mock(DbConnectionRepository.class);
        CodeRepoRepository codeRepoRepository = mock(CodeRepoRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        GraphBuilder graphBuilder = mock(GraphBuilder.class);
        FrontendGraphBuilder frontendGraphBuilder = mock(FrontendGraphBuilder.class);
        AiScanOrchestrator aiScanOrchestrator = mock(AiScanOrchestrator.class);
        DbSchemaAnalysisAgent dbSchemaAnalysisAgent = mock(DbSchemaAnalysisAgent.class);
        ExtractionAdapterRegistry extractionAdapterRegistry = mock(ExtractionAdapterRegistry.class);

        String projectId = "project-1";
        String versionId = "version-1";
        ScanVersion version = new ScanVersion();
        version.setId(versionId);

        doReturn(version).when(scanVersionRepository).getById(versionId);
        when(scanTaskRepository.insert(any(ScanTask.class))).thenReturn(1);
        when(scanTaskRepository.updateById(any(ScanTask.class))).thenReturn(1);
        when(codeRepoRepository.selectList(any())).thenReturn(Collections.emptyList());
        when(dbConnectionRepository.selectList(any())).thenReturn(Collections.emptyList());

        ProjectScanner scanner = new ProjectScanner(
                scanVersionRepository,
                scanTaskRepository,
                factRepository,
                dbConnectionRepository,
                codeRepoRepository,
                documentRepository,
                graphBuilder,
                frontendGraphBuilder,
                null,
                new ObjectMapper(),
                aiScanOrchestrator,
                dbSchemaAnalysisAgent,
                extractionAdapterRegistry
        );

        scanner.startFullScan(projectId, versionId, tempDir.toString());

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(messages)
                .anyMatch(message -> message.contains("Scan task started") && message.contains("DB_DISCOVERY"));
        assertThat(messages)
                .anyMatch(message -> message.contains("Scan still running") && message.contains("BACKEND_SCAN"));
    }
}
