package io.github.legacygraph.extractors.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.repository.FactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FactPersister 单元测试。
 * 验证 Fact 持久化助手将抽取事实正确入库。
 */
@ExtendWith(MockitoExtension.class)
class FactPersisterTest {

    @Mock
    private FactRepository factRepository;

    private FactPersister persister;

    @BeforeEach
    void setUp() {
        persister = new FactPersister(factRepository, new ObjectMapper());
    }

    /**
     * 测试 saveFact 构造 Fact 并调用 upsert。
     */
    @Test
    void saveFact_constructsAndUpsertsFact() {
        when(factRepository.upsert(any(Fact.class))).thenReturn(1);

        persister.saveFact("project-1", "v1", "MAPPER_XML", "MAPPER",
                "com.demo.OrderMapper", "OrderMapper",
                "/src/mapper/OrderMapper.xml", 1, 50,
                "sample data", BigDecimal.ONE, "EXTRACTED");

        ArgumentCaptor<Fact> captor = ArgumentCaptor.forClass(Fact.class);
        verify(factRepository).upsert(captor.capture());
        Fact fact = captor.getValue();

        assertEquals("project-1", fact.getProjectId());
        assertEquals("v1", fact.getVersionId());
        assertEquals("MAPPER_XML", fact.getSourceType());
        assertEquals("MAPPER", fact.getFactType());
        assertEquals("com.demo.OrderMapper", fact.getFactKey());
        assertEquals("OrderMapper", fact.getFactName());
        assertEquals("/src/mapper/OrderMapper.xml", fact.getSourcePath());
        assertEquals(1, fact.getStartLine());
        assertEquals(50, fact.getEndLine());
        assertEquals(1.0, fact.getConfidence());
        assertEquals("EXTRACTED", fact.getStatus());
        assertNotNull(fact.getNormalizedData());
        assertNotNull(fact.getId());
    }

    /**
     * 测试 null confidence 降级为 0.0。
     */
    @Test
    void saveFact_nullConfidence_defaultsToZero() {
        when(factRepository.upsert(any(Fact.class))).thenReturn(1);

        persister.saveFact("project-1", "v1", "DOCUMENT", "DOC",
                "readme.md", "README",
                "/doc/readme.md", null, null,
                "content", null, "EXTRACTED");

        ArgumentCaptor<Fact> captor = ArgumentCaptor.forClass(Fact.class);
        verify(factRepository).upsert(captor.capture());

        assertEquals(0.0, captor.getValue().getConfidence());
        assertNull(captor.getValue().getStartLine());
    }

    /**
     * 测试 saveFact 异常时被吞掉（不传播）。
     */
    @Test
    void saveFact_whenUpsertFails_doesNotThrow() {
        doThrow(new RuntimeException("DB error")).when(factRepository).upsert(any(Fact.class));

        assertDoesNotThrow(() -> persister.saveFact(
                "project-1", "v1", "DOC", "DOC",
                "key", "name", "/path", null, null,
                "data", BigDecimal.ONE, "EXTRACTED"));
    }

    @Test
    void saveFacts_batchesDraftsInOneRepositoryCall() {
        when(factRepository.batchUpsert(anyList())).thenReturn(2);

        persister.saveFacts(List.of(
                FactPersister.FactDraft.builder()
                        .projectId("project-1")
                        .versionId("v1")
                        .sourceType("CODE_AST")
                        .factType("SERVICE_CALL")
                        .factKey("OrderService.create")
                        .factName("OrderService -> OrderMapper.insert")
                        .sourcePath("OrderService.java")
                        .startLine(10)
                        .endLine(10)
                        .data("call-1")
                        .confidence(BigDecimal.ONE)
                        .status("EXTRACTED")
                        .build(),
                FactPersister.FactDraft.builder()
                        .projectId("project-1")
                        .versionId("v1")
                        .sourceType("CODE_AST")
                        .factType("SERVICE_CALL")
                        .factKey("OrderService.update")
                        .factName("OrderService -> OrderMapper.update")
                        .sourcePath("OrderService.java")
                        .startLine(20)
                        .endLine(20)
                        .data("call-2")
                        .confidence(null)
                        .status("EXTRACTED")
                        .build()));

        ArgumentCaptor<List<Fact>> captor = ArgumentCaptor.forClass(List.class);
        verify(factRepository).batchUpsert(captor.capture());
        verify(factRepository, never()).upsert(any(Fact.class));

        List<Fact> facts = captor.getValue();
        assertEquals(2, facts.size());
        assertEquals("project-1", facts.get(0).getProjectId());
        assertEquals("SERVICE_CALL", facts.get(0).getFactType());
        assertEquals(0.0, facts.get(1).getConfidence());
        assertNotNull(facts.get(0).getNormalizedData());
    }
}
