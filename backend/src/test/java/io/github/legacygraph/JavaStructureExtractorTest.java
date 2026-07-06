package io.github.legacygraph;

import io.github.legacygraph.extractors.JavaStructureExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaStructureExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractFromFile_returnsPlainClassAndMethodStructure() throws Exception {
        Path file = tempDir.resolve("OrderService.java");
        Files.writeString(file, """
                package com.demo;

                public class OrderService {
                    public void create() {
                    }

                    private String cancel(String orderId) {
                        return orderId;
                    }
                }
                """);

        JavaStructureExtractor extractor = new JavaStructureExtractor();

        var classes = extractor.extractFromFile(file);

        assertEquals(1, classes.size());
        var classInfo = classes.get(0);
        assertEquals("OrderService", classInfo.getClassName());
        assertEquals("com.demo", classInfo.getPackageName());
        assertEquals("com.demo.OrderService", classInfo.getQualifiedName());
        assertEquals(2, classInfo.getMethods().size());
        assertEquals("com.demo.OrderService.create()", classInfo.getMethods().get(0).getQualifiedName());
        assertEquals("com.demo.OrderService.cancel(String)", classInfo.getMethods().get(1).getQualifiedName());
    }
}
