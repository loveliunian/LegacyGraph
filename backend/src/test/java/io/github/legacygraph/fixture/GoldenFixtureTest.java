package io.github.legacygraph.fixture;

import io.github.legacygraph.extractors.JavaControllerExtractor;
import io.github.legacygraph.extractors.JavaStructureExtractor;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.extractors.ServiceCallExtractor;
import io.github.legacygraph.model.ApiFact;
import io.github.legacygraph.model.MapperSqlFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3b 黄金 fixture 测试 —— 用于回归验证图谱扫描质量。
 * <p>
 * 构造一个迷你 Java 项目的内存表示（通过 @TempDir 落地临时文件供抽取器解析），
 * 覆盖 Controller → Service → Mapper → SqlStatement → Table 完整链路、
 * 继承关系（Controller extends BaseController）以及方法签名归一化。
 * 全程不依赖真实 Neo4j，仅验证抽取器输出的事实数据结构能正确形成链路。
 * </p>
 */
@SpringBootTest
class GoldenFixtureTest {

    @TempDir
    Path tempDir;

    /**
     * 端到端链路：Controller → Service → Mapper → SqlStatement → Table。
     * 验证各抽取器输出的事实能在字段层面对齐，形成完整调用/读写链路。
     */
    @Test
    void testEndToEndPath() throws Exception {
        // === 迷你 Java 项目：4 个源文件 + 1 个 MyBatis XML ===
        Path controllerFile = writeResource("controller/UserController.java", """
                package com.example.controller;

                import com.example.service.UserService;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/users")
                public class UserController {
                    @Autowired
                    private UserService userService;

                    @GetMapping("/{id}")
                    public String getUser(@PathVariable Long id) {
                        return userService.findById(id);
                    }
                }
                """);

        Path serviceFile = writeResource("service/UserService.java", """
                package com.example.service;

                import com.example.mapper.UserMapper;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;

                @Service
                public class UserService {
                    @Autowired
                    private UserMapper userMapper;

                    public String findById(Long id) {
                        return userMapper.findById(id);
                    }
                }
                """);

        writeResource("mapper/UserMapper.java", """
                package com.example.mapper;

                public interface UserMapper {
                    String findById(Long id);
                }
                """);

        Path mapperXml = writeResource("mapper/UserMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.mapper.UserMapper">
                    <select id="findById" parameterType="long" resultType="string">
                        SELECT id, name FROM t_user WHERE id = #{id}
                    </select>
                </mapper>
                """);

        // 1. Controller → ApiFact
        List<ApiFact> apiFacts = new JavaControllerExtractor().extractFromFile(controllerFile);
        assertFalse(apiFacts.isEmpty(), "应抽取到 API 事实");
        ApiFact api = apiFacts.get(0);
        assertTrue(api.getControllerClass().contains("UserController"),
                "controllerClass 应为 UserController，实际: " + api.getControllerClass());

        // 2. Controller → Service 调用关系
        ServiceCallExtractor callExtractor = new ServiceCallExtractor();
        List<ServiceCallExtractor.CallRelation> ctrlCalls = callExtractor.extractFromFile(controllerFile.toFile());
        ServiceCallExtractor.CallRelation ctrl2service = ctrlCalls.stream()
                .filter(r -> "UserService".equals(r.getTargetClass()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Controller → Service 调用关系缺失"));
        assertEquals("UserService", ctrl2service.getTargetClass());
        assertEquals("findById", ctrl2service.getCalledMethod());

        // 3. Service → Mapper 调用关系
        List<ServiceCallExtractor.CallRelation> svcCalls = callExtractor.extractFromFile(serviceFile.toFile());
        ServiceCallExtractor.CallRelation svc2mapper = svcCalls.stream()
                .filter(r -> "UserMapper".equals(r.getTargetClass()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Service → Mapper 调用关系缺失"));
        assertEquals("UserMapper", svc2mapper.getTargetClass());

        // 4. Mapper XML → SqlStatement → Table
        MapperSqlFact mapperFact = new MyBatisXmlExtractor().extractFromFile(mapperXml.toFile());
        assertEquals("com.example.mapper.UserMapper", mapperFact.getNamespace());
        assertFalse(mapperFact.getStatements().isEmpty(), "应抽取到 SQL 语句");
        MyBatisXmlExtractor.SqlStatement stmt = mapperFact.getStatements().get(0);
        assertEquals("findById", stmt.getId());
        assertTrue(stmt.getReadTables().stream().anyMatch(t -> t.equalsIgnoreCase("t_user")),
                "SqlStatement 应读取 t_user 表，实际: " + stmt.getReadTables());

        // 5. 链路闭环验证：Controller → Service → Mapper → SqlStatement → Table
        // Controller：ApiFact 与 CallRelation 调用方对齐
        assertTrue(ctrl2service.getCallerClass().endsWith(api.getControllerClass()),
                "Controller 调用方应与 ApiFact 的 controllerClass 对齐");
        // Service：Controller→Service 的 target 与 Service→Mapper 的 caller 对齐
        assertEquals(ctrl2service.getTargetClass(), simpleName(svc2mapper.getCallerClass()),
                "Service 在 Controller→Service 与 Service→Mapper 间应对齐");
        // Mapper：Service→Mapper 的 target 与 XML namespace 对齐
        assertTrue(mapperFact.getNamespace().endsWith(svc2mapper.getTargetClass()),
                "Mapper 在 Service→Mapper 与 XML namespace 间应对齐");
        // SqlStatement：namespace.findById 与 Service 调用方法对齐
        assertTrue((mapperFact.getNamespace() + "." + stmt.getId()).endsWith(
                        svc2mapper.getCalledMethod()),
                "SqlStatement 应与 Mapper 方法对齐");
        // Table：SqlStatement 读表存在
        assertTrue(stmt.getReadTables().stream().anyMatch(t -> t.equalsIgnoreCase("t_user")),
                "链路终点 Table 应为 t_user");
    }

    /**
     * 验证继承关系抽取 —— Controller extends BaseController，为 GraphBuilder 的 EXTENDS 边提供数据源。
     */
    @Test
    void testInheritanceEdge() throws Exception {
        writeResource("controller/BaseController.java", """
                package com.example.controller;

                public class BaseController {
                    public void sharedInit() {}
                }
                """);
        Path childFile = writeResource("controller/UserController.java", """
                package com.example.controller;

                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class UserController extends BaseController {
                    public String getUser(Long id) {
                        return null;
                    }
                }
                """);

        List<JavaStructureExtractor.JavaClassInfo> classes =
                new JavaStructureExtractor().extractFromFile(childFile);
        JavaStructureExtractor.JavaClassInfo userCtrl = classes.stream()
                .filter(c -> "UserController".equals(c.getClassName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未抽取到 UserController"));

        assertNotNull(userCtrl.getExtendedTypes(), "extendedTypes 不应为 null");
        assertTrue(userCtrl.getExtendedTypes().contains("BaseController"),
                "应抽取到 EXTENDS BaseController，实际: " + userCtrl.getExtendedTypes());
        // GraphBuilder.buildJavaStructureGraph 会基于此创建 EdgeType.EXTENDS 边
        assertEquals("UserController", userCtrl.getClassName());
        assertEquals("com.example.controller.UserController", userCtrl.getQualifiedName());
    }

    /**
     * 验证 {@link io.github.legacygraph.extractors.MethodSignatureSupport} 的类型名归一化。
     * MethodSignatureSupport 为 package-private，通过反射调用 normalizeTypeName。
     */
    @Test
    void testSignatureNormalization() throws Exception {
        Class<?> supportClass = Class.forName("io.github.legacygraph.extractors.MethodSignatureSupport");
        Method normalize = supportClass.getDeclaredMethod("normalizeTypeName", String.class);
        normalize.setAccessible(true);

        // 全限定名 → 简单名
        assertEquals("String", normalize.invoke(null, "java.lang.String"));
        assertEquals("User", normalize.invoke(null, "com.example.entity.User"));
        // 泛型参数擦除
        assertEquals("List", normalize.invoke(null, "List<User>"));
        assertEquals("Map", normalize.invoke(null, "java.util.Map<String, Object>"));
        // 原始/简单类型保持
        assertEquals("Long", normalize.invoke(null, "Long"));
        assertEquals("int", normalize.invoke(null, "int"));
        // null / 空串透传
        assertNull(normalize.invoke(null, (Object) null));
        assertEquals("", normalize.invoke(null, ""));
    }

    // ===== helpers =====

    private Path writeResource(String relative, String content) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return null;
        int dot = fqn.lastIndexOf('.');
        return dot > 0 ? fqn.substring(dot + 1) : fqn;
    }
}
