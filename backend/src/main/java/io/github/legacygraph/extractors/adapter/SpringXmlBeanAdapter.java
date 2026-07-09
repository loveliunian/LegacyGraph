package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.ServiceCallExtractor;
import io.github.legacygraph.extractors.SpringXmlBeanExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Spring XML Bean 配置适配器（doc 3.2）—
 * 解析 Spring XML 配置文件中的 {@code <bean>} 依赖注入关系，转为 SERVICE_CALL 事实写图。
 */
@Slf4j
@Component
public class SpringXmlBeanAdapter implements ExtractionAdapter {

    private static final Set<String> XML_EXTENSIONS = Set.of("xml");

    private final GraphBuilder graphBuilder;
    private final FactPersister factPersister;
    private final SpringXmlBeanExtractor extractor = new SpringXmlBeanExtractor();

    public SpringXmlBeanAdapter(GraphBuilder graphBuilder, FactPersister factPersister) {
        this.graphBuilder = graphBuilder;
        this.factPersister = factPersister;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        if (asset.getFileType() == null || !XML_EXTENSIONS.contains(asset.getFileType().toLowerCase())) {
            return false;
        }
        if (asset.getFile() == null || !Files.isReadable(asset.getFile())) {
            return false;
        }
        // 文件扩展名是 xml，且内容包含 <bean 或 <beans 标签即视为 Spring XML 配置。
        // MyBatis Mapper 文件根标签为 <mapper>，不含 <bean/<beans，因此不会与此处冲突。
        return isSpringXmlFile(asset.getFile().toFile());
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        File xmlFile = asset.getFile().toFile();
        List<SpringXmlBeanExtractor.SpringBeanDependency> deps = extractor.extractFromFile(xmlFile);
        if (deps.isEmpty()) {
            return ExtractionResult.builder()
                    .processedAssets(1)
                    .summary("Spring XML: no bean dependencies")
                    .build();
        }

        List<ServiceCallExtractor.CallRelation> calls = new ArrayList<>(deps.size());
        for (SpringXmlBeanExtractor.SpringBeanDependency dep : deps) {
            // callerClass = bean 的 class；callerMethod = null（注入依赖，非方法调用）；
            // calledMethod = "injects:" + refBeanClass（与 ServiceCallExtractor 一致）。
            // refBeanClass 为 null（ref 目标不在当前文件）时回退到 refBeanId，保留可追溯的引用信息。
            String refClass = dep.getRefBeanClass();
            String injectTarget = refClass != null ? refClass : dep.getRefBeanId();
            ServiceCallExtractor.CallRelation rel =
                    new ServiceCallExtractor.CallRelation(dep.getBeanClass(), null, "injects:" + injectTarget);
            rel.setTargetClass(refClass);
            rel.setSourcePath(asset.getRelativePath());
            calls.add(rel);
        }

        factPersister.saveFacts(calls.stream()
                .map(call -> FactPersister.FactDraft.builder()
                        .projectId(context.getProjectId())
                        .versionId(context.getVersionId())
                        .sourceType("SPRING_XML")
                        .factType("SERVICE_CALL")
                        .factKey(call.getCallerClass() + "." + call.getCalledMethod())
                        .factName(call.getCallerClass() + " -> " + call.getTargetClass() + "." + call.getCalledMethod())
                        .sourcePath(asset.getRelativePath())
                        .startLine(call.getLineNumber())
                        .endLine(call.getLineNumber())
                        .data(call)
                        .confidence(BigDecimal.ONE)
                        .status("EXTRACTED")
                        .build())
                .toList());
        graphBuilder.buildServiceCallGraph(context.getProjectId(), context.getVersionId(), calls);

        return ExtractionResult.builder()
                .processedAssets(1)
                .edgeCount(calls.size())
                .evidenceCount(calls.size())
                .summary("Spring XML: " + calls.size() + " bean dependencies")
                .build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("SpringXmlBeanAdapter")
                .languages(Set.of("xml"))
                .frameworks(Set.of("spring", "spring-boot"))
                .fileTypes(XML_EXTENSIONS)
                .aiEnhanced(false)
                .priority(35)
                .build();
    }

    /**
     * 判断是否是 Spring XML 配置文件：扩展名 xml，且内容包含 {@code <bean} 或 {@code <beans} 标签。
     */
    static boolean isSpringXmlFile(File file) {
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return content.contains("<bean") || content.contains("<beans");
        } catch (Exception e) {
            log.debug("Failed to read file for Spring XML check: {}", file, e);
            return false;
        }
    }
}
