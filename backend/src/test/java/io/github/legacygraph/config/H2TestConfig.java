package io.github.legacygraph.config;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.GraphQueryService;
import io.github.legacygraph.service.LlmProviderService;
import io.github.legacygraph.service.PromptTemplateService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H2 测试环境的 Mock Bean 配置。
 * 覆盖 LLM 相关基础设施，让 Controller 集成测试在无外部 API key 时也能加载 ApplicationContext。
 */
@Configuration
public class H2TestConfig {

    @Bean
    @Primary
    public LlmGateway llmGateway() {
        LlmGateway gateway = mock(LlmGateway.class);
        // 为 callWithTemplate 提供安全的默认返回值，避免 Controller 测试因 LLM 调用返回 null 导致 NPE
        when(gateway.callWithTemplate(anyString(), anyString(), any(), eq(FactExtractionResult.class)))
                .thenReturn(new FactExtractionResult());
        when(gateway.callWithTemplate(anyString(), anyString(), any(), eq(io.github.legacygraph.agent.DocUnderstandingAgent.BusinessFactExtraction.class)))
                .thenReturn(new io.github.legacygraph.agent.DocUnderstandingAgent.BusinessFactExtraction());
        return gateway;
    }

    @Bean
    @Primary
    public LlmProviderService llmProviderService() {
        return mock(LlmProviderService.class);
    }

    @Bean
    @Primary
    public PromptTemplateService promptTemplateService() {
        return mock(PromptTemplateService.class);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        float[] embedding = new float[1024];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = 0.001f * (i + 1);
        }
        when(mockModel.embed(anyString())).thenReturn(embedding);
        return mockModel;
    }

    @Bean
    @Primary
    public Neo4jGraphDao neo4jGraphDao() {
        Neo4jGraphDao dao = mock(Neo4jGraphDao.class);
        when(dao.findNodeById(anyString())).thenReturn(Optional.empty());
        when(dao.findNodesByIds(any())).thenReturn(List.of());
        when(dao.queryNodes(anyString(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(dao.queryNodes(anyString(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(dao.graphStats(anyString())).thenReturn(Map.of());
        when(dao.versionGraphStats(anyString(), anyString())).thenReturn(Map.of());
        return dao;
    }

    @Bean
    @Primary
    public GraphQueryService graphQueryService() {
        GraphQueryService service = mock(GraphQueryService.class);
        when(service.getApiCallChain(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(service.getTableImpact(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(service.getTablesNodes(anyString(), anyString())).thenReturn(List.of());
        when(service.getFeatureView(anyString(), anyString(), anyString())).thenReturn(Map.of("nodes", List.of(), "edges", List.of()));
        when(service.getBusinessView(anyString(), anyString(), anyString())).thenReturn(Map.of("nodes", List.of(), "edges", List.of()));
        when(service.getUnifiedGraph(anyString(), any(), any())).thenReturn(Map.of("nodes", List.of(), "edges", List.of()));
        when(service.getDriftQueue(anyString(), anyString())).thenReturn(Map.of(
                "items", List.of(),
                "summary", Map.of("staticOnly", 0L, "runtimeOnly", 0L, "docOnly", 0L, "lowConfidence", 0L)
        ));
        return service;
    }

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
