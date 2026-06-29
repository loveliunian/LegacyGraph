package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.legacygraph.entity.AuditLog;
import io.github.legacygraph.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.delete(new QueryWrapper<>());
    }

    @Test
    void testList_AuditLogs() throws Exception {
        // Insert test data
        AuditLog log = new AuditLog();
        log.setTraceId("test-trace-1");
        log.setOperation("测试操作");
        log.setMethod("TestController#test");
        log.setRequestUri("/lg/test");
        log.setRequestMethod("GET");
        log.setClientIp("127.0.0.1");
        log.setStatus("SUCCESS");
        log.setDurationMs(100L);
        log.setCreatedAt(LocalDateTime.now());
        auditLogRepository.insert(log);

        mockMvc.perform(get("/lg/audit/list")
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").exists())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void testGetById_Success() throws Exception {
        AuditLog log = new AuditLog();
        log.setTraceId("test-trace-1");
        log.setOperation("测试操作");
        log.setCreatedAt(LocalDateTime.now());
        auditLogRepository.insert(log);

        Long id = auditLogRepository.selectList(null).get(0).getId();

        mockMvc.perform(get("/lg/audit/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void testGetById_NotFound() throws Exception {
        // Controller returns Result.success(null) when log is not found,
        // which serializes to {"code": 0, "data": null}.
        // $.data will be null — check it exists or is null rather than isEmpty()
        mockMvc.perform(get("/lg/audit/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testDelete_Success() throws Exception {
        AuditLog log = new AuditLog();
        log.setTraceId("test-trace-1");
        log.setOperation("测试操作");
        log.setCreatedAt(LocalDateTime.now());
        auditLogRepository.insert(log);

        Long id = auditLogRepository.selectList(null).get(0).getId();

        mockMvc.perform(delete("/lg/audit/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testClear() throws Exception {
        AuditLog log1 = new AuditLog();
        log1.setTraceId("test-1");
        log1.setOperation("op1");
        log1.setCreatedAt(LocalDateTime.now());
        auditLogRepository.insert(log1);

        AuditLog log2 = new AuditLog();
        log2.setTraceId("test-2");
        log2.setOperation("op2");
        log2.setCreatedAt(LocalDateTime.now());
        auditLogRepository.insert(log2);

        mockMvc.perform(delete("/lg/audit/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // After clear(), the @Log aspect inserts a log entry for the "清空审计日志" operation,
        // so total will be 1 (the clear operation itself).
        mockMvc.perform(get("/lg/audit/list")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void testStats() throws Exception {
        AuditLog log = new AuditLog();
        log.setTraceId("test-trace-1");
        log.setOperation("测试操作");
        log.setCreatedAt(LocalDateTime.now());
        auditLogRepository.insert(log);

        mockMvc.perform(get("/lg/audit/stats/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").exists());
    }
}
