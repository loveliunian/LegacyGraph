package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.AuditLog;
import io.github.legacygraph.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.system.AuditLogService;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    void testList_Empty() {
        Page<AuditLog> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(auditLogRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<AuditLog> result = auditLogService.list(
                1, 10, null, null, null, null, null);

        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    @Test
    void testList_WithData() {
        List<AuditLog> logs = new ArrayList<>();
        AuditLog log = new AuditLog();
        log.setId(1L);
        log.setOperation("LOGIN");
        log.setOperatorName("admin");
        logs.add(log);

        Page<AuditLog> mockPage = new Page<>(1, 10);
        mockPage.setRecords(logs);
        mockPage.setTotal(1);

        when(auditLogRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<AuditLog> result = auditLogService.list(
                1, 10, null, null, null, null, null);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals(1L, result.getList().get(0).getId());
    }

    @Test
    void testList_WithFilters() {
        Page<AuditLog> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(auditLogRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        LocalDateTime startTime = LocalDateTime.now().minusDays(7);
        LocalDateTime endTime = LocalDateTime.now();

        PageResult<AuditLog> result = auditLogService.list(
                1, 10, "LOGIN", "admin", "SUCCESS", startTime, endTime);

        assertNotNull(result);
        verify(auditLogRepository).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void testGetById_Found() {
        AuditLog log = new AuditLog();
        log.setId(1L);
        log.setOperation("LOGIN");

        when(auditLogRepository.selectById(1L)).thenReturn(log);

        AuditLog result = auditLogService.getById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("LOGIN", result.getOperation());
    }

    @Test
    void testGetById_NotFound() {
        when(auditLogRepository.selectById(999L)).thenReturn(null);

        AuditLog result = auditLogService.getById(999L);

        assertNull(result);
    }

    @Test
    void testClear() {
        when(auditLogRepository.delete(any(LambdaQueryWrapper.class))).thenReturn(1);

        assertDoesNotThrow(() -> auditLogService.clear());

        verify(auditLogRepository).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void testDelete_Success() {
        when(auditLogRepository.deleteById(1L)).thenReturn(1);

        boolean result = auditLogService.delete(1L);

        assertTrue(result);
        verify(auditLogRepository).deleteById(1L);
    }

    @Test
    void testDelete_NotFound() {
        when(auditLogRepository.deleteById(999L)).thenReturn(0);

        boolean result = auditLogService.delete(999L);

        assertFalse(result);
    }

    @Test
    void testCount() {
        when(auditLogRepository.selectCount(null)).thenReturn(100L);

        long result = auditLogService.count();

        assertEquals(100L, result);
    }

    @Test
    void testCountZero() {
        when(auditLogRepository.selectCount(null)).thenReturn(0L);

        long result = auditLogService.count();

        assertEquals(0L, result);
    }

    @Test
    void testCountToday() {
        when(auditLogRepository.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);

        long result = auditLogService.countToday();

        assertEquals(5L, result);
        verify(auditLogRepository).selectCount(any(LambdaQueryWrapper.class));

        // Verify the query includes between condition for today
        // (We can't easily check the exact times, but it should be called)
    }
}
