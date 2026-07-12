package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Java source structure extractor.
 */
@Slf4j
@Component
public class JavaStructureExtractor {

    /** ThreadLocal JavaParser — parallelStream 安全（JavaParser 非线程安全） */
    private final ThreadLocal<JavaParser> javaParser = ThreadLocal.withInitial(() -> {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        return new JavaParser(config);
    });

    public List<JavaClassInfo> extractFromFile(Path javaFile) throws IOException {
        return extractFromFile(javaFile, null);
    }

    /**
     * 从 Java 文件抽取类结构信息。
     *
     * @param javaFile       Java 源文件路径
     * @param cachedContent  预读的文件内容缓存（可为 null，null 时 fallback 读文件）
     */
    public List<JavaClassInfo> extractFromFile(Path javaFile, String cachedContent) throws IOException {
        List<JavaClassInfo> classes = new ArrayList<>();

        // 先读入内存再解析，避免 I/O 竞争读到半截文件；优先用缓存内容避免重复 I/O
        JavaParser parser = javaParser.get();
        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(cachedContent != null ? cachedContent : Files.readString(javaFile));
        } catch (RuntimeException e) {
            // JavaParser 词法分析器偶发内部崩溃（如 IndexOutOfBounds），重试一次
            log.warn("JavaParser crashed on first parse attempt (will retry): {} — {}", javaFile, e.getMessage());
            try {
                result = parser.parse(Files.readString(javaFile));
            } catch (RuntimeException e2) {
                log.warn("Failed to parse Java structure (JavaParser crash after retry): {}", javaFile);
                log.warn("Parse error: {}", e2.getMessage());
                return classes;
            }
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            // 偶发 I/O 竞争导致读入不完整 → 重读源文件并重试一次
            try {
                result = parser.parse(Files.readString(javaFile));
            } catch (RuntimeException e) {
                log.warn("Failed to parse Java structure (JavaParser crash on retry): {}", javaFile);
                log.warn("Parse error: {}", e.getMessage());
                return classes;
            }
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.warn("Failed to parse Java structure (after retry): {}", javaFile);
            logParseProblems(result);
            return classes;
        }

        CompilationUnit cu = result.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        // 提取文件级 import 语句全名（如 com.foo.bar.Baz / com.foo.bar.*），用于包级 DEPENDS_ON 接线
        List<String> imports = new ArrayList<>();
        for (var imp : cu.getImports()) {
            imports.add(imp.getNameAsString());
        }

        List<ClassOrInterfaceDeclaration> allClasses;
        try {
            allClasses = cu.findAll(ClassOrInterfaceDeclaration.class);
        } catch (RuntimeException e) {
            log.warn("findAll(ClassOrInterfaceDeclaration) failed for {} ({}), falling back to top-level types only",
                    javaFile, e.getMessage());
            allClasses = new ArrayList<>();
            for (var type : cu.getTypes()) {
                if (type instanceof ClassOrInterfaceDeclaration cid) {
                    allClasses.add(cid);
                }
            }
        }

        for (ClassOrInterfaceDeclaration clazz : allClasses) {
            String className = clazz.getNameAsString();
            String qualifiedName = clazz.getFullyQualifiedName()
                    .orElse(packageName.isBlank() ? className : packageName + "." + className);
            // 提取继承和实现列表（简单名，后续由 resolver 解析为 FQN）
            List<String> extendedTypes = new ArrayList<>();
            for (var ext : clazz.getExtendedTypes()) {
                extendedTypes.add(ext.getNameAsString());
            }
            List<String> implementedTypes = new ArrayList<>();
            for (var impl : clazz.getImplementedTypes()) {
                implementedTypes.add(impl.getNameAsString());
            }

            // P1-3: 检测内部类 — 判断是否有父 ClassOrInterfaceDeclaration
            boolean isNested = clazz.getParentNode()
                    .flatMap(p -> java.util.Optional.of(p instanceof ClassOrInterfaceDeclaration))
                    .orElse(false);
            String outerQualifiedName = null;
            if (isNested) {
                // 提取外层类的 FQN
                var parent = clazz.getParentNode();
                while (parent.isPresent() && !(parent.get() instanceof ClassOrInterfaceDeclaration)) {
                    parent = parent.get().getParentNode();
                }
                if (parent.isPresent() && parent.get() instanceof ClassOrInterfaceDeclaration outer) {
                    outerQualifiedName = outer.getFullyQualifiedName().orElse(null);
                }
            }

            JavaClassInfo classInfo = new JavaClassInfo();
            classInfo.setClassName(className);
            classInfo.setPackageName(packageName);
            classInfo.setQualifiedName(qualifiedName);
            classInfo.setKind(clazz.isInterface() ? "INTERFACE" : "CLASS");
            classInfo.setSourcePath(javaFile.toString());
            classInfo.setStartLine(clazz.getBegin().map(p -> p.line).orElse(null));
            classInfo.setEndLine(clazz.getEnd().map(p -> p.line).orElse(null));
            // S2-T6: 列级定位 span
            classInfo.setStartColumn(clazz.getBegin().map(p -> p.column).orElse(null));
            classInfo.setEndColumn(clazz.getEnd().map(p -> p.column).orElse(null));
            classInfo.setMethods(new ArrayList<>());
            classInfo.setFields(new ArrayList<>());
            classInfo.setExtendedTypes(extendedTypes);
            classInfo.setImplementedTypes(implementedTypes);
            classInfo.setImports(imports);
            classInfo.setNested(isNested);
            classInfo.setOuterQualifiedName(outerQualifiedName);
            // L-09: 提取类级注解用于 inferNodeType 优先判定
            if (!clazz.getAnnotations().isEmpty()) {
                classInfo.setAnnotations(clazz.getAnnotations().stream()
                        .map(a -> a.getNameAsString())
                        .collect(java.util.stream.Collectors.toList()));
            }

            // 收集已声明的方法名，避免 Lombok 虚拟方法重复
            Set<String> declaredMethodNames = new HashSet<>();
            try {
                for (MethodDeclaration method : clazz.getMethods()) {
                    String methodName = method.getNameAsString();
                    String methodSignature = MethodSignatureSupport.build(method);
                    JavaMethodInfo methodInfo = new JavaMethodInfo(
                            methodName,
                            qualifiedName + "." + methodSignature,
                            method.getBegin().map(p -> p.line).orElse(null),
                            method.getEnd().map(p -> p.line).orElse(null),
                            method.getBegin().map(p -> p.column).orElse(null),
                            method.getEnd().map(p -> p.column).orElse(null)
                    );
                    // L-10: 提取方法注解和 synchronized 修饰符用于并发属性
                    if (!method.getAnnotations().isEmpty()) {
                        List<String> annos = method.getAnnotations().stream()
                                .map(a -> a.getNameAsString())
                                .collect(java.util.stream.Collectors.toList());
                        methodInfo.setAnnotations(annos);
                    }
                    methodInfo.setSynchronizedModifier(
                            method.getModifiers().stream()
                                    .anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.SYNCHRONIZED));
                    classInfo.getMethods().add(methodInfo);
                    declaredMethodNames.add(methodName);
                }
            } catch (RuntimeException e) {
                log.warn("getMethods() failed for class {} in {}: {}", className, javaFile, e.getMessage());
            }

            // L-12: 抽取字段声明（含字段级注解 @Value/@Autowired/@ConfigurationProperties/@Resource 等），
            // 供 GraphBuilder 按注解分类入图为 ConfigItem / Dependency / FeatureFlag 节点
            try {
                for (FieldDeclaration field : clazz.getFields()) {
                    // 字段级注解挂在 FieldDeclaration 上（多个变量共享同一组注解）
                    List<String> fieldAnnotations = new ArrayList<>();
                    if (!field.getAnnotations().isEmpty()) {
                        fieldAnnotations = field.getAnnotations().stream()
                                .map(a -> a.getNameAsString())
                                .collect(java.util.stream.Collectors.toList());
                    }
                    Integer fieldStart = field.getBegin().map(p -> p.line).orElse(null);
                    Integer fieldEnd = field.getEnd().map(p -> p.line).orElse(null);
                    // 一个字段声明可能声明多个变量（如 private String a, b;），逐个收集
                    Integer fieldStartCol = field.getBegin().map(p -> p.column).orElse(null);
                    Integer fieldEndCol = field.getEnd().map(p -> p.column).orElse(null);
                    for (VariableDeclarator var : field.getVariables()) {
                        classInfo.getFields().add(new JavaFieldInfo(
                                var.getNameAsString(),
                                var.getTypeAsString(),
                                fieldAnnotations,
                                fieldStart,
                                fieldEnd,
                                fieldStartCol,
                                fieldEndCol
                        ));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("getFields() failed for class {} in {}: {}", className, javaFile, e.getMessage());
            }

            // P1-3: Lombok 虚拟方法生成
            try {
                generateLombokVirtualMethods(clazz, qualifiedName, classInfo, declaredMethodNames);
            } catch (RuntimeException e) {
                log.warn("generateLombokVirtualMethods failed for class {} in {}: {}", className, javaFile, e.getMessage());
            }

            classes.add(classInfo);
        }

        return classes;
    }

    /**
     * P1-3: 检测 Lombok 注解并为 @Data/@Getter/@Setter/@Builder 生成虚拟方法节点。
     * <p>
     * Lombok 在编译期生成的方法不会出现在 AST 中，导致 Method 节点缺失，
     * 影响调用链解析。此处通过注解检测合成对应方法。
     * </p>
     * <ul>
     *   <li>@Data / @Getter → getXxx() 方法</li>
     *   <li>@Data / @Setter → setXxx(xxx) 方法</li>
     *   <li>@Builder → builder() 静态方法 + Builder 内部类</li>
     *   <li>@ToString → toString() 方法</li>
     *   <li>@EqualsAndHashCode → equals() / hashCode() 方法</li>
     * </ul>
     */
    private void generateLombokVirtualMethods(ClassOrInterfaceDeclaration clazz,
                                               String qualifiedName,
                                               JavaClassInfo classInfo,
                                               Set<String> declaredMethodNames) {
        boolean hasData = hasAnnotation(clazz, "Data");
        boolean hasGetter = hasData || hasAnnotation(clazz, "Getter");
        boolean hasSetter = hasData || hasAnnotation(clazz, "Setter");
        boolean hasBuilder = hasAnnotation(clazz, "Builder");
        boolean hasToString = hasData || hasAnnotation(clazz, "ToString");
        boolean hasEquals = hasData || hasAnnotation(clazz, "EqualsAndHashCode");

        if (!hasGetter && !hasSetter && !hasBuilder && !hasToString && !hasEquals) {
            return;
        }

        int virtualLine = clazz.getBegin().map(p -> p.line).orElse(0);

        // 遍历字段生成 getter/setter
        for (FieldDeclaration field : clazz.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                String fieldName = var.getNameAsString();
                String fieldType = var.getTypeAsString();

                // getter: getFieldName() 或 isFieldName()（boolean 类型）
                if (hasGetter) {
                    String getterName = isBooleanType(fieldType)
                            ? "is" + capitalize(fieldName)
                            : "get" + capitalize(fieldName);
                    if (!declaredMethodNames.contains(getterName)) {
                        classInfo.getMethods().add(new JavaMethodInfo(
                                getterName,
                                qualifiedName + "." + getterName + "()",
                                virtualLine, virtualLine
                        ));
                        declaredMethodNames.add(getterName);
                    }
                }

                // setter: setFieldName(fieldType)
                if (hasSetter) {
                    String setterName = "set" + capitalize(fieldName);
                    if (!declaredMethodNames.contains(setterName)) {
                        String paramType = simplifyType(fieldType);
                        classInfo.getMethods().add(new JavaMethodInfo(
                                setterName,
                                qualifiedName + "." + setterName + "(" + paramType + ")",
                                virtualLine, virtualLine
                        ));
                        declaredMethodNames.add(setterName);
                    }
                }
            }
        }

        // builder() 静态方法
        if (hasBuilder && !declaredMethodNames.contains("builder")) {
            classInfo.getMethods().add(new JavaMethodInfo(
                    "builder",
                    qualifiedName + ".builder()",
                    virtualLine, virtualLine
            ));
            declaredMethodNames.add("builder");
        }

        // toString()
        if (hasToString && !declaredMethodNames.contains("toString")) {
            classInfo.getMethods().add(new JavaMethodInfo(
                    "toString",
                    qualifiedName + ".toString()",
                    virtualLine, virtualLine
            ));
            declaredMethodNames.add("toString");
        }

        // equals(Object) / hashCode()
        if (hasEquals) {
            if (!declaredMethodNames.contains("equals")) {
                classInfo.getMethods().add(new JavaMethodInfo(
                        "equals",
                        qualifiedName + ".equals(Object)",
                        virtualLine, virtualLine
                ));
                declaredMethodNames.add("equals");
            }
            if (!declaredMethodNames.contains("hashCode")) {
                classInfo.getMethods().add(new JavaMethodInfo(
                        "hashCode",
                        qualifiedName + ".hashCode()",
                        virtualLine, virtualLine
                ));
                declaredMethodNames.add("hashCode");
            }
        }
    }

    private boolean hasAnnotation(ClassOrInterfaceDeclaration clazz, String annotationName) {
        return clazz.getAnnotations().stream()
                .anyMatch(a -> {
                    String name = a.getNameAsString();
                    return name.equals(annotationName)
                            || name.equals("lombok." + annotationName)
                            || name.equals("lombok.experimental." + annotationName);
                });
    }

    private boolean isBooleanType(String type) {
        return "boolean".equals(type) || "Boolean".equals(type);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String simplifyType(String type) {
        // 去泛型
        int idx = type.indexOf('<');
        if (idx > 0) return type.substring(0, idx);
        return type;
    }

    /**
     * 安全输出解析问题概要。使用 getMessage() 仅输出一行描述，
     * 避免 getVerboseMessage() 输出超长源码上下文或 toString() 打印 stack trace。
     */
    private static void logParseProblems(ParseResult<?> result) {
        var problems = result.getProblems();
        if (problems == null || problems.isEmpty()) return;
        for (int i = 0; i < problems.size(); i++) {
            try {
                log.warn("Parse problem [{}]: {}", i, problems.get(i).getMessage());
            } catch (Exception ex) {
                log.warn("Parse problem [{}]: <FAILED getMessage(): {}>", i, ex.getMessage());
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JavaClassInfo {
        private String className;
        private String packageName;
        private String qualifiedName;
        private String kind;
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
        /** S2-T6: 列级定位 span（起始列，从 1 开始） */
        private Integer startColumn;
        /** S2-T6: 列级定位 span（结束列） */
        private Integer endColumn;
        private List<JavaMethodInfo> methods = new ArrayList<>();
        /** L-12: 类的字段列表（含字段级注解，用于入图为 ConfigItem/Dependency/FeatureFlag 节点） */
        private List<JavaFieldInfo> fields = new ArrayList<>();
        /** extends 的父类简单名列表 */
        private List<String> extendedTypes = new ArrayList<>();
        /** implements 的接口简单名列表 */
        private List<String> implementedTypes = new ArrayList<>();
        /** 当前文件 import 语句列表（全名，如 com.foo.bar.Baz 或 com.foo.bar.*）— 用于包级 DEPENDS_ON 接线 */
        private List<String> imports = new ArrayList<>();
        /** P1-3: 是否为嵌套类（内部类） */
        private boolean nested = false;
        /** P1-3: 外层类的全限定名（仅 nested=true 时有值） */
        private String outerQualifiedName;
        /** L-09: 类级注解名称列表（如 RestController, Service, Mapper），用于 inferNodeType 优先判定 */
        private List<String> annotations = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    public static class JavaMethodInfo {
        private String methodName;
        private String qualifiedName;
        private Integer startLine;
        private Integer endLine;
        /** S2-T6: 列级定位 span（起始列，从 1 开始） */
        private Integer startColumn;
        /** S2-T6: 列级定位 span（结束列） */
        private Integer endColumn;
        /** L-10: 方法上的注解名称列表（如 Transactional, Async, Cacheable, Lock） */
        private List<String> annotations;
        /** L-10: 是否有 synchronized 关键字 */
        private boolean synchronizedModifier;

        public JavaMethodInfo(String methodName, String qualifiedName, Integer startLine, Integer endLine) {
            this.methodName = methodName;
            this.qualifiedName = qualifiedName;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        /** S2-T6: 带列级定位的构造器 */
        public JavaMethodInfo(String methodName, String qualifiedName, Integer startLine, Integer endLine,
                              Integer startColumn, Integer endColumn) {
            this.methodName = methodName;
            this.qualifiedName = qualifiedName;
            this.startLine = startLine;
            this.endLine = endLine;
            this.startColumn = startColumn;
            this.endColumn = endColumn;
        }
    }

    /**
     * L-12: Java 字段信息 DTO。
     * <p>承载字段名、类型与字段级注解（如 @Value / @Autowired / @ConfigurationProperties / @Resource），
     * 供 GraphBuilder 按注解分类入图为 ConfigItem / Dependency / FeatureFlag 节点。</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JavaFieldInfo {
        private String fieldName;
        private String fieldType;
        /** 字段级注解简单名列表（如 Value, Autowired, Resource, ConfigurationProperties） */
        private List<String> annotations;
        private Integer startLine;
        private Integer endLine;
        /** S2-T6: 列级定位 span（起始列，从 1 开始） */
        private Integer startColumn;
        /** S2-T6: 列级定位 span（结束列） */
        private Integer endColumn;
    }

    // ==================== S2-T2: 注解→节点类型映射表 ====================

    /**
     * S2-T2: Spring/Stereo 注解 → 节点类型映射表。
     * <p>
     * 根据类上的注解推断节点类型，优先级高于包路径推断。
     * 映射规则：
     * <ul>
     *   <li>@RestController / @Controller → "CONTROLLER"</li>
     *   <li>@Service → "SERVICE"</li>
     *   <li>@Repository → "REPOSITORY"</li>
     *   <li>@Mapper / @Repository(mybatis) → "MAPPER"</li>
     *   <li>@Component / @ComponentScan → "COMPONENT"</li>
     *   <li>@Configuration / @ConfigurationProperties → "CONFIGURATION"</li>
     *   <li>@Entity / @Table → "ENTITY"</li>
     *   <li>@FeignClient → "FEIGN_CLIENT"</li>
     *   <li>@RestControllerAdvice / @ControllerAdvice → "ADVICE"</li>
     *   <li>@Aspect → "ASPECT"</li>
     * </ul>
     * </p>
     */
    private static final java.util.Map<String, String> ANNOTATION_TO_NODE_TYPE = java.util.Map.ofEntries(
            java.util.Map.entry("RestController", "CONTROLLER"),
            java.util.Map.entry("Controller", "CONTROLLER"),
            java.util.Map.entry("Service", "SERVICE"),
            java.util.Map.entry("Repository", "REPOSITORY"),
            java.util.Map.entry("Mapper", "MAPPER"),
            java.util.Map.entry("Component", "COMPONENT"),
            java.util.Map.entry("Configuration", "CONFIGURATION"),
            java.util.Map.entry("ConfigurationProperties", "CONFIGURATION"),
            java.util.Map.entry("Entity", "ENTITY"),
            java.util.Map.entry("Table", "ENTITY"),
            java.util.Map.entry("FeignClient", "FEIGN_CLIENT"),
            java.util.Map.entry("RestControllerAdvice", "ADVICE"),
            java.util.Map.entry("ControllerAdvice", "ADVICE"),
            java.util.Map.entry("Aspect", "ASPECT"),
            java.util.Map.entry("Data", "DATA_DTO"),
            java.util.Map.entry("Dto", "DATA_DTO"),
            java.util.Map.entry("Builder", "DATA_DTO")
    );

    /**
     * S2-T2: 根据类注解推断节点类型。
     * <p>遍历注解列表，返回第一个匹配的节点类型；无匹配返回 null（由调用方降级到包路径推断）。</p>
     *
     * @param annotations 类注解简单名列表
     * @return 节点类型字符串，或 null（无匹配）
     */
    public static String inferNodeTypeFromAnnotations(List<String> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }
        for (String annotation : annotations) {
            String normalized = normalizeAnnotationSimple(annotation);
            String nodeType = ANNOTATION_TO_NODE_TYPE.get(normalized);
            if (nodeType != null) {
                return nodeType;
            }
        }
        return null;
    }

    /** 去掉注解的包名前缀和参数，只保留简单名 */
    private static String normalizeAnnotationSimple(String annotation) {
        int parenIdx = annotation.indexOf('(');
        String name = parenIdx > 0 ? annotation.substring(0, parenIdx) : annotation;
        int dotIdx = name.lastIndexOf('.');
        return dotIdx > 0 ? name.substring(dotIdx + 1) : name;
    }
}
