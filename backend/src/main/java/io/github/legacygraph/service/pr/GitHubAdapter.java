package io.github.legacygraph.service.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.change.CreatePrRequest;
import io.github.legacygraph.dto.change.PrResult;
import io.github.legacygraph.dto.change.PrStatusInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

/**
 * GitHub 适配器实现（阶段三-3.2）。
 * <p>
 * 通过 GitHub REST API 实现 PR 创建、状态查询、添加 reviewer 等操作。
 * 分支创建、补丁应用、分支推送由 {@link AbstractGitProviderAdapter} 走 git CLI 完成。
 * </p>
 * <p>激活配置：{@code legacygraph.pr.github.enabled=true}</p>
 * <p>需配置：{@code legacygraph.pr.github.token} 与 {@code legacygraph.pr.github.api-url}</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "legacygraph.pr.github.enabled", havingValue = "true")
public class GitHubAdapter extends AbstractGitProviderAdapter {

    private final ObjectMapper objectMapper;

    @Value("${legacygraph.pr.github.token:}")
    private String token;

    @Value("${legacygraph.pr.github.api-url:https://api.github.com}")
    private String apiUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String getProviderId() {
        return "github";
    }

    @Override
    public PrResult createPullRequest(CreatePrRequest request) {
        try {
            String repoPath = extractRepoPath(request.getRepoUrl());
            String url = apiUrl + "/repos/" + repoPath + "/pulls";

            String body = objectMapper.writeValueAsString(new HashMap<String, Object>() {{
                put("title", request.getPrTitle());
                put("body", request.getPrBody());
                put("head", request.getSourceBranch());
                put("base", request.getTargetBranch());
            }});

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                JsonNode json = objectMapper.readTree(response.body());
                String prUrl = json.path("html_url").asText();
                String prNumber = json.path("number").asText();
                log.info("GitHub PR created: {}", prUrl);
                return PrResult.builder()
                        .success(true)
                        .prUrl(prUrl)
                        .prNumber(prNumber)
                        .branchName(request.getSourceBranch())
                        .build();
            } else {
                log.error("GitHub PR creation failed: {} - {}", response.statusCode(), response.body());
                return PrResult.builder()
                        .success(false)
                        .failureReason("GitHub API 返回 " + response.statusCode() + ": " + response.body())
                        .branchName(request.getSourceBranch())
                        .build();
            }
        } catch (Exception e) {
            log.error("GitHub PR creation error: {}", e.getMessage());
            return PrResult.builder()
                    .success(false)
                    .failureReason("GitHub PR 创建异常: " + e.getMessage())
                    .branchName(request.getSourceBranch())
                    .build();
        }
    }

    @Override
    public PrStatusInfo getPrStatus(String prUrl) {
        try {
            String[] parts = prUrl.split("/");
            if (parts.length < 7) {
                return PrStatusInfo.builder().prUrl(prUrl).status("UNKNOWN").build();
            }
            String repoPath = parts[3] + "/" + parts[4];
            String prNumber = parts[6];
            String url = apiUrl + "/repos/" + repoPath + "/pulls/" + prNumber;

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github.v3+json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String state = json.path("state").asText();
                boolean merged = json.path("merged").asBoolean();
                boolean mergeable = json.path("mergeable").asBoolean();

                String status = merged ? "MERGED" : ("closed".equals(state) ? "CLOSED" : "OPEN");

                return PrStatusInfo.builder()
                        .prUrl(prUrl)
                        .status(status)
                        .mergeable(mergeable)
                        .build();
            }
        } catch (Exception e) {
            log.warn("GitHub PR status query failed: {}", e.getMessage());
        }
        return PrStatusInfo.builder()
                .prUrl(prUrl)
                .status("UNKNOWN")
                .build();
    }

    @Override
    public void addReviewer(String prUrl, List<String> reviewers) {
        try {
            String[] parts = prUrl.split("/");
            if (parts.length < 7) {
                log.warn("GitHub addReviewer: invalid prUrl {}", prUrl);
                return;
            }
            String repoPath = parts[3] + "/" + parts[4];
            String prNumber = parts[6];
            String url = apiUrl + "/repos/" + repoPath + "/pulls/" + prNumber + "/requested_reviewers";

            String body = objectMapper.writeValueAsString(new HashMap<String, Object>() {{
                put("reviewers", reviewers);
            }});

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("GitHub reviewers added to {}: {}", prUrl, reviewers);
        } catch (Exception e) {
            log.warn("GitHub addReviewer failed: {}", e.getMessage());
        }
    }

    /** 从 GitHub URL 提取 owner/repo 路径 */
    private String extractRepoPath(String repoUrl) {
        String url = repoUrl.replace(".git", "");
        url = url.replace("https://github.com/", "");
        url = url.replace("http://github.com/", "");
        url = url.replace("git@github.com:", "");
        return url;
    }
}