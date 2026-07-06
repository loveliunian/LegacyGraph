package io.github.legacygraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * 图谱写入配置 — 从 application.yml 读取 {@code legacygraph.graph} 配置。
 * <p>
 * 落地 {@code doc/系统关系总览/04-落地实施计划.md} 阶段3：双轨切换。
 * </p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "legacygraph.graph")
public class GraphWriteConfig {

    /**
     * 写图模式：
     * <ul>
     *   <li>{@code direct} — 现状：Adapter 直接写图（默认，兼容）</li>
     *   <li>{@code shadow} — 直接写图 + Claim 编译 dry-run 对比（安全过渡）</li>
     *   <li>{@code claim-compiler} — Claim 编译实际写图，Adapter 只产 Claim Draft</li>
     * </ul>
     */
    private String writeMode = "direct";

    /** Claim 编译最小置信度阈值 */
    private BigDecimal compilerMinConfidence = new BigDecimal("0.85");

    /** Claim 编译是否包含 PENDING_CONFIRM */
    private boolean compilerIncludePending = false;

    public boolean isClaimCompilerMode() {
        return "claim-compiler".equalsIgnoreCase(writeMode);
    }

    public boolean isShadowMode() {
        return "shadow".equalsIgnoreCase(writeMode);
    }
}
