package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.EvidenceConflict;
import io.github.legacygraph.service.graph.EvidenceConflictService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvidenceConflictControllerTest {

    @Mock
    private EvidenceConflictService service;

    private EvidenceConflictController controller;

    @BeforeEach
    void setUp() {
        controller = new EvidenceConflictController(service);
    }

    @Test
    void list_returnsConflictsFromService() {
        EvidenceConflict c1 = new EvidenceConflict();
        c1.setId("c1");
        EvidenceConflict c2 = new EvidenceConflict();
        c2.setId("c2");
        when(service.list("p1", false)).thenReturn(Arrays.asList(c1, c2));

        Result<List<EvidenceConflict>> result = controller.list("p1", false);

        assertEquals(0, result.getCode());
        assertEquals(2, result.getData().size());
        verify(service).list("p1", false);
    }

    @Test
    void list_withIncludeResolvedTrue() {
        when(service.list("p1", true)).thenReturn(List.of());

        Result<List<EvidenceConflict>> result = controller.list("p1", true);

        assertEquals(0, result.getCode());
        assertTrue(result.getData().isEmpty());
        verify(service).list("p1", true);
    }

    @Test
    void resolve_success() {
        EvidenceConflict resolved = new EvidenceConflict();
        resolved.setId("c1");
        resolved.setResolved(true);
        resolved.setResolution("ACCEPT_A");
        when(service.resolve("c1", "ACCEPT_A")).thenReturn(resolved);

        EvidenceConflictController.ResolveRequest request = new EvidenceConflictController.ResolveRequest();
        request.setResolution("ACCEPT_A");

        Result<EvidenceConflict> result = controller.resolve("c1", request);

        assertEquals(0, result.getCode());
        assertTrue(result.getData().getResolved());
        assertEquals("ACCEPT_A", result.getData().getResolution());
    }

    @Test
    void resolve_blankResolution_returnsBadRequest() {
        EvidenceConflictController.ResolveRequest request = new EvidenceConflictController.ResolveRequest();
        request.setResolution("   ");

        Result<EvidenceConflict> result = controller.resolve("c1", request);

        assertEquals(1, result.getCode());
        assertEquals("请选择处理方式", result.getMessage());
        verify(service, never()).resolve(any(), any());
    }

    @Test
    void resolve_nullResolution_returnsBadRequest() {
        EvidenceConflictController.ResolveRequest request = new EvidenceConflictController.ResolveRequest();
        request.setResolution(null);

        Result<EvidenceConflict> result = controller.resolve("c1", request);

        assertEquals(1, result.getCode());
    }

    @Test
    void resolve_serviceThrowsException_returnsBadRequest() {
        when(service.resolve("c1", "ACCEPT_A"))
                .thenThrow(new IllegalArgumentException("证据冲突不存在"));

        EvidenceConflictController.ResolveRequest request = new EvidenceConflictController.ResolveRequest();
        request.setResolution("ACCEPT_A");

        Result<EvidenceConflict> result = controller.resolve("c1", request);

        assertEquals(1, result.getCode());
        assertEquals("证据冲突不存在", result.getMessage());
    }
}
