package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.model.MapperSqlFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.util.Set;

/**
 * MyBatis Mapper XML 适配器（doc 3.2）— 抽取 SQL 语句事实并写图。
 */
@Slf4j
@Component
public class MyBatisXmlAdapter implements ExtractionAdapter {

    private static final Set<String> XML_EXTENSIONS = Set.of("xml");

    private final GraphBuilder graphBuilder;
    private final FactPersister factPersister;
    private final MyBatisXmlExtractor extractor = new MyBatisXmlExtractor();

    public MyBatisXmlAdapter(GraphBuilder graphBuilder, FactPersister factPersister) {
        this.graphBuilder = graphBuilder;
        this.factPersister = factPersister;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        if (asset.getFileType() == null || !XML_EXTENSIONS.contains(asset.getFileType().toLowerCase())) {
            return false;
        }
        return isMapperFile(asset.getRelativePath());
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        File xmlFile = asset.getFile().toFile();
        MapperSqlFact mapperFact = extractor.extractFromFile(xmlFile);
        if (mapperFact == null || mapperFact.getNamespace() == null) {
            return ExtractionResult.builder().processedAssets(1).summary("MyBatis XML: no namespace").build();
        }
        factPersister.saveFact(context.getProjectId(), context.getVersionId(),
                "MAPPER_XML", "MAPPER", mapperFact.getNamespace(), mapperFact.getNamespace(),
                asset.getRelativePath(), null, null, mapperFact, BigDecimal.ONE, "EXTRACTED");
        graphBuilder.buildMapperSqlGraph(context.getProjectId(), context.getVersionId(), mapperFact);
        return ExtractionResult.builder()
                .processedAssets(1)
                .summary("MyBatis XML: " + mapperFact.getNamespace())
                .build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("MyBatisXmlAdapter")
                .languages(Set.of("xml"))
                .frameworks(Set.of("mybatis"))
                .fileTypes(XML_EXTENSIONS)
                .aiEnhanced(false)
                .priority(30)
                .build();
    }

    static boolean isMapperFile(String relativePath) {
        if (relativePath == null) return false;
        String name = relativePath;
        int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        if (slash >= 0) name = relativePath.substring(slash + 1);
        return name.endsWith("Mapper.xml")
                || (name.contains("Mapper") && name.endsWith(".xml"))
                || (name.contains("mapper") && name.endsWith(".xml"));
    }
}
