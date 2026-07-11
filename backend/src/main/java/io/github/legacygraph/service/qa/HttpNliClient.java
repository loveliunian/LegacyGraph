package io.github.legacygraph.service.qa;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP NLI 客户端实现 — 调用内网 HHEM-2.1-Open NLI 服务。
 * <p>
 * 仅在 {@code legacygraph.qa.evidence-verifier.nli.enabled=true} 时注入。
 * 服务不可用时 {@link #score} 返回 -1，由 {@link EvidenceVerifier} 降级到正则匹配。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "legacygraph.qa.evidence-verifier.nli.enabled", havingValue = "true")
public class HttpNliClient implements NliClient {

    private final RestTemplate restTemplate;
    private final String endpoint;
    private final int timeoutMs;
    private final double minScore;

    public HttpNliClient(
            @Value("${legacygraph.qa.evidence-verifier.nli.endpoint:http://internal-hhem:8080/score}") String endpoint,
            @Value("${legacygraph.qa.evidence-verifier.nli.timeout-ms:800}") int timeoutMs,
            @Value("${legacygraph.qa.evidence-verifier.nli.min-score:0.55}") double minScore) {
        this.endpoint = endpoint;
        this.timeoutMs = timeoutMs;
        this.minScore = minScore;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public double score(String premise, String hypothesis) {
        if (premise == null || premise.isBlank() || hypothesis == null || hypothesis.isBlank()) {
            return 0.0;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = Map.of("premise", premise, "hypothesis", hypothesis);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(endpoint, request, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object score = response.getBody().get("score");
                if (score instanceof Number n) {
                    return n.doubleValue();
                }
            }
            log.warn("NLI response missing score field: {}", response.getBody());
            return -1;
        } catch (Exception e) {
            log.warn("NLI score failed (premise={}, hypothesis={}, endpoint={}): {}",
                    premise, hypothesis, endpoint, e.getMessage());
            return -1;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Map<String, String> body = Map.of("premise", "test", "hypothesis", "test");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(endpoint, request, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("NLI health check failed: {}", e.getMessage());
            return false;
        }
    }

    /** NLI 最小支撑分数阈值 */
    public double getMinScore() {
        return minScore;
    }

    /** NLI 超时（ms） */
    public int getTimeoutMs() {
        return timeoutMs;
    }
}
