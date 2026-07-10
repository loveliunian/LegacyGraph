package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.JavaStructureExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
class JavaCodeAdapterTest {

    @TempDir
    Path tempDir;

    @Mock
    private GraphBuilder graphBuilder;

    @Test
    void extractBuildsJavaStructureForControllersEvenWhenNoApiMappingExists() throws Exception {
        Path file = tempDir.resolve("OrderController.java");
        Files.writeString(file, """
                package com.demo;

                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class OrderController {
                    public void helper() {
                    }
                }
                """);
        when(graphBuilder.buildJavaStructureGraph(any(), any(), any())).thenReturn(List.of());

        JavaCodeAdapter adapter = new JavaCodeAdapter(graphBuilder, new JavaStructureExtractor(), new io.github.legacygraph.extractors.PackageExtractor());
        ScanContext context = ScanContext.builder()
                .projectId("project-1")
                .versionId("v1")
                .config(Map.of())
                .build();
        SourceAsset asset = SourceAsset.builder()
                .file(file)
                .relativePath("OrderController.java")
                .fileType("java")
                .language("java")
                .build();

        adapter.extract(context, asset);

        ArgumentCaptor<List<JavaStructureExtractor.JavaClassInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(graphBuilder).buildJavaStructureGraph(
                org.mockito.ArgumentMatchers.eq("project-1"),
                org.mockito.ArgumentMatchers.eq("v1"),
                captor.capture());
        assertEquals("com.demo.OrderController", captor.getValue().get(0).getQualifiedName());
    }
}
