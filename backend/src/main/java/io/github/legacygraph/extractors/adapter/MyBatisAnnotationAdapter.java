package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
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
        return isMapperInterface(asset.getRelativePath());
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        MapperSqlFact mapperFact = extractor.extractFromFile(asset.getFile().toFile());
        if (mapperFact == null || mapperFact.getNamespace() == null) {
            return ExtractionResult.builder().processedAssets(1).summary("MyBatis annotation: no SQL found").build();
        }
        
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
                || (name.contains("Mapper") && name.endsWith(".java"))
                || (name.contains("Dao") && name.endsWith(".java"))
                || (name.contains("Repository") && name.endsWith(".java"));
    }
}
