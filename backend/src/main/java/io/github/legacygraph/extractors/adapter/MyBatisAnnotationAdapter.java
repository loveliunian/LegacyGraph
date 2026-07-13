package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.extractors.MyBatisAnnotationExtractor;
import io.github.legacygraph.model.MapperSqlFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * MyBatis 注解方式 Mapper 适配器 - 从 Java 接口中提取 SQL 语句事实并写图
 */
@Slf4j
@Component
public class MyBatisAnnotationAdapter implements ExtractionAdapter {

    private static final Set<String> JAVA_EXTENSIONS = Set.of("java");

    private final GraphBuilder graphBuilder;
    private final FactPersister factPersister;
    private final MyBatisAnnotationExtractor extractor = new MyBatisAnnotationExtractor();

    public MyBatisAnnotationAdapter(GraphBuilder graphBuilder, FactPersister factPersister) {
        this.graphBuilder = graphBuilder;
        this.factPersister = factPersister;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        if (asset.getFileType() == null || !JAVA_EXTENSIONS.contains(asset.getFileType().toLowerCase())) {
            return false;
        }
        // P0-2: 文件名匹配（快速路径）或内容含 MyBatis 标记（兼容任意命名的 Mapper 接口）
        if (isMapperInterface(asset.getRelativePath())) {
            return true;
        }
        // cachedContent 可能为 null（未预读），fallback 到文件逐行扫描保持 supports() 在所有调度场景生效
        return hasMyBatisMarker(asset.getCachedContent(), asset.getFile());
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        MapperSqlFact mapperFact = extractor.extractFromFile(asset.getFile().toFile());
        if (mapperFact == null || mapperFact.getNamespace() == null) {
            return ExtractionResult.builder().processedAssets(1).summary("MyBatis annotation: no SQL found").build();
        }

        // G1: 标记来源为 MYBATIS_ANNOTATION，让 GraphBuilder 正确区分注解式与 XML
        mapperFact.setSourceType(SourceType.MYBATIS_ANNOTATION.name());

        factPersister.saveFact(context.getProjectId(), context.getVersionId(),
                "MAPPER_ANNOTATION", "MAPPER", mapperFact.getNamespace(), mapperFact.getNamespace(),
                asset.getRelativePath(), null, null, mapperFact, BigDecimal.ONE, "EXTRACTED");
        
        graphBuilder.buildMapperSqlGraph(context.getProjectId(), context.getVersionId(), mapperFact);
        
        return ExtractionResult.builder()
                .processedAssets(1)
                .summary("MyBatis annotation: " + mapperFact.getNamespace() + 
                        " (" + mapperFact.getStatements().size() + " SQL)")
                .build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("MyBatisAnnotationAdapter")
                .languages(Set.of("java"))
                .frameworks(Set.of("mybatis"))
                .fileTypes(JAVA_EXTENSIONS)
                .aiEnhanced(false)
                .priority(31) // 略低于 XML 适配器
                .build();
    }

    static boolean isMapperInterface(String relativePath) {
        if (relativePath == null) return false;
        String name = relativePath;
        int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        if (slash >= 0) name = relativePath.substring(slash + 1);
        return name.endsWith("Mapper.java")
                || name.endsWith("Dao.java")
                || name.endsWith("Repository.java")
                || name.endsWith("DAO.java")
                || name.endsWith("DaoImpl.java")
                || (name.contains("Mapper") && name.endsWith(".java"))
                || (name.contains("Dao") && name.endsWith(".java"))
                || (name.contains("Repository") && name.endsWith(".java"));
    }

    /**
     * P0-2: 轻量内容标记检查 —— 兼容 MyBatis-Plus 时代任意命名的 Mapper 接口。
     * <p>避免对每个 Java 文件做完整 AST 解析；仅在 supports() 阶段做字符串扫描，
     * 命中 MyBatis 注解或 BaseMapper 继承标记时才进入 extract() 做精确解析。</p>
     */
    static boolean hasMyBatisMarker(String content) {
        return hasMyBatisMarker(content, (java.nio.file.Path) null);
    }

    /**
     * P0-2 修复：cachedContent 未预读时 fallback 到逐行读文件。
     * <p>对照 JavaCodeAdapter / JavaServiceCallAdapter 的实现，避免 hasMyBatisMarker(null) 直接返回 false
     * 导致 P0-2 修复在 ProjectScanner 没把 content 写进 SourceAsset 的调度场景失效。</p>
     */
    static boolean hasMyBatisMarker(String content, java.nio.file.Path path) {
        if (content != null) {
            return content.contains("@Select") || content.contains("@Insert")
                    || content.contains("@Update") || content.contains("@Delete")
                    || content.contains("BaseMapper") || content.contains("extends Mapper")
                    || content.contains("JoinMapper");
        }
        if (path == null || !java.nio.file.Files.isReadable(path)) {
            return false;
        }
        try (var lines = java.nio.file.Files.lines(path)) {
            return lines.anyMatch(l ->
                    l.contains("@Select") || l.contains("@Insert")
                            || l.contains("@Update") || l.contains("@Delete")
                            || l.contains("BaseMapper") || l.contains("extends Mapper")
                            || l.contains("JoinMapper"));
        } catch (java.io.IOException ignored) {
            return false;
        }
    }
}
