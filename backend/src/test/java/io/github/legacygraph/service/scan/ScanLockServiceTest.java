package io.github.legacygraph.service.scan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S1-T2: ScanLockService 并发互斥测试。
 * 验证：同一 projectId 并发调用 tryAcquireScanLock，仅第一个能成功；释放后可再次获取。
 */
class ScanLockServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ScanLockService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ScanLockService(jdbcTemplate);
    }

    @Test
    void tryAcquireScanLock_firstCall_returnsTrue() {
        // queryForObject(String, Class<T>, Object...) — 用 any(String) + eq(Boolean.class) + any() varargs 匹配
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true);

        assertTrue(service.tryAcquireScanLock("project-A"),
                "首次获取锁应返回 true");
    }

    @Test
    void tryAcquireScanLock_secondConcurrentCall_returnsFalse() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true)
                .thenReturn(false);

        assertTrue(service.tryAcquireScanLock("project-A"));
        assertFalse(service.tryAcquireScanLock("project-A"),
                "同 projectId 第二次 tryAcquireScanLock 应返回 false（互斥）");
    }

    @Test
    void tryAcquireScanLock_afterRelease_acquireAgainSucceeds() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true)  // acquire 1
                .thenReturn(true)  // release
                .thenReturn(true); // acquire 2

        assertTrue(service.tryAcquireScanLock("project-A"));
        service.releaseScanLock("project-A");
        assertTrue(service.tryAcquireScanLock("project-A"),
                "释放锁后再次获取应成功");
    }

    @Test
    void tryAcquireScanLock_nullProjectId_returnsFalse() {
        assertFalse(service.tryAcquireScanLock(null));
        assertFalse(service.tryAcquireScanLock(""));
        assertFalse(service.tryAcquireScanLock("   "));
    }

    @Test
    void tryAcquireScanLock_pgFailure_degradesGracefully() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
                .thenThrow(new DataAccessException("PG unavailable") {});

        assertTrue(service.tryAcquireScanLock("project-A"),
                "PG 不可用时降级放行（不阻塞主流程）");
    }

    @Test
    void releaseScanLock_returnsFalse_shouldNotThrow() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
                .thenReturn(false);

        // 即便 PG 返回 false（锁未被当前会话持有），也不能抛异常
        service.releaseScanLock("project-A");
    }

    @Test
    void isScanRunning_lockFree_returnsFalse() {
        // isScanRunning 内部 try 成功（锁空闲）→ 立即释放 → 返回 false
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true)   // tryAdvisoryLock 成功
                .thenReturn(true);  // advisoryUnlock 成功

        assertFalse(service.isScanRunning("project-A"),
                "锁空闲时 isScanRunning 应返回 false");
    }

    @Test
    void isScanRunning_lockHeldByOther_returnsTrue() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
                .thenReturn(false); // tryAdvisoryLock 失败（已被持有）

        assertTrue(service.isScanRunning("project-A"),
                "锁被持有时 isScanRunning 应返回 true");
    }
}