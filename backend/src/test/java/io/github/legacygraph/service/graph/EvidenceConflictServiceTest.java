package io.github.legacygraph.service.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.EvidenceConflict;
import io.github.legacygraph.repository.EvidenceConflictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvidenceConflictServiceTest {

    @Mock
    private EvidenceConflictRepository repository;

    private EvidenceConflictService service;

    @BeforeEach
    void setUp() {
        service = new EvidenceConflictService(repository);
    }

    @Test
    void list_excludeResolved_queriesWithResolvedFalse() {
        // given
        EvidenceConflict c1 = new EvidenceConflict();
        c1.setId("c1");
        c1.setProjectId("p1");
        c1.setResolved(false);
        when(repository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(c1));

        // when
        List<EvidenceConflict> result = service.list("p1", false);

        // then
        assertEquals(1, result.size());
        assertEquals("c1", result.get(0).getId());
        verify(repository).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void list_includeResolved_returnsAll() {
        // given
        EvidenceConflict c1 = new EvidenceConflict();
        c1.setId("c1");
        EvidenceConflict c2 = new EvidenceConflict();
        c2.setId("c2");
        when(repository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(c1, c2));

        // when
        List<EvidenceConflict> result = service.list("p1", true);

        // then
        assertEquals(2, result.size());
        verify(repository).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void list_emptyResult_returnsEmptyList() {
        when(repository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        List<EvidenceConflict> result = service.list("p1", false);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_existingConflict_marksResolvedAndUpdates() {
        // given
        EvidenceConflict conflict = new EvidenceConflict();
        conflict.setId("c1");
        conflict.setResolved(false);
        when(repository.selectById("c1")).thenReturn(conflict);
        when(repository.updateById(any(EvidenceConflict.class))).thenReturn(1);

        // when
        EvidenceConflict result = service.resolve("c1", "ACCEPT_SOURCE_A");

        // then
        assertNotNull(result);
        assertTrue(result.getResolved());
        assertEquals("ACCEPT_SOURCE_A", result.getResolution());
        assertNotNull(result.getResolvedAt());

        ArgumentCaptor<EvidenceConflict> captor = ArgumentCaptor.forClass(EvidenceConflict.class);
        verify(repository).updateById(captor.capture());
        assertEquals("c1", captor.getValue().getId());
    }

    @Test
    void resolve_nonExistentConflict_throwsException() {
        when(repository.selectById("nonexistent")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.resolve("nonexistent", "ACCEPT_SOURCE_A"));
    }

    @Test
    void resolve_preservesOriginalFields() {
        EvidenceConflict conflict = new EvidenceConflict();
        conflict.setId("c1");
        conflict.setProjectId("p1");
        conflict.setTitle("节点类型冲突");
        conflict.setSeverity("HIGH");
        conflict.setResolved(false);
        when(repository.selectById("c1")).thenReturn(conflict);
        when(repository.updateById(any(EvidenceConflict.class))).thenReturn(1);

        EvidenceConflict result = service.resolve("c1", "MANUAL_OVERRIDE");

        assertEquals("p1", result.getProjectId());
        assertEquals("节点类型冲突", result.getTitle());
        assertEquals("HIGH", result.getSeverity());
        assertEquals("MANUAL_OVERRIDE", result.getResolution());
    }
}
