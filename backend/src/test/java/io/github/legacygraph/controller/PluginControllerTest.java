package io.github.legacygraph.controller;

import io.github.legacygraph.plugin.PluginRegistry;
import io.github.legacygraph.plugin.PluginRegistry.PluginDescriptor;
import io.github.legacygraph.plugin.PluginRegistry.PluginType;
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

    private PluginController controller;

    @BeforeEach
    void setUp() {
        controller = new PluginController(registry);
    }

    @Test
    void list_noTypeFilter_returnsAll() {
        PluginDescriptor p1 = new PluginDescriptor("p1", "Scanner1", "desc", PluginType.SCANNER);
        PluginDescriptor p2 = new PluginDescriptor("p2", "Agent1", "desc", PluginType.AGENT);
        when(registry.listAll()).thenReturn(Arrays.asList(p1, p2));

        List<PluginDescriptor> result = controller.list(null);

        assertEquals(2, result.size());
        verify(registry).listAll();
        verify(registry, never()).listByType(any());
    }

    @Test
    void list_withTypeFilter_returnsFiltered() {
        PluginDescriptor p1 = new PluginDescriptor("p1", "Scanner1", "desc", PluginType.SCANNER);
        when(registry.listByType(PluginType.SCANNER)).thenReturn(List.of(p1));

        List<PluginDescriptor> result = controller.list(PluginType.SCANNER);

        assertEquals(1, result.size());
        assertEquals("p1", result.get(0).id());
        verify(registry).listByType(PluginType.SCANNER);
        verify(registry, never()).listAll();
    }

    @Test
    void list_emptyResult_returnsEmptyList() {
        when(registry.listByType(PluginType.TOOL)).thenReturn(List.of());

        List<PluginDescriptor> result = controller.list(PluginType.TOOL);

        assertTrue(result.isEmpty());
    }

    @Test
    void get_returnsPluginDescriptor() {
        PluginDescriptor p = new PluginDescriptor("p1", "Scanner1", "Java Scanner", PluginType.SCANNER);
        when(registry.get("p1")).thenReturn(p);

        PluginDescriptor result = controller.get("p1");

        assertNotNull(result);
        assertEquals("p1", result.id());
        assertEquals("Scanner1", result.name());
        assertEquals("Java Scanner", result.description());
        verify(registry).get("p1");
    }

    @Test
    void get_nonExistent_returnsNull() {
        when(registry.get("nonexistent")).thenReturn(null);

        PluginDescriptor result = controller.get("nonexistent");

        assertNull(result);
    }

    @Test
    void list_allPluginTypes() {
        when(registry.listAll()).thenReturn(List.of(
                new PluginDescriptor("s1", "Scanner", "desc", PluginType.SCANNER),
                new PluginDescriptor("a1", "Agent", "desc", PluginType.AGENT),
                new PluginDescriptor("t1", "Tool", "desc", PluginType.TOOL),
                new PluginDescriptor("g1", "GraphView", "desc", PluginType.GRAPH_VIEW)
        ));

        List<PluginDescriptor> result = controller.list(null);

        assertEquals(4, result.size());
    }
}
