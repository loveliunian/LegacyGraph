package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.JavaStructureExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JavaServiceCallAdapterTest {

    @TempDir
    Path tempDir;

    @Mock
    private GraphBuilder graphBuilder;
    @Mock
    private FactPersister factPersister;
    @Mock
    private JavaStructureExtractor structureExtractor;

    @Test
    void extractBuildsJavaStructureEvenWhenClassHasNoServiceCalls() throws Exception {
        Path file = tempDir.resolve("OrderService.java");
        Files.writeString(file, """
                package com.demo;

                public class OrderService {
                    public void create() {
                    }
                }
                """);
        when(graphBuilder.buildJavaStructureGraph(any(), any(), any())).thenReturn(List.of());
        JavaStructureExtractor.JavaClassInfo classInfo = new JavaStructureExtractor.JavaClassInfo();
        classInfo.setQualifiedName("com.demo.OrderService");
        classInfo.setClassName("OrderService");
        classInfo.setPackageName("com.demo");
        classInfo.setMethods(List.of());
        when(structureExtractor.extractFromFile(any())).thenReturn(List.of(classInfo));

        JavaServiceCallAdapter adapter = new JavaServiceCallAdapter(graphBuilder, factPersister, structureExtractor);
        ScanContext context = ScanContext.builder()
                .projectId("project-1")
                .versionId("v1")
                .config(Map.of())
                .build();
        SourceAsset asset = SourceAsset.builder()
                .file(file)
                .relativePath("OrderService.java")
                .fileType("java")
                .language("java")
                .build();

        adapter.extract(context, asset);

        ArgumentCaptor<List<JavaStructureExtractor.JavaClassInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(graphBuilder).buildJavaStructureGraph(
                org.mockito.ArgumentMatchers.eq("project-1"),
                org.mockito.ArgumentMatchers.eq("v1"),
                captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("com.demo.OrderService", captor.getValue().get(0).getQualifiedName());
    }
}
