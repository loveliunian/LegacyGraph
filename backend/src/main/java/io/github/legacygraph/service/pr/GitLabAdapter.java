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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

/**
 * GitLab 适配器实现（阶段三-3.2）。
 * <p>
 * 通过 GitLab REST API 实现 MR 创建、状态查询、添加 reviewer。
 * 分支创建、补丁应用、分支推送由 {@link AbstractGitProviderAdapter} 走 git CLI 完成。
 * </p>
 * <p>激活配置：{@code legacygraph.pr.gitlab.enabled=true}</p>
 * <p>需配置：{@code legacygraph.pr.gitlab.token} 与 {@code legacygraph.pr.gitlab.api-url}</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "legacygraph.pr.gitlab.enabled", havingValue = "true")
public class GitLabAdapter extends AbstractGitProviderAdapter {

    private final ObjectMapper objectMapper;

    @Value("${legacygraph.pr.gitlab.token:}")
    private String token;

    @Value("${legacygraph.pr.gitlab.api-url:https://gitlab.com/api/v4}")
    private String apiUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String getProviderId() {
        return "gitlab";
    }

    @Override
    public PrResult createPullRequest(CreatePrRequest request) {
        try {
            String projectId = URLEncoder.encode(extractProjectPath(request.getRepoUrl()),
                    StandardCharsets.UTF_8);
            String url = apiUrl + "/projects/" + projectId + "/merge_requests";

            String body = objectMapper.writeValueAsString(new HashMap<String, Object>() {{
                put("title", request.getPrTitle());
                put("description", request.getPrBody());
                put("source_branch", request.getSourceBranch());
                put("target_branch", request.getTargetBranch());
            }});

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                JsonNode json = objectMapper.readTree(response.body());
                String mrUrl = json.path("web_url").asText();
                String mrNumber = json.path("iid").asText();
                log.info("GitLab MR created: {}", mrUrl);
                return PrResult.builder()
                        .success(true)
                        .prUrl(mrUrl)
                        .prNumber(mrNumber)
                        .branchName(request.getSourceBranch())
                        .build();
            } else {
                log.error("GitLab MR creation failed: {} - {}", response.statusCode(), response.body());
                return PrResult.builder()
                        .success(false)
                        .failureReason("GitLab API 返回 " + response.statusCode() + ": " + response.body())
                        .branchName(request.getSourceBranch())
                        .build();
            }
        } catch (Exception e) {
            log.error("GitLab MR creation error: {}", e.getMessage());
            return PrResult.builder()
                    .success(false)
                    .failureReason("GitLab MR 创建异常: " + e.getMessage())
                    .branchName(request.getSourceBranch())
                    .build();
        }
    }

    @Override
    public PrStatusInfo getPrStatus(String prUrl) {
        try {
            String projectPath = extractProjectPathFromMrUrl(prUrl);
            String iid = extractMrIid(prUrl);
            String projectId = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
            String url = apiUrl + "/projects/" + projectId + "/merge_requests/" + iid;

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String state = json.path("state").asText();
                boolean mergeable = json.path("detailed_merge_status").asText().equals("mergeable")
                        || json.path("merge_status").asText().equals("can_be_merged");

                String status = "merged".equals(state) ? "MERGED"
                        : ("closed".equals(state) ? "CLOSED" : "OPEN");

                return PrStatusInfo.builder()
                        .prUrl(prUrl)
                        .status(status)
                        .mergeable(mergeable)
                        .build();
            }
        } catch (Exception e) {
            log.warn("GitLab MR status query failed: {}", e.getMessage());
        }
        return PrStatusInfo.builder()
                .prUrl(prUrl)
                .status("UNKNOWN")
                .build();
    }

    @Override
    public void addReviewer(String prUrl, List<String> reviewers) {
        try {
            String projectPath = extractProjectPathFromMrUrl(prUrl);
            String iid = extractMrIid(prUrl);
            String projectId = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
            String url = apiUrl + "/projects/" + projectId + "/merge_requests/" + iid;

            String body = objectMapper.writeValueAsString(new HashMap<String, Object>() {{
                put("reviewer_ids", reviewers);
            }});

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("GitLab reviewers added to {}: {}", prUrl, reviewers);
        } catch (Exception e) {
            log.warn("GitLab addReviewer failed: {}", e.getMessage());
        }
    }

    /** 从 GitLab URL 提取项目路径 */
    private String extractProjectPath(String repoUrl) {
        String url = repoUrl.replace(".git", "");
        url = url.replace("https://gitlab.com/", "");
        url = url.replace("http://gitlab.com/", "");
        url = url.replace("git@gitlab.com:", "");
        return url;
    }

    /** 从 MR URL 提取项目路径 */
    private String extractProjectPathFromMrUrl(String mrUrl) {
        String url = mrUrl.replace("https://gitlab.com/", "");
        url = url.replace("http://gitlab.com/", "");
        int idx = url.indexOf("/-/merge_requests/");
        if (idx > 0) {
            return url.substring(0, idx);
        }
        return url;
    }

    /** 从 MR URL 提取 IID */
    private String extractMrIid(String mrUrl) {
        int idx = mrUrl.lastIndexOf("/");
        if (idx > 0) {
            return mrUrl.substring(idx + 1);
        }
        return "";
    }
}