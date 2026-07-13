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

    @Test
    void testExtractFromFile_LombokRequiredArgsConstructor() throws IOException {
        // Lombok @RequiredArgsConstructor：final 字段 + 无显式构造器 + 无 @Autowired
        // 源码里没有构造器（编译期由 Lombok 生成），原 collectInjectedVarTypes 漏掉 final 字段
        // → orderMapper 不被识别为注入 → targetClass=null → Service→Mapper 调用链断裂。
        String content = """
                package com.example.service;

                import com.example.mapper.OrderMapper;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;

                @Service
                @RequiredArgsConstructor
                public class OrderService {
                    private final OrderMapper orderMapper;

                    public void createOrder(Object dto) {
                        orderMapper.insert(dto);
                    }
                }
                """;
        Path javaFile = tempDir.resolve("OrderService.java");
        Files.writeString(javaFile, content);

        ServiceCallExtractor extractor = new ServiceCallExtractor();
        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(javaFile.toFile());

        // final 字段 orderMapper 应被识别为构造器注入，insert 调用应解析出 targetClass=OrderMapper
        boolean resolved = relations.stream()
                .anyMatch(r -> "OrderMapper".equals(r.getTargetClass())
                        && "insert".equals(r.getTargetMethod()));
        assertTrue(resolved, "Lombok final 字段 orderMapper 应解析为 OrderMapper（修复前 targetClass=null）");
    }

    @Test
    void testExtractFromFile_ThisPrefixedScope() throws IOException {
        // this.userMapper.findById() —— scope.toString()="this.userMapper"，原逻辑匹配不上 "userMapper"
        String content = """
                package com.example.service;

                import com.example.mapper.UserMapper;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;

                @Service
                @RequiredArgsConstructor
                public class UserService {
                    private final UserMapper userMapper;

                    public Object findUser(Long id) {
                        return this.userMapper.findById(id);
                    }
                }
                """;
        Path javaFile = tempDir.resolve("UserService.java");
        Files.writeString(javaFile, content);

        ServiceCallExtractor extractor = new ServiceCallExtractor();
        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(javaFile.toFile());

        boolean resolved = relations.stream()
                .anyMatch(r -> "UserMapper".equals(r.getTargetClass())
                        && "findById".equals(r.getTargetMethod()));
        assertTrue(resolved, "this.userMapper.findById() 应解析出 targetClass=UserMapper（修复前 this. 前缀导致 miss）");
    }

    // ============ S2-T1-PATCH: Lambda 表达式抽取测试 ============

    @Test
    void testExtractFromFile_LambdaBodyMethodCall() throws IOException {
        // 场景：lambda 表达式体内的方法调用 — () -> userMapper.findById(id)
        // 验证：抽取器会为 lambda 体内的方法调用打 LAMBDA_CALL 标记，且 targetClass 正确解析
        String content = """
                package com.example.service;

                import com.example.mapper.UserMapper;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import java.util.function.Supplier;

                @Service
                @RequiredArgsConstructor
                public class UserService {
                    private final UserMapper userMapper;

                    public Object findUserDeferred(Long id) {
                        Supplier<Object> supplier = () -> userMapper.findById(id);
                        return supplier.get();
                    }
                }
                """;
        Path javaFile = tempDir.resolve("UserService.java");
        Files.writeString(javaFile, content);

        ServiceCallExtractor extractor = new ServiceCallExtractor();
        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(javaFile.toFile());

        // lambda 体内对 Mapper 的调用标记为 DATABASE_CALL（P0-3 对齐：lambda 体里对注入 Mapper 字段的调用标记 DATABASE_CALL）
        boolean lambdaEdgeFound = relations.stream()
                .anyMatch(r -> "DATABASE_CALL".equals(r.getEdgeTargetKind())
                        && "UserMapper".equals(r.getTargetClass())
                        && "findById".equals(r.getTargetMethod()));
        assertTrue(lambdaEdgeFound,
                "lambda 体内 userMapper.findById 应标记为 DATABASE_CALL 并解析 targetClass=UserMapper");
    }

    @Test
    void testExtractFromFile_LambdaWithExplicitParam() throws IOException {
        // 场景：lambda 带显式类型参数 (User u) -> mapper.selectByName(u.getName())
        // 验证：lambda 参数的显式类型不影响 methodVarToType，但 lambda 体内的方法调用仍能解析
        String content = """
                package com.example.service;

                import com.example.mapper.UserMapper;
                import com.example.model.User;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import java.util.function.Function;

                @Service
                @RequiredArgsConstructor
                public class UserService {
                    private final UserMapper userMapper;

                    public String extractName(User input) {
                        Function<User, String> fn = (User u) -> userMapper.selectByName(u.getName());
                        return fn.apply(input);
                    }
                }
                """;
        Path javaFile = tempDir.resolve("UserService.java");
        Files.writeString(javaFile, content);

        ServiceCallExtractor extractor = new ServiceCallExtractor();
        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(javaFile.toFile());

        // 找到 DATABASE_CALL 边（lambda 体内对 Mapper 的调用标记为 DATABASE_CALL）
        boolean lambdaEdge = relations.stream()
                .anyMatch(r -> "DATABASE_CALL".equals(r.getEdgeTargetKind())
                        && "UserMapper".equals(r.getTargetClass())
                        && "selectByName".equals(r.getTargetMethod()));
        assertTrue(lambdaEdge, "显式类型参数 lambda 体内的 userMapper.selectByName 应被标记为 DATABASE_CALL");
    }

    @Test
    void testExtractFromFile_LambdaCapturesOuterVariable() throws IOException {
        // 场景：lambda 捕获外层方法的局部变量 — mapper = userMapper; () -> mapper.findById(id)
        // 验证：lambda 闭包变量追踪生效，外层局部变量类型解析到 UserMapper
        String content = """
                package com.example.service;

                import com.example.mapper.UserMapper;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import java.util.function.Supplier;

                @Service
                @RequiredArgsConstructor
                public class UserService {
                    private final UserMapper userMapper;

                    public Object findUserViaLocal(Long id) {
                        UserMapper mapper = userMapper;
                        Supplier<Object> supplier = () -> mapper.findById(id);
                        return supplier.get();
                    }
                }
                """;
        Path javaFile = tempDir.resolve("UserService.java");
        Files.writeString(javaFile, content);

        ServiceCallExtractor extractor = new ServiceCallExtractor();
        List<ServiceCallExtractor.CallRelation> relations = extractor.extractFromFile(javaFile.toFile());

        // lambda 捕获了外层局部变量 mapper（类型 UserMapper），mapper.findById 应解析为 UserMapper
        boolean lambdaCaptures = relations.stream()
                .anyMatch(r -> "DATABASE_CALL".equals(r.getEdgeTargetKind())
                        && "UserMapper".equals(r.getTargetClass())
                        && "findById".equals(r.getTargetMethod()));
        assertTrue(lambdaCaptures, "lambda 闭包捕获外层 mapper 局部变量应解析 targetClass=UserMapper");
    }
}
