package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import io.github.legacygraph.model.MapperSqlFact;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis 注解方式 Mapper 提取器
 * 从 Java 接口中提取 @Select/@Insert/@Update/@Delete 注解的 SQL 语句
 */
@Slf4j
public class MyBatisAnnotationExtractor {

    private static final String[] SQL_ANNOTATIONS = {
        "Select", "Insert", "Update", "Delete",
        "org.apache.ibatis.annotations.Select",
        "org.apache.ibatis.annotations.Insert",
        "org.apache.ibatis.annotations.Update",
        "org.apache.ibatis.annotations.Delete"
    };

    private final JavaParser javaParser;

    public MyBatisAnnotationExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件中提取注解方式的 SQL 语句
     * 
     * @param javaFile Java 源文件
     * @return MapperSqlFact 包含 Mapper 信息和 SQL 语句列表，如果不是 Mapper 接口则返回 null
     */
    public MapperSqlFact extractFromFile(File javaFile) {
        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFile);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return null;
            }

            CompilationUnit cu = parseResult.getResult().get();
            Optional<CompilationUnit.Storage> storage = cu.getStorage();
            if (storage.isEmpty()) {
                return null;
            }

            // 查找 Mapper 接口
            Optional<ClassOrInterfaceDeclaration> mapperInterface = cu.findAll(ClassOrInterfaceDeclaration.class)
                .stream()
                .filter(ClassOrInterfaceDeclaration::isInterface)
                .filter(c -> c.getNameAsString().endsWith("Mapper") 
                          || c.getNameAsString().endsWith("Dao")
                          || c.getNameAsString().endsWith("Repository"))
                .findFirst();

            if (mapperInterface.isEmpty()) {
                return null;
            }

            ClassOrInterfaceDeclaration mapper = mapperInterface.get();
            String className = mapper.getNameAsString();
            String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
            String namespace = packageName.isEmpty() ? className : packageName + "." + className;

            // 提取 SQL 语句
            List<MyBatisXmlExtractor.SqlStatement> statements = new ArrayList<>();
            int statementCounter = 0;

            for (MethodDeclaration method : mapper.getMethods()) {
                String methodName = method.getNameAsString();
                
                // 查找 SQL 注解
                for (String annotationName : SQL_ANNOTATIONS) {
                    Optional<AnnotationExpr> annotation = method.getAnnotationByName(annotationName);
                    if (annotation.isPresent()) {
                        String sql = extractSqlFromAnnotation(annotation.get());
                        if (sql != null && !sql.isBlank()) {
                            statementCounter++;
                            String statementId = methodName;
                            
                            // 确定 SQL 类型
                            String sqlType = determineSqlType(annotationName);
                            
                            MyBatisXmlExtractor.SqlStatement statementFact = new MyBatisXmlExtractor.SqlStatement();
                            statementFact.setId(statementId);
                            statementFact.setSql(sql);
                            statementFact.setType(sqlType);
                            statementFact.setStartLine(method.getBegin().map(p -> p.line).orElse(0));
                            statementFact.setEndLine(method.getEnd().map(p -> p.line).orElse(0));
                            
                            statements.add(statementFact);
                            log.debug("Extracted annotation SQL: {}.{} -> {} ({} chars)", 
                                className, methodName, sqlType, sql.length());
                        }
                        break; // 一个方法只处理第一个 SQL 注解
                    }
                }
            }

            if (statements.isEmpty()) {
                return null;
            }

            log.info("Extracted {} SQL statements from annotation Mapper: {}", statements.size(), namespace);

            MapperSqlFact result = new MapperSqlFact();
            result.setNamespace(namespace);
            result.setMapperInterface(className);
            result.setSourcePath(javaFile.getAbsolutePath());
            result.setStatements(statements);
            return result;

        } catch (Exception e) {
            log.warn("Failed to extract annotation Mapper from {}: {}", javaFile.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 从注解中提取 SQL 语句
     */
    private String extractSqlFromAnnotation(AnnotationExpr annotation) {
        try {
            // 处理 @Select("SELECT ...")
            if (annotation.isSingleMemberAnnotationExpr()) {
                return extractStringFromExpression(annotation.asSingleMemberAnnotationExpr().getMemberValue());
            }
            
            // 处理 @Select(value = "SELECT ...")
            if (annotation.isNormalAnnotationExpr()) {
                return annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("value"))
                    .findFirst()
                    .map(pair -> extractStringFromExpression(pair.getValue()))
                    .orElse(null);
            }

            // 处理 @Select({"SELECT ...", "FROM ..."})
            // JavaParser 会将数组字面量解析为 ArrayInitializerExpr
            return null;
        } catch (Exception e) {
            log.debug("Failed to extract SQL from annotation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从表达式中提取字符串值
     */
    private String extractStringFromExpression(com.github.javaparser.ast.expr.Expression expr) {
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().getValue();
        }
        if (expr.isTextBlockLiteralExpr()) {
            return expr.asTextBlockLiteralExpr().getValue();
        }
        if (expr.isArrayInitializerExpr()) {
            // 处理数组字面量 {"SELECT ...", "FROM ..."}
            return expr.asArrayInitializerExpr().getValues().stream()
                .filter(v -> v.isStringLiteralExpr() || v.isTextBlockLiteralExpr())
                .map(v -> extractStringFromExpression(v))
                .reduce((a, b) -> a + " " + b)
                .orElse(null);
        }
        return null;
    }

    /**
     * 根据注解名确定 SQL 类型
     */
    private String determineSqlType(String annotationName) {
        String name = annotationName.toLowerCase();
        if (name.contains("select")) return "SELECT";
        if (name.contains("insert")) return "INSERT";
        if (name.contains("update")) return "UPDATE";
        if (name.contains("delete")) return "DELETE";
        return "UNKNOWN";
    }
}
