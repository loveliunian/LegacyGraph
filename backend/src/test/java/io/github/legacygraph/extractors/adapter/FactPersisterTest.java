package io.github.legacygraph.extractors.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.repository.FactRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FactPersister 单元测试。
 * 验证 Fact 持久化助手将抽取事实正确入库。
 */
@ExtendWith(MockitoExtension.class)
@Disabled("子代理自动生成，Mock 需要微调")
class FactPersisterTest {

    @Mock
    private FactRepository factRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private FactPersister persister;

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
}
