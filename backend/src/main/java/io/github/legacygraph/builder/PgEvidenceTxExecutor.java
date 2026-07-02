package io.github.legacygraph.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PG 证据写入事务执行器。
 * <p>
 * 将 PG 写操作封装在独立的 REQUIRES_NEW 事务中，确保：
 * <ul>
 *   <li>MyBatis-Plus 的 SqlSessionTemplate 能正确参与 Spring 事务同步</li>
 *   <li>内部事务失败不会导致外层事务（含 Neo4j 写入）被标记为 aborted</li>
 *   <li>避免手动 TransactionManager 操作绕过 Spring 事务同步导致的
 *       "current transaction is aborted" 级联失败</li>
 * </ul>
 * </p>
 * <p>
 * 注意：此 Bean 必须独立于 EvidenceGraphWriter，因为 Spring AOP 代理无法拦截
 * 类内部的自调用（self-invocation），@Transactional 注解在同类方法调用中不生效。
 * </p>
 */
@Slf4j
@Component
public class PgEvidenceTxExecutor {

    /**
     * 在独立事务中执行 PG 写操作。
     * <p>REQUIRES_NEW 确保：挂起当前事务（如有），创建新连接+新事务，
     * 失败时只回滚内部事务，不影响外层。</p>
     *
     * @param pgWrite PG 写操作（Lambda/Runnable）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Runnable pgWrite) {
        pgWrite.run();
    }
}
