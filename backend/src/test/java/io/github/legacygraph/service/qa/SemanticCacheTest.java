package io.github.legacygraph.service.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.repository.SemanticCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticCacheTest {

    @Mock
    private SemanticCacheRepository cacheRepository;

    @Mock
    private VectorRetrievalService vectorService;

    private SemanticCache semanticCache;

    @BeforeEach
    void setUp() {
        semanticCache = new SemanticCache(cacheRepository, vectorService);
    }

    @Test
    void evictExpired_RepositoryFailureDoesNotEscapeScheduledTask() {
        when(cacheRepository.delete(any(LambdaQueryWrapper.class)))
                .thenThrow(new RuntimeException("missing semantic cache table"));

        assertDoesNotThrow(() -> semanticCache.evictExpired());
    }
}
