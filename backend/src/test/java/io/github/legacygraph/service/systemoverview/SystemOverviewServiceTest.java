package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.dto.systemoverview.LayerMappingDTO;
import io.github.legacygraph.dto.systemoverview.SystemOverviewDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SystemOverviewService} 单元测试。
 * 验证 12 业务域映射、按域模糊匹配、Markdown 报告生成。
 */
class SystemOverviewServiceTest {

    private SystemOverviewService service;

    @BeforeEach
    void setUp() {
        service = new SystemOverviewService();
    }

    @Test
    void getOverview_returns12DomainsAnd5Paths() {
        SystemOverviewDTO dto = service.getOverview("p1", "v1");

        assertEquals("p1", dto.getProjectId());
        assertEquals(12, dto.getMappings().size());
        assertEquals(5, dto.getCorePaths().size());
        assertEquals(12, dto.getTotalDomains());
    }

    @Test
    void getDomain_fuzzyMatchByCapability() {
        List<LayerMappingDTO> rows = service.getDomain("p1", "v1", "QA");

        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(m ->
                m.getBusinessDomain().contains("QA") || m.getCapability().contains("QA")));
    }

    @Test
    void getDomain_blankReturnsAll() {
        List<LayerMappingDTO> rows = service.getDomain("p1", "v1", "");
        assertEquals(12, rows.size());
    }

    @Test
    void generateMarkdown_containsKeySections() {
        String md = service.generateMarkdown("p1", "v1");

        assertTrue(md.contains("系统关系总览报告"));
        assertTrue(md.contains("业务域映射总表"));
        assertTrue(md.contains("核心贯穿链路"));
        assertTrue(md.contains("ProjectController"));
        assertTrue(md.contains("BusinessDomain CONTAINS Feature"));
    }
}
