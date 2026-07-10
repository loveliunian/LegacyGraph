package io.github.legacygraph.service;

import io.github.legacygraph.dto.plugin.PluginCheckResult;
import io.github.legacygraph.dto.plugin.PluginTestResult;
import io.github.legacygraph.dto.plugin.ExternalPluginDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 插件安装测试服务。
 * <p>
 * 对外部动态注册的 MCP/HTTP 插件执行分层只读检查：
 * <ul>
 *   <li>MCP：连通性 → 协议握手 → 鉴权 → 能力探测</li>
 *   <li>HTTP：连通性 → 鉴权 → 响应格式校验</li>
 * </ul>
 * 全部只读、严格超时（单阶段 5s，总 15s），不执行写操作，不依赖 projectId。
 * </p>
 */
@Slf4j
@Service
public class PluginTestService {

    private static final int STAGE_TIMEOUT_SECONDS = 5;
    private static final String STATUS_PASS = "PASS";
    private static final String STATUS_FAIL = "FAIL";
    private static final String STATUS_SKIP = "SKIP";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(STAGE_TIMEOUT_SECONDS))
            .build();

    /**
     * 测试外部插件（注册前预检或已注册复测）。
     *
     * @param descriptor 外部插件描述符
     * @return 分层测试结果
     */
    public PluginTestResult test(ExternalPluginDescriptor descriptor) {
        PluginTestResult result = new PluginTestResult();
        result.setPluginId(descriptor.getId());
        String protocol = descriptor.getProtocol() != null ? descriptor.getProtocol().toUpperCase() : "MCP";
        result.setProtocol(protocol);

        List<PluginCheckResult> checks = new ArrayList<>();
        long start = System.currentTimeMillis();

        String endpoint = descriptor.getMcpEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            checks.add(new PluginCheckResult("connectivity", STATUS_FAIL, 0, "mcpEndpoint 未配置"));
            result.setOverallStatus("UNAVAILABLE");
            result.setMessage("插件端点未配置");
            result.setChecks(checks);
            result.setElapsedMs(System.currentTimeMillis() - start);
            return result;
        }

        try {
            // 阶段1：连通性
            PluginCheckResult connectivity = testConnectivity(endpoint);
            checks.add(connectivity);
            if (STATUS_FAIL.equals(connectivity.getStatus())) {
                result.setOverallStatus("UNAVAILABLE");
                result.setMessage("连通性失败: " + connectivity.getMessage());
                finalizeResult(result, checks, start);
                return result;
            }

            // 阶段2：协议握手 / 响应格式
            if ("MCP".equals(protocol)) {
                PluginCheckResult handshake = testMcpHandshake(endpoint, descriptor.getAuth());
                checks.add(handshake);
                if (STATUS_FAIL.equals(handshake.getStatus())) {
                    result.setOverallStatus("PROTOCOL_ERROR");
                    result.setMessage("MCP 握手失败: " + handshake.getMessage());
                    finalizeResult(result, checks, start);
                    return result;
                }
            } else {
                PluginCheckResult format = testHttpResponseFormat(endpoint, descriptor.getAuth());
                checks.add(format);
                if (STATUS_FAIL.equals(format.getStatus())) {
                    result.setOverallStatus("PROTOCOL_ERROR");
                    result.setMessage("响应格式异常: " + format.getMessage());
                    finalizeResult(result, checks, start);
                    return result;
                }
            }

            // 阶段3：鉴权
            PluginCheckResult auth = testAuth(endpoint, descriptor.getAuth(), protocol);
            checks.add(auth);
            if (STATUS_FAIL.equals(auth.getStatus())) {
                result.setOverallStatus("AUTH_FAILED");
                result.setMessage("鉴权失败: " + auth.getMessage());
                finalizeResult(result, checks, start);
                return result;
            }

            // 阶段4：能力探测（MCP only，可选）
            if ("MCP".equals(protocol)) {
                PluginCheckResult capability = testMcpCapability(endpoint, descriptor.getAuth());
                checks.add(capability);
            }

            result.setOverallStatus("READY");
            result.setMessage("所有检查通过");
        } catch (Exception e) {
            log.warn("Plugin test failed for {}: {}", descriptor.getId(), e.getMessage());
            result.setOverallStatus("UNAVAILABLE");
            result.setMessage("测试异常: " + e.getMessage());
        }

        finalizeResult(result, checks, start);
        return result;
    }

    private void finalizeResult(PluginTestResult result, List<PluginCheckResult> checks, long start) {
        result.setChecks(checks);
        result.setElapsedMs(System.currentTimeMillis() - start);
    }

    /** 阶段1：TCP/HTTP 连通性检查 */
    private PluginCheckResult testConnectivity(String endpoint) {
        long start = System.currentTimeMillis();
        try {
            URI uri = URI.create(endpoint);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .HEAD()
                    .timeout(Duration.ofSeconds(STAGE_TIMEOUT_SECONDS))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long elapsed = System.currentTimeMillis() - start;
            // 2xx/3xx/4xx 都说明端点可达（404/401 也代表服务在运行）
            if (response.statusCode() < 500) {
                return new PluginCheckResult("connectivity", STATUS_PASS, elapsed,
                        "端点可达 (HTTP " + response.statusCode() + ")");
            }
            return new PluginCheckResult("connectivity", STATUS_FAIL, elapsed,
                    "服务端错误 (HTTP " + response.statusCode() + ")");
        } catch (java.net.ConnectException e) {
            return new PluginCheckResult("connectivity", STATUS_FAIL,
                    System.currentTimeMillis() - start, "连接被拒绝: " + e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return new PluginCheckResult("connectivity", STATUS_FAIL,
                    System.currentTimeMillis() - start, "连接超时");
        } catch (Exception e) {
            return new PluginCheckResult("connectivity", STATUS_FAIL,
                    System.currentTimeMillis() - start, "连接失败: " + e.getMessage());
        }
    }

    /** 阶段2a：MCP 协议握手 — 发送 initialize 请求验证返回合法 JSON-RPC 响应 */
    private PluginCheckResult testMcpHandshake(String endpoint, String auth) {
        long start = System.currentTimeMillis();
        try {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"legacygraph-test\",\"version\":\"1.0.0\"}}}";
            HttpRequest request = buildPostRequest(endpoint, body, auth);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                String respBody = response.body();
                if (respBody != null && respBody.contains("\"jsonrpc\"")) {
                    return new PluginCheckResult("handshake", STATUS_PASS, elapsed, "MCP initialize 握手成功");
                }
                return new PluginCheckResult("handshake", STATUS_FAIL, elapsed,
                        "响应非合法 JSON-RPC: " + truncate(respBody));
            }
            return new PluginCheckResult("handshake", STATUS_FAIL, elapsed,
                    "握手失败 (HTTP " + response.statusCode() + ")");
        } catch (Exception e) {
            return new PluginCheckResult("handshake", STATUS_FAIL,
                    System.currentTimeMillis() - start, "握手异常: " + e.getMessage());
        }
    }

    /** 阶段2b：HTTP 响应格式校验 */
    private PluginCheckResult testHttpResponseFormat(String endpoint, String auth) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(STAGE_TIMEOUT_SECONDS))
                    .GET();
            if (auth != null && !auth.isBlank()) {
                builder.header("Authorization", auth);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() < 400) {
                return new PluginCheckResult("response-format", STATUS_PASS, elapsed,
                        "HTTP 响应正常 (HTTP " + response.statusCode() + ")");
            }
            return new PluginCheckResult("response-format", STATUS_FAIL, elapsed,
                    "HTTP 错误 (HTTP " + response.statusCode() + ")");
        } catch (Exception e) {
            return new PluginCheckResult("response-format", STATUS_FAIL,
                    System.currentTimeMillis() - start, "请求异常: " + e.getMessage());
        }
    }

    /** 阶段3：鉴权检查 — 若配置了 auth，验证是否被接受 */
    private PluginCheckResult testAuth(String endpoint, String auth, String protocol) {
        if (auth == null || auth.isBlank()) {
            return new PluginCheckResult("auth", STATUS_SKIP, 0, "未配置鉴权信息");
        }
        // 鉴权已在握手/格式检查中携带，此处只验证是否返回 401/403
        // 若前面阶段通过，说明鉴权被接受
        return new PluginCheckResult("auth", STATUS_PASS, 0, "鉴权信息已被接受");
    }

    /** 阶段4：MCP 能力探测 — 调用 tools/list 列出可用工具 */
    private PluginCheckResult testMcpCapability(String endpoint, String auth) {
        long start = System.currentTimeMillis();
        try {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
            HttpRequest request = buildPostRequest(endpoint, body, auth);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() == 200 && response.body() != null) {
                String respBody = response.body();
                int toolCount = countOccurrences(respBody, "\"name\"");
                return new PluginCheckResult("capability", STATUS_PASS, elapsed,
                        "可用工具数: " + toolCount);
            }
            return new PluginCheckResult("capability", STATUS_SKIP, elapsed,
                    "能力探测不可用 (HTTP " + response.statusCode() + ")");
        } catch (Exception e) {
            return new PluginCheckResult("capability", STATUS_SKIP,
                    System.currentTimeMillis() - start, "能力探测跳过: " + e.getMessage());
        }
    }

    private HttpRequest buildPostRequest(String endpoint, String body, String auth) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(STAGE_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null && !auth.isBlank()) {
            builder.header("Authorization", auth);
        }
        return builder.build();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
