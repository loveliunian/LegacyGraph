package io.github.legacygraph;

import io.github.legacygraph.extractors.JavaControllerExtractor;
import io.github.legacygraph.model.ApiFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JavaControllerExtractor 单元测试
 * 测试从 Spring Boot Controller 中抽取 API 接口信息
 */
class JavaControllerExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void testRestControllerWithGetMapping() throws IOException {
        // given: @RestController + @GetMapping
        String content = """
                package com.example.controller;
                                
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                                
                @RestController
                public class UserController {
                    @GetMapping("/users/{id}")
                    public String getUser(Long id) {
                        return "user";
                    }
                }
                """;
        Path javaFile = tempDir.resolve("UserController.java");
        Files.writeString(javaFile, content);

        JavaControllerExtractor extractor = new JavaControllerExtractor();

        // when
        List<ApiFact> facts = extractor.extractFromFile(javaFile);

        // then
        assertNotNull(facts);
        assertEquals(1, facts.size(), "应抽取到 1 个 API");

        ApiFact fact = facts.get(0);
        assertEquals("GET", fact.getHttpMethod());
        assertEquals("/users/{id}", fact.getPath());
        assertEquals("/users/{id}", fact.getFullPath(), "无类级别路径时应与方法路径相同");
        assertEquals("UserController", fact.getControllerClass());
        assertEquals("com.example.controller", fact.getControllerPackage());
        assertEquals("getUser", fact.getMethodName());
        assertEquals("String", fact.getResponseType());
        assertNotNull(fact.getRequestParams());
        assertEquals(1, fact.getRequestParams().size());
        assertEquals("id", fact.getRequestParams().get(0).getName());
        assertEquals("Long", fact.getRequestParams().get(0).getType());
        assertTrue(fact.getRequestParams().get(0).isRequired(), "非 Optional 类型应 required");
        // 检查注解收集
        assertTrue(fact.getAnnotations().contains("RestController"), "应包含类级别注解");
        assertTrue(fact.getAnnotations().contains("GetMapping"), "应包含方法级别注解");
        // 路径应包含源文件
        assertTrue(fact.getSourcePath().endsWith("UserController.java"));
        assertNotNull(fact.getStartLine());
        assertNotNull(fact.getEndLine());
    }

    @Test
    void testPostMappingWithClassRequestMapping() throws IOException {
        // given: 类级别 @RequestMapping + 方法 @PostMapping
        String content = """
                package com.example.controller;
                                
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;
                                
                @RestController
                @RequestMapping("/api/users")
                public class UserController {
                    @PostMapping("/create")
                    public String createUser(@RequestBody String user) {
                        return "ok";
                    }
                }
                """;
        Path javaFile = tempDir.resolve("UserController.java");
        Files.writeString(javaFile, content);

        JavaControllerExtractor extractor = new JavaControllerExtractor();

        // when
        List<ApiFact> facts = extractor.extractFromFile(javaFile);

        // then
        assertNotNull(facts);
        assertEquals(1, facts.size(), "应抽取到 1 个 API");

        ApiFact fact = facts.get(0);
        assertEquals("POST", fact.getHttpMethod());
        assertEquals("/create", fact.getPath());
        assertEquals("/api/users/create", fact.getFullPath(), "应拼接类路径与方法路径");
        assertEquals("UserController", fact.getControllerClass());
        assertEquals("createUser", fact.getMethodName());
        assertEquals("String", fact.getResponseType());
        // 请求体
        assertNotNull(fact.getRequestBody());
        assertEquals("user", fact.getRequestBody().getName());
        assertEquals("String", fact.getRequestBody().getType());
        assertTrue(fact.getRequestBody().isRequired());
    }

    @Test
    void testPreAuthorizePermissions() throws IOException {
        // given: @PreAuthorize + @RequiresPermissions 权限注解
        String content = """
                package com.example.controller;
                                
                import org.apache.shiro.authz.annotation.RequiresPermissions;
                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.RestController;
                                
                @RestController
                public class AdminController {
                    @DeleteMapping("/admin/users/{id}")
                    @PreAuthorize("hasAuthority('sys:user:delete')")
                    @RequiresPermissions("sys:user:delete")
                    public String deleteUser(Long id) {
                        return "deleted";
                    }
                }
                """;
        Path javaFile = tempDir.resolve("AdminController.java");
        Files.writeString(javaFile, content);

        JavaControllerExtractor extractor = new JavaControllerExtractor();

        // when
        List<ApiFact> facts = extractor.extractFromFile(javaFile);

        // then
        assertNotNull(facts);
        assertEquals(1, facts.size(), "应抽取到 1 个 API");

        ApiFact fact = facts.get(0);
        assertEquals("DELETE", fact.getHttpMethod());
        assertEquals("/admin/users/{id}", fact.getPath());
        assertEquals("deleteUser", fact.getMethodName());

        // 验证权限
        List<String> permissions = fact.getPermissions();
        assertNotNull(permissions);
        assertTrue(permissions.size() >= 2, "应抽取到至少 2 个权限");
        // PreAuthorize 的权限值 may be extracted differently depending on implementation
        // 至少应该提取到 RequiresPermissions 的值
        boolean hasSysUserDelete = permissions.stream()
                .anyMatch(p -> p.contains("sys:user:delete"));
        assertTrue(hasSysUserDelete, "应抽取到 sys:user:delete 权限");
    }

    @Test
    void testRequestMappingWithMethodPost() throws IOException {
        // given: @RequestMapping(method=RequestMethod.POST) 不使用 @PostMapping
        String content = """
                package com.example.controller;
                                
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;
                                
                @RestController
                public class LegacyController {
                    @RequestMapping(value = "/legacy/submit", method = RequestMethod.POST)
                    public String submitData(String data) {
                        return "done";
                    }
                }
                """;
        Path javaFile = tempDir.resolve("LegacyController.java");
        Files.writeString(javaFile, content);

        JavaControllerExtractor extractor = new JavaControllerExtractor();

        // when
        List<ApiFact> facts = extractor.extractFromFile(javaFile);

        // then
        assertNotNull(facts);
        assertEquals(1, facts.size(), "应抽取到 1 个 API");

        ApiFact fact = facts.get(0);
        assertEquals("POST", fact.getHttpMethod(), "应从 @RequestMapping(method=POST) 提取 POST");
        assertEquals("/legacy/submit", fact.getPath());
        assertEquals("/legacy/submit", fact.getFullPath());
        assertEquals("submitData", fact.getMethodName());
    }

    @Test
    void testNonControllerReturnsEmpty() throws IOException {
        // given: 普通类，没有 Controller 注解
        String content = """
                package com.example.service;
                                
                public class NormalService {
                    public String doSomething(String input) {
                        return "result: " + input;
                    }
                }
                """;
        Path javaFile = tempDir.resolve("NormalService.java");
        Files.writeString(javaFile, content);

        JavaControllerExtractor extractor = new JavaControllerExtractor();

        // when
        List<ApiFact> facts = extractor.extractFromFile(javaFile);

        // then
        assertNotNull(facts);
        assertTrue(facts.isEmpty(), "非 Controller 类应返回空列表");
    }
}
