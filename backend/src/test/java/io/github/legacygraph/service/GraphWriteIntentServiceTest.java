package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.dto.graph.GraphWriteIntent;
import io.github.legacygraph.entity.GraphWriteIntentEntity;
import io.github.legacygraph.repository.GraphWriteIntentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphWriteIntentServiceTest {

    @Mock
    private GraphWriteIntentRepository repository;

    @Test
    void enqueueGeneratesDifferentIdempotencyKeysForDifferentClaimsWithSameCounts() {
        GraphWriteIntentService service = new GraphWriteIntentService(repository, new ObjectMapper().findAndRegisterModules());
        when(repository.selectList(any())).thenReturn(Collections.emptyList());

        service.enqueue(intentWithNode("node:a"));
        service.enqueue(intentWithNode("node:b"));

        ArgumentCaptor<GraphWriteIntentEntity> captor = ArgumentCaptor.forClass(GraphWriteIntentEntity.class);
        verify(repository, times(2)).insert(captor.capture());
        List<GraphWriteIntentEntity> inserted = captor.getAllValues();
        assertNotEquals(inserted.get(0).getIdempotencyKey(), inserted.get(1).getIdempotencyKey());
    }

    private GraphWriteIntent intentWithNode(String nodeKey) {
        return GraphWriteIntent.builder()
                .projectId("project-1")
                .versionId("v1")
                .source("COMPILER")
                .nodeClaims(List.of(GraphNodeClaim.builder()
                        .projectId("project-1")
                        .versionId("v1")
                        .nodeType("Feature")
                        .nodeKey(nodeKey)
                        .build()))
                .build();
    }
}
