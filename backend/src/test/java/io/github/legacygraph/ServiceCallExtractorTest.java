package io.github.legacygraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import io.github.legacygraph.extractors.ServiceCallExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServiceCallExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void testExtractFromFile_SimpleController() throws IOException {
        // given: 一个简单的 Controller 文件
        String content = """
                package com.example.controller;
                                
                import com.example.service.UserService;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.RestController;
                                
                @RestController
                public class UserController {
                    @Autowired
                    private UserService userService;
                                
                    public String getUser(Long id) {
                        return userService.findById(id);
                    }
                }
                """;
        Path javaFile = tempDir.resolve("UserController.java");
        Files.writeString(javaFile, content);

        ServiceCallExtractor extractor = new ServiceCallExtractor();

        // when
        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(javaFile.toFile());

        // then
        assertNotNull(relations);
        assertFalse(relations.isEmpty(), "应抽取到调用关系");
        // 检查是否有 @Autowired 注入的 UserService
        boolean hasInjected = relations.stream()
                .anyMatch(r -> r.getCalledMethod() != null && r.getCalledMethod().contains("UserService"));
        assertTrue(hasInjected, "应发现 UserService 依赖注入");
    }

    @Test
    void testExtractFromFile_ConstructorInjection() throws IOException {
        String content = """
                package com.example.service;
                                
                import com.example.mapper.OrderMapper;
                import org.springframework.stereotype.Service;
                                
                @Service
                public class OrderService {
                    private final OrderMapper orderMapper;
                                
                    public OrderService(OrderMapper orderMapper) {
                        this.orderMapper = orderMapper;
                    }
                                
                    public void createOrder(Object dto) {
                        orderMapper.insert(dto);
                    }
                }
                """;
        Path javaFile = tempDir.resolve("OrderService.java");
        Files.writeString(javaFile, content);

        ServiceCallExtractor extractor = new ServiceCallExtractor();

        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(javaFile.toFile());

        // 验证通过构造器注入检测到了 OrderMapper
        assertNotNull(relations);
        boolean hasMapper = relations.stream()
                .anyMatch(r -> r.getCalledMethod() != null && r.getCalledMethod().contains("OrderMapper"));
        assertTrue(hasMapper, "构造器注入的 OrderMapper 应被检测到");
    }

    @Test
    void testExtractFromFile_MethodCalls() throws IOException {
        String content = """
                package com.example.controller;
                                
                import com.example.service.PaymentService;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.RestController;
                                
                @RestController
                public class PaymentController {
                    @Autowired
                    private PaymentService paymentService;
                                
                    public String pay(Long orderId) {
                        String result = paymentService.processPayment(orderId);
                        logResult(result);
                        return result;
                    }
                                
                    private void logResult(String msg) {
                        System.out.println(msg);
                    }
                }
                """;
        Path javaFile = tempDir.resolve("PaymentController.java");
        Files.writeString(javaFile, content);

        ServiceCallExtractor extractor = new ServiceCallExtractor();

        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(javaFile.toFile());

        // 验证方法调用被抽取
        boolean hasProcessPayment = relations.stream()
                .anyMatch(r -> "processPayment".equals(r.getCalledMethod()) || 
                               (r.getCalledMethod() != null && r.getCalledMethod().contains("processPayment")));
        boolean hasLogResult = relations.stream()
                .anyMatch(r -> "logResult".equals(r.getCalledMethod()));
        assertTrue(hasProcessPayment, "processPayment 调用应被检测到");
        assertTrue(hasLogResult, "logResult 调用应被检测到");
    }

    @Test
    void testExtractFromFile_NonJavaFile_ReturnsEmpty() throws IOException {
        Path nonJavaFile = tempDir.resolve("config.json");
        Files.writeString(nonJavaFile, "{}");

        ServiceCallExtractor extractor = new ServiceCallExtractor();

        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(nonJavaFile.toFile());

        assertNotNull(relations);
        assertTrue(relations.isEmpty(), "非 Java 文件应返回空列表");
    }

    @Test
    void testExtractFromFile_InjectAnnotation() throws IOException {
        String content = """
                package com.example.service;
                                
                import com.example.mapper.ProductMapper;
                import javax.inject.Inject;
                import org.springframework.stereotype.Service;
                                
                @Service
                public class ProductService {
                    @Inject
                    private ProductMapper productMapper;
                                
                    public Object getProduct(Long id) {
                        return productMapper.selectById(id);
                    }
                }
                """;
        Path javaFile = tempDir.resolve("ProductService.java");
        Files.writeString(javaFile, content);

        ServiceCallExtractor extractor = new ServiceCallExtractor();

        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(javaFile.toFile());

        assertNotNull(relations);
        boolean hasMapper = relations.stream()
                .anyMatch(r -> r.getCalledMethod() != null && r.getCalledMethod().contains("ProductMapper"));
        assertTrue(hasMapper, "@Inject 注入的 ProductMapper 应被检测到");
    }

    @Test
    void testCallRelation_AllArgsConstructor() {
        ServiceCallExtractor.CallRelation relation = new ServiceCallExtractor.CallRelation(
                "com.example.TestService", "handleRequest", "processData");
        relation.setTargetClass("com.example.DataProcessor");
        relation.setTargetMethod("process");
        relation.setSourcePath("/path/TestService.java");
        relation.setLineNumber(42);

        assertEquals("com.example.TestService", relation.getCallerClass());
        assertEquals("handleRequest", relation.getCallerMethod());
        assertEquals("processData", relation.getCalledMethod());
        assertEquals("com.example.DataProcessor", relation.getTargetClass());
        assertEquals("process", relation.getTargetMethod());
        assertEquals("/path/TestService.java", relation.getSourcePath());
        assertEquals(42, relation.getLineNumber());
    }

    @Test
    void testCallRelation_SimpleConstructor() {
        ServiceCallExtractor.CallRelation relation = new ServiceCallExtractor.CallRelation(
                "TestClass", null, "someMethod");

        assertEquals("TestClass", relation.getCallerClass());
        assertNull(relation.getCallerMethod());
        assertEquals("someMethod", relation.getCalledMethod());
        assertNull(relation.getTargetClass());
        assertNull(relation.getSourcePath());
        assertNull(relation.getLineNumber());
    }
}
