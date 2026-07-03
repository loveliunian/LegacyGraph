package io.github.legacygraph.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 可观测性配置（P3-2 / P3-3）
 * - Prometheus metrics：通过 actuator/prometheus 暴露
 * - 自定义业务指标：scan 耗时、agent 调用次数、图谱节点/边增量
 * - OpenTelemetry：通过 spring-actuator 自动 trace HTTP 请求
 */
@Configuration
public class ObservabilityConfig {

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /**
     * 扫描耗时计时器
     */
    @Bean
    public Timer scanDurationTimer(MeterRegistry registry) {
        Timer timer = Timer.builder("legacygraph.scan.duration")
                .description("图谱扫描耗时")
                .tag("type", "full_scan")
                .register(registry);
        timers.put("scan", timer);
        return timer;
    }

    /**
     * Agent 调用计数器
     */
    @Bean
    public Counter agentCallCounter(MeterRegistry registry) {
        Counter counter = Counter.builder("legacygraph.agent.calls")
                .description("Agent 调用总次数")
                .tag("type", "all")
                .register(registry);
        counters.put("agent_calls", counter);
        return counter;
    }

    /**
     * 图谱节点增量计数器
     */
    @Bean
    public Counter graphNodeCounter(MeterRegistry registry) {
        return Counter.builder("legacygraph.graph.nodes")
                .description("图谱节点创建总数")
                .register(registry);
    }

    /**
     * 图谱边增量计数器
     */
    @Bean
    public Counter graphEdgeCounter(MeterRegistry registry) {
        return Counter.builder("legacygraph.graph.edges")
                .description("图谱边创建总数")
                .register(registry);
    }
}
