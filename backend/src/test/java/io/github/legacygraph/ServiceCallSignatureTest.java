package io.github.legacygraph;

import io.github.legacygraph.extractors.ServiceCallExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ServiceCallExtractor 为方法级 CALLS 边生成与 JavaStructureExtractor 对齐的被调用方法签名。
 * 对应图谱审计报告 P0：方法级 CALLS 边 nodeKey 对齐（caller + target 双侧）。
 *
 * <p>背景：Method 节点 nodeKey = {@code 类全限定名.方法名(参数类型)}（由 JavaStructureExtractor 生成）。
 * 此前 ServiceCallExtractor 仅生成 caller 侧签名，target 侧 {@code calledMethodSignature} 始终为 null，
 * 导致 buildServiceCallGraph 中 target Method 节点查找失败、方法级 CALLS 边被静默跳过。
 * 现基于调用实参推断 target 侧签名，使双侧 nodeKey 对齐。
 */
class ServiceCallSignatureTest {

    @TempDir
    Path tempDir;

    @Test
    void calledMethodSignature_alignedWithMethodParam() throws IOException {
        // 实参 id 为方法形参 Long → 被调用签名 findById(Long)，与 Method nodeKey 对齐
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

        List<ServiceCallExtractor.CallRelation> relations =
                new ServiceCallExtractor().extractFromFile(javaFile.toFile());

        ServiceCallExtractor.CallRelation findById = relations.stream()
                .filter(r -> "findById".equals(r.getCalledMethod()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应抽取到 findById 调用"));

        assertEquals("findById(Long)", findById.getCalledMethodSignature(),
                "被调用方法签名应基于实参类型生成，与方法形参 Long 对齐");
        assertEquals("getUser(Long)", findById.getCallerMethodSignature(),
                "caller 侧签名同样应与方法形参对齐");
    }

    @Test
    void calledMethodSignature_noArgsCall() throws IOException {
        String content = """
                package com.example.controller;

                import com.example.service.BootService;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class BootController {
                    @Autowired
                    private BootService bootService;

                    public void start() {
                        bootService.init();
                    }
                }
                """;
        Path javaFile = tempDir.resolve("BootController.java");
        Files.writeString(javaFile, content);

        List<ServiceCallExtractor.CallRelation> relations =
                new ServiceCallExtractor().extractFromFile(javaFile.toFile());

        ServiceCallExtractor.CallRelation init = relations.stream()
                .filter(r -> "init".equals(r.getCalledMethod()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应抽取到 init 调用"));

        assertEquals("init()", init.getCalledMethodSignature(),
                "无参调用签名应为 init()");
    }

    @Test
    void calledMethodSignature_unresolvableArg_isNull() throws IOException {
        // 链式方法调用作为实参，返回类型无法在不解析符号的情况下推断 → 签名为 null（回退，避免误匹配重载）
        String content = """
                package com.example.controller;

                import com.example.service.UserService;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class RefreshController {
                    @Autowired
                    private UserService userService;

                    public void refresh() {
                        userService.reload(userService.current());
                    }
                }
                """;
        Path javaFile = tempDir.resolve("RefreshController.java");
        Files.writeString(javaFile, content);

        List<ServiceCallExtractor.CallRelation> relations =
                new ServiceCallExtractor().extractFromFile(javaFile.toFile());

        ServiceCallExtractor.CallRelation reload = relations.stream()
                .filter(r -> "reload".equals(r.getCalledMethod()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应抽取到 reload 调用"));

        assertNull(reload.getCalledMethodSignature(),
                "实参为方法调用且返回类型无法推断时，签名应为 null 以避免误匹配重载");
    }
}
