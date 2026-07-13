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
        // P0-2: 文件名匹配（快速路径）或内容含 <mapper namespace=...>（兼容任意位置/命名的 XML）
        if (isMapperFile(asset.getRelativePath())) {
            return true;
        }
        // cachedContent 未预读时 fallback 到文件内容读取，保证 P0-2 在所有调度场景生效
        return hasMapperNamespace(asset.getCachedContent(), asset.getFile());
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
                || name.endsWith("Dao.xml")
                || name.endsWith("Repository.xml")
                || (name.contains("Mapper") && name.endsWith(".xml"))
                || (name.contains("mapper") && name.endsWith(".xml"))
                || (name.contains("Dao") && name.endsWith(".xml"))
                || (name.contains("Repository") && name.endsWith(".xml"));
    }

    /**
     * P0-2: 内容标记检查 —— 用 namespace 取代文件名兜底，兼容任意位置/命名的 MyBatis XML。
     */
    static boolean hasMapperNamespace(String content) {
        return hasMapperNamespace(content, (java.nio.file.Path) null);
    }

    /**
     * P0-2 修复：cachedContent 未预读时 fallback 到读文件全文（小文件，可直接读）。
     * <p>XML 文件相对短小且需要找多行才能命中 &lt;mapper namespace，建议直接读全文（不像 Java 需逐行）。</p>
     */
    static boolean hasMapperNamespace(String content, java.nio.file.Path path) {
        if (content != null) {
            return content.contains("<mapper namespace");
        }
        if (path == null || !java.nio.file.Files.isReadable(path)) {
            return false;
        }
        try {
            return java.nio.file.Files.readString(path).contains("<mapper namespace");
        } catch (java.io.IOException ignored) {
            return false;
        }
    }
}
