package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.plugin.PluginRegistry;
import io.github.legacygraph.plugin.PluginRegistry.PluginDescriptor;
import io.github.legacygraph.plugin.PluginRegistry.PluginType;
import io.github.legacygraph.plugin.PluginStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PluginControllerTest {

    @Mock
    private PluginRegistry registry;

    @Mock
    private PluginStatusService statusService;

    private PluginController controller;

    @BeforeEach
    void setUp() {
        controller = new PluginController(registry, statusService);
    }

    @Test
    void list_noTypeFilter_returnsAll() {
        PluginDescriptor p1 = new PluginDescriptor("p1", "Scanner1", "desc", PluginType.SCANNER);
        PluginDescriptor p2 = new PluginDescriptor("p2", "Agent1", "desc", PluginType.AGENT);
        when(registry.listAll()).thenReturn(Arrays.asList(p1, p2));

        Result<List<PluginDescriptor>> result = controller.list(null);

        assertNotNull(result);
        assertEquals(0, result.getCode());
        assertEquals(2, result.getData().size());
        verify(registry).listAll();
        verify(registry, never()).listByType(any());
    }

    @Test
    void list_withTypeFilter_returnsFiltered() {
        PluginDescriptor p1 = new PluginDescriptor("p1", "Scanner1", "desc", PluginType.SCANNER);
        when(registry.listByType(PluginType.SCANNER)).thenReturn(List.of(p1));

        Result<List<PluginDescriptor>> result = controller.list(PluginType.SCANNER);

        assertNotNull(result);
        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("p1", result.getData().get(0).id());
        verify(registry).listByType(PluginType.SCANNER);
        verify(registry, never()).listAll();
    }

    @Test
    void list_emptyResult_returnsEmptyList() {
        when(registry.listByType(PluginType.TOOL)).thenReturn(List.of());

        Result<List<PluginDescriptor>> result = controller.list(PluginType.TOOL);

        assertNotNull(result);
        assertEquals(0, result.getCode());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    void get_returnsPluginDescriptor() {
        PluginDescriptor p = new PluginDescriptor("p1", "Scanner1", "Java Scanner", PluginType.SCANNER);
        when(registry.get("p1")).thenReturn(p);

        Result<PluginDescriptor> result = controller.get("p1");

        assertNotNull(result);
        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals("p1", result.getData().id());
        assertEquals("Scanner1", result.getData().name());
        assertEquals("Java Scanner", result.getData().description());
        verify(registry).get("p1");
    }

    @Test
    void get_nonExistent_returnsNull() {
        when(registry.get("nonexistent")).thenReturn(null);

        Result<PluginDescriptor> result = controller.get("nonexistent");

        assertNotNull(result);
        assertEquals(0, result.getCode());
        assertNull(result.getData());
    }

    @Test
    void list_allPluginTypes() {
        when(registry.listAll()).thenReturn(List.of(
                new PluginDescriptor("s1", "Scanner", "desc", PluginType.SCANNER),
                new PluginDescriptor("a1", "Agent", "desc", PluginType.AGENT),
                new PluginDescriptor("t1", "Tool", "desc", PluginType.TOOL),
                new PluginDescriptor("g1", "GraphView", "desc", PluginType.GRAPH_VIEW)
        ));

        Result<List<PluginDescriptor>> result = controller.list(null);

        assertNotNull(result);
        assertEquals(0, result.getCode());
        assertEquals(4, result.getData().size());
    }
}
