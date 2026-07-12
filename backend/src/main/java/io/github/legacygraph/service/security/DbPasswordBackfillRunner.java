package io.github.legacygraph.service.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.repository.DbConnectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * S1-T5: 数据库连接密码回填组件。
 * <p>
 * 启动时扫描所有 DbConnection 记录，对 passwordCipher 为空但 password 列有明文值的记录，
 * 使用 SecretCipher 加密后写入 passwordCipher，并将 password 列替换为脱敏值。
 * </p>
 * <p>
 * 判断"明文"的条件：password 不为空、不含 "***"（脱敏标记）、且 passwordCipher 为空。
 * </p>
 */
@Slf4j
@Component
@Order(50)
public class DbPasswordBackfillRunner implements CommandLineRunner {

    private final DbConnectionRepository dbConnectionRepository;
    private final SecretCipher secretCipher;

    @Autowired
    public DbPasswordBackfillRunner(DbConnectionRepository dbConnectionRepository,
                                     SecretCipher secretCipher) {
        this.dbConnectionRepository = dbConnectionRepository;
        this.secretCipher = secretCipher;
    }

    @Override
    public void run(String... args) {
        try {
            backfillPlaintextPasswords();
        } catch (Exception e) {
            log.error("DbPassword backfill failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 扫描所有 passwordCipher 为空且 password 含明文的记录，执行一次性加密回填。
     */
    private void backfillPlaintextPasswords() {
        LambdaQueryWrapper<DbConnection> wrapper = new LambdaQueryWrapper<DbConnection>()
                .and(w -> w.isNull(DbConnection::getPasswordCipher)
                          .or().eq(DbConnection::getPasswordCipher, ""))
                .isNotNull(DbConnection::getPassword);
        List<DbConnection> connections = dbConnectionRepository.selectList(wrapper);

        if (connections.isEmpty()) {
            log.info("DbPassword backfill: no plaintext passwords found, skip");
            return;
        }

        int backfilled = 0;
        int skipped = 0;
        for (DbConnection conn : connections) {
            String password = conn.getPassword();
            if (password == null || password.isEmpty()) {
                skipped++;
                continue;
            }
            // 跳过已脱敏的密码（含 *** 标记）
            if (password.contains("***")) {
                skipped++;
                continue;
            }

            try {
                // 加密明文密码
                String cipher = secretCipher.encrypt(password);
                String masked = secretCipher.mask(password);
                conn.setPasswordCipher(cipher);
                conn.setPassword(masked);
                dbConnectionRepository.updateById(conn);
                backfilled++;
                log.info("Backfilled password for DbConnection: id={}, name={}",
                        conn.getId(), conn.getConnectionName());
            } catch (Exception e) {
                log.warn("Failed to backfill password for DbConnection id={}: {}",
                        conn.getId(), e.getMessage());
                skipped++;
            }
        }

        log.info("DbPassword backfill completed: {} backfilled, {} skipped, {} total",
                backfilled, skipped, connections.size());
    }
}
