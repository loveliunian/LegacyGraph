package io.github.legacygraph.service.scan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * S1-T2: 扫描锁服务 — 基于 PostgreSQL advisory lock 实现项目级扫描互斥。
 * <p>
 * 使用 pg_try_advisory_lock(projectIdHash) 尝试获取锁：
 * <ul>
 *   <li>获取成功 → 扫描继续，完成后释放锁</li>
 *   <li>获取失败 → 拒绝扫描，提示"已有扫描在进行"</li>
 * </ul>
 * <p>
 * advisory lock 不依赖表数据，性能高且自动随会话释放（即使 JVM 崩溃也不会死锁）。
 */
@Slf4j
@Service
public class ScanLockService {

    /** advisory lock 的固定 key 前缀，避免与其他业务 advisory lock 冲突 */
    private static final int LOCK_KEY_PREFIX = 0x4C47; // "LG" 的十六进制

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ScanLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 尝试获取项目扫描锁（非阻塞）。
     * <p>
     * 使用 projectId 的哈希值作为 advisory lock 的 key，确保同一项目并发扫描时只有一个能获取锁。
     *
     * @param projectId 项目 ID
     * @return true=获取成功可以扫描，false=已有扫描在进行
     */
    public boolean tryAcquireScanLock(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        try {
            long lockKey = hashProjectId(projectId);
            // pg_try_advisory_lock 返回 true/false，不阻塞
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?, ?)",
                    Boolean.class,
                    LOCK_KEY_PREFIX,
                    lockKey
            );
            if (Boolean.TRUE.equals(acquired)) {
                log.info("Scan lock acquired for projectId={}", projectId);
                return true;
            } else {
                log.warn("Scan lock NOT acquired for projectId={} — another scan is running", projectId);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to acquire scan lock for projectId={}: {}", projectId, e.getMessage());
            // 降级：advisory lock 不可用时放行（不阻塞主流程）
            return true;
        }
    }

    /**
     * 释放项目扫描锁。
     * <p>
     * 扫描完成（无论成功/失败/取消）后必须调用，释放锁供后续扫描使用。
     *
     * @param projectId 项目 ID
     */
    public void releaseScanLock(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        try {
            long lockKey = hashProjectId(projectId);
            Boolean released = jdbcTemplate.queryForObject(
                    "SELECT pg_advisory_unlock(?, ?)",
                    Boolean.class,
                    LOCK_KEY_PREFIX,
                    lockKey
            );
            if (Boolean.TRUE.equals(released)) {
                log.info("Scan lock released for projectId={}", projectId);
            } else {
                log.warn("Scan lock release returned false for projectId={} — lock not held by this session", projectId);
            }
        } catch (Exception e) {
            log.error("Failed to release scan lock for projectId={}: {}", projectId, e.getMessage());
        }
    }

    /**
     * 检查项目是否正在被扫描（锁是否已被持有）。
     * <p>
     * 注意：advisory lock 是会话级的，此方法尝试获取再立即释放来判断锁状态，
     * 存在 TOCTOU 竞态，仅用于状态查询/前端展示，不用于互斥判断。
     *
     * @param projectId 项目 ID
     * @return true=锁已被持有（正在扫描），false=锁空闲
     */
    public boolean isScanRunning(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        try {
            long lockKey = hashProjectId(projectId);
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?, ?)",
                    Boolean.class,
                    LOCK_KEY_PREFIX,
                    lockKey
            );
            if (Boolean.TRUE.equals(acquired)) {
                // 锁是空闲的，立即释放
                jdbcTemplate.queryForObject(
                        "SELECT pg_advisory_unlock(?, ?)",
                        Boolean.class,
                        LOCK_KEY_PREFIX,
                        lockKey
                );
                return false;
            }
            return true; // 锁已被持有
        } catch (Exception e) {
            log.debug("Failed to check scan lock status for projectId={}: {}", projectId, e.getMessage());
            return false;
        }
    }

    /**
     * 将 projectId 字符串映射为 int64 键。
     * 使用 String.hashCode() 并保留低 63 位，确保同一 projectId 产生稳定的键值。
     */
    private long hashProjectId(String projectId) {
        return (long) projectId.hashCode() & 0x7FFFFFFFL;
    }
}
