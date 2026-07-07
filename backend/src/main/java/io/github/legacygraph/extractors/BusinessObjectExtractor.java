package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.legacygraph.model.NodeExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * 业务对象扫描器 - 从 Entity/DTO/Domain Model 类提取 BusinessObject 节点
 * 
 * 扫描目标：
 * - @Entity / @Table 注解的 JPA 实体
 * - @TableName 注解的 MyBatis-Plus 实体
 * - 类名匹配 *Entity / *DO / *PO / *BO / *DTO 模式
 * - 类在 entity/model/domain/dto 包下
 */
@Slf4j
@Component
public class BusinessObjectExtractor {

    private static final Set<String> ENTITY_ANNOTATIONS = Set.of(
            "Entity", "Table", "TableName", "Document"
    );

    private static final Set<String> ENTITY_SUFFIXES = Set.of(
            "Entity", "DO", "PO", "BO", "DTO", "VO", "Model", "Domain"
    );

    private final JavaParser javaParser;

    public BusinessObjectExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    public List<NodeExtractionResult> extract(File file) {
        List<NodeExtractionResult> results = new ArrayList<>();
        if (!file.getName().endsWith(".java")) return results;
        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(file);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return results;
            }

            CompilationUnit cu = parseResult.getResult().get();
            String filePath = file.getAbsolutePath();

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                if (clazz.isInterface()) return;

                String className = clazz.getNameAsString();

                // 策略1: 检查持久化注解 @Entity / @Table / @TableName / @Document
                boolean hasEntityAnnotation = clazz.getAnnotations().stream()
                        .map(AnnotationExpr::getNameAsString)
                        .anyMatch(ENTITY_ANNOTATIONS::contains);

                // 策略2: 类名后缀模式 (*Entity / *DO / *DTO 等)
                boolean hasEntitySuffix = ENTITY_SUFFIXES.stream()
                        .anyMatch(className::endsWith);

                // 策略3: 继承了常见实体基类（如 BaseEntity / Model<T>）
                boolean extendsBaseEntity = clazz.getExtendedTypes().stream()
                        .anyMatch(t -> {
                            String parent = t.getNameAsString();
                            return "BaseEntity".equals(parent) || "Model".equals(parent)
                                    || parent.contains("Entity") || parent.contains("BaseModel");
                        });

                // 策略4: 有 @Data/@Getter + 多个字段（POJO 结构）且字段上有 @TableField/@Column 注解
                boolean hasOrmFieldAnnotations = clazz.getFields().stream()
                        .anyMatch(f -> f.getAnnotations().stream()
                                .anyMatch(a -> "TableField".equals(a.getNameAsString())
                                        || "Column".equals(a.getNameAsString())
                                        || "Id".equals(a.getNameAsString())));

                if (hasEntityAnnotation || hasEntitySuffix || extendsBaseEntity || hasOrmFieldAnnotations) {
                    String objectName = cleanClassName(className);
                    String nodeKey = "bo:" + objectName.toLowerCase();

                    // 提取表名（如果有）
                    String tableName = extractTableName(clazz);

                    NodeExtractionResult result = NodeExtractionResult.builder()
                            .nodeType("BusinessObject")
                            .nodeKey(nodeKey)
                            .displayName(objectName)
                            .description("业务对象: " + objectName + " (类: " + className + ")")
                            .sourcePath(filePath)
                            .sourceType("CODE_AST")
                            .confidence(hasEntityAnnotation ? 0.9 : hasOrmFieldAnnotations ? 0.8 : 0.7)
                            .properties(Map.of(
                                    "className", className,
                                    "tableName", tableName != null ? tableName : ""
                            ))
                            .build();
                    results.add(result);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to parse business objects from {}: {}", file.getName(), e.getMessage());
        }
        return results;
    }

    private String cleanClassName(String className) {
        for (String suffix : ENTITY_SUFFIXES) {
            if (className.endsWith(suffix)) {
                return className.substring(0, className.length() - suffix.length());
            }
        }
        return className;
    }

    private String extractTableName(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream()
                .filter(a -> "Table".equals(a.getNameAsString()) || "TableName".equals(a.getNameAsString()))
                .findFirst()
                .map(a -> {
                    if (a.isSingleMemberAnnotationExpr()) {
                        return a.asSingleMemberAnnotationExpr().getMemberValue().toString().replace("\"", "");
                    } else if (a.isNormalAnnotationExpr()) {
                        return a.asNormalAnnotationExpr().getPairs().stream()
                                .filter(p -> "value".equals(p.getNameAsString()) || "name".equals(p.getNameAsString()))
                                .findFirst()
                                .map(p -> p.getValue().toString().replace("\"", ""))
                                .orElse(null);
                    }
                    return null;
                })
                .orElse(null);
    }
}
