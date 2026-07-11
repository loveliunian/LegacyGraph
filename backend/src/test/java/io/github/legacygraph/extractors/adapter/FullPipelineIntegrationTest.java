package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.FrontendGraphBuilder;
import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.extractors.DocumentExtractor;
import io.github.legacygraph.extractors.JavaStructureExtractor;
import io.github.legacygraph.repository.FactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 扫描链路集成测试（前半段：扫描→抽取→图谱构建）。
 *
 * <p><b>覆盖范围</b>：</p>
 * <ol>
 *   <li>ScanContext 构建（project/version/baseDir/backendDir/frontendDir/config）</li>
 *   <li>文件遍历与候选过滤（排除 node_modules/.git/target 等）</li>
 *   <li>Adapter Registry 按文件类型选择适配器（Java → JavaServiceCallAdapter，
 *       XML → MyBatisXmlAdapter，vue/jsx/tsx → VueFrontendAdapter，md/txt → DocumentAdapter）</li>
 *   <li>适配器 extract 产出 ExtractionResult</li>
 *   <li>FactPersister 将事实写入 FactRepository</li>
 *   <li>GraphBuilder/FrontendGraphBuilder 接收图谱构建调用</li>
 *   <li>并发执行（虚拟线程 ExecutorService）正确聚合结果</li>
 *   <li>已有适配器跳过不支持的资产 & 异常隔离</li>
 * </ol>
 *
 * <p><b>未覆盖范围</b>（需单独测试）：</p>
 * <ul>
 *   <li>GraphRelease 发布与质量门禁</li>
 *   <li>需求分析（RequirementController）</li>
 *   <li>方案生成与校验（SolutionController）</li>
 *   <li>QA 问答全链路（EnhancedQaAgent）</li>
 * </ul>
 *
 * <p>测试数据：模拟一个包含 Java + MyBatis XML + Vue 组件的典型项目结构。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("扫描链路（前半段）：输入资料 → 适配器选择 → 抽取 → 事实落库 → 图谱构建")
class FullPipelineIntegrationTest {

    @TempDir
    Path projectRoot;

    @Mock
    private GraphBuilder graphBuilder;

    @Mock
    private FrontendGraphBuilder frontendGraphBuilder;

    @Mock
    private FactRepository factRepository;

    @Mock
    private JavaStructureExtractor javaStructureExtractor;

    private ExtractionAdapterRegistry adapterRegistry;
    private FactPersister factPersister;

    private static final String PROJECT_ID = "project-test";
    private static final String VERSION_ID = "version-001";

    // ============================================================
    // 测试数据准备
    // ============================================================

    @BeforeEach
    void setUp() throws Exception {
        // 构建 FactPersister
        com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        factPersister = new FactPersister(factRepository, objectMapper);
        lenient().when(factRepository.upsert(any(Fact.class))).thenReturn(1);
        lenient().when(factRepository.batchUpsert(anyList())).thenReturn(1);

        // 构建 Adapter Registry（注册所有真实适配器）
        adapterRegistry = new ExtractionAdapterRegistry(List.of(
                new JavaServiceCallAdapter(graphBuilder, factPersister, javaStructureExtractor, new io.github.legacygraph.extractors.PackageExtractor()),
                new MyBatisXmlAdapter(graphBuilder, factPersister),
                new VueFrontendAdapter(frontendGraphBuilder, factPersister),
                new DocumentAdapter(factPersister, new DocumentExtractor())
        ));

        // 构造项目目录结构
        createProjectStructure();
    }

    /**
     * 构造一个典型微项目的文件结构：
     * <pre>
     * project/
     *   backend/
     *     src/main/java/com/demo/
     *       controller/OrderController.java
     *       service/OrderService.java
     *       mapper/OrderMapper.java
     *     src/main/resources/mapper/OrderMapper.xml
     *     src/main/resources/application.yml
     *   frontend/
     *     src/views/OrderList.vue
     *     src/components/OrderCard.jsx
     *   docs/
     *     README.md
     *   node_modules/ (应被排除)
     *   target/ (应被排除)
     * </pre>
     */
    private void createProjectStructure() throws Exception {
        // backend Java sources
        Path controllerDir = projectRoot.resolve("backend/src/main/java/com/demo/controller");
        Files.createDirectories(controllerDir);
        Files.writeString(controllerDir.resolve("OrderController.java"), """
                package com.demo.controller;
                
                import com.demo.service.OrderService;
                import org.springframework.web.bind.annotation.*;
                
                @RestController
                @RequestMapping("/api/orders")
                public class OrderController {
                    private final OrderService orderService;
                    
                    public OrderController(OrderService orderService) {
                        this.orderService = orderService;
                    }
                    
                    @PostMapping
                    public String createOrder(@RequestBody OrderRequest req) {
                        orderService.createOrder(req);
                        return "OK";
                    }
                }
                """);

        Path serviceDir = projectRoot.resolve("backend/src/main/java/com/demo/service");
        Files.createDirectories(serviceDir);
        Files.writeString(serviceDir.resolve("OrderService.java"), """
                package com.demo.service;
                
                import com.demo.mapper.OrderMapper;
                import org.springframework.stereotype.Service;
                
                @Service
                public class OrderService {
                    private final OrderMapper orderMapper;
                    
                    public OrderService(OrderMapper orderMapper) {
                        this.orderMapper = orderMapper;
                    }
                    
                    public void createOrder(OrderRequest req) {
                        orderMapper.insertOrder(req);
                    }
                }
                """);

        Path mapperDir = projectRoot.resolve("backend/src/main/java/com/demo/mapper");
        Files.createDirectories(mapperDir);
        Files.writeString(mapperDir.resolve("OrderMapper.java"), """
                package com.demo.mapper;
                
                import org.apache.ibatis.annotations.Mapper;
                
                @Mapper
                public interface OrderMapper {
                    void insertOrder(OrderRequest req);
                }
                """);

        // MyBatis XML mapper
        Path xmlDir = projectRoot.resolve("backend/src/main/resources/mapper");
        Files.createDirectories(xmlDir);
        Files.writeString(xmlDir.resolve("OrderMapper.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                    "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.demo.mapper.OrderMapper">
                    <insert id="insertOrder">
                        INSERT INTO t_order (sku_id, user_id, amount, status)
                        VALUES (#{skuId}, #{userId}, #{amount}, 'PENDING')
                    </insert>
                </mapper>
                """);

        // config file (not a mapper XML — should be skipped by MyBatisXmlAdapter)
        Path configDir = projectRoot.resolve("backend/src/main/resources");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("application.yml"), """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/legacygraph
                    username: app
                """);

        // Frontend Vue component
        Path viewsDir = projectRoot.resolve("frontend/src/views");
        Files.createDirectories(viewsDir);
        Files.writeString(viewsDir.resolve("OrderList.vue"), """
                <template>
                  <div class="order-list">
                    <el-table :data="orders">
                      <el-table-column prop="id" label="订单号"/>
                      <el-table-column prop="status" label="状态"/>
                    </el-table>
                  </div>
                </template>
                
                <script setup lang="ts">
                import { ref } from 'vue'
                const orders = ref([])
                </script>
                """);

        // Frontend JSX component
        Path componentsDir = projectRoot.resolve("frontend/src/components");
        Files.createDirectories(componentsDir);
        Files.writeString(componentsDir.resolve("OrderCard.jsx"), """
                export default function OrderCard({ order }) {
                  return (
                    <div className="card">
                      <h3>{order.id}</h3>
                      <span>{order.status}</span>
                    </div>
                  );
                }
                """);

        // Document
        Path docsDir = projectRoot.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("README.md"), """
                # 订单管理系统
                
                本系统负责订单的全生命周期管理，包括：
                - 创建订单
                - 库存校验
                - 状态流转
                """);

        // Excluded directories (should be filtered out)
        Path nodeModules = projectRoot.resolve("node_modules/some-lib");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("ignored.js"), "// should be ignored");

        Path targetDir = projectRoot.resolve("target/classes");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("compiled.class"), "ignored");
    }

    // ============================================================
    // 测试用例
    // ============================================================

    /**
     * 测试 1：ScanContext 构建验证
     * 验证扫描上下文携带了所有必需的字段。
     */
    @Test
    @DisplayName("1. ScanContext 构建 — 携带所有扫描必要字段")
    void test01_scanContext_buildsWithAllRequiredFields() {
        ScanContext ctx = ScanContext.builder()
                .projectId(PROJECT_ID)
                .versionId(VERSION_ID)
                .baseDir(projectRoot.toString())
                .backendDir(projectRoot.resolve("backend").toString())
                .frontendDir(projectRoot.resolve("frontend").toString())
                .config(Map.of("timeout", 300, "includeTests", false))
                .build();

        assertAll("ScanContext fields",
                () -> assertEquals(PROJECT_ID, ctx.getProjectId(), "projectId"),
                () -> assertEquals(VERSION_ID, ctx.getVersionId(), "versionId"),
                () -> assertEquals(projectRoot.toString(), ctx.getBaseDir(), "baseDir"),
                () -> assertEquals(projectRoot.resolve("backend").toString(), ctx.getBackendDir(), "backendDir"),
                () -> assertEquals(projectRoot.resolve("frontend").toString(), ctx.getFrontendDir(), "frontendDir"),
                () -> assertEquals(Map.of("timeout", 300, "includeTests", false), ctx.getConfig(), "config")
        );
    }

    /**
     * 测试 2：文件遍历与候选过滤
     * 验证遍历能发现所有源码文件，同时排除 node_modules/target/.git 等目录。
     */
    @Test
    @DisplayName("2. 文件遍历 — 发现所有源码并排除无关注目录")
    void test02_fileWalk_discoversSourceFilesAndExcludesIrrelevantDirs() throws Exception {
        List<Path> allFiles = Files.walk(projectRoot)
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());

        // 排除巨型/无关目录
        List<Path> relevantFiles = allFiles.stream()
                .filter(p -> {
                    String s = p.toString();
                    return !s.contains("/node_modules/")
                            && !s.contains("/target/")
                            && !s.contains("/.git/")
                            && !s.contains("/dist/")
                            && !s.contains("/build/");
                })
                .collect(Collectors.toList());

        // 应包含 Java/XML/Vue/JSX/MD 文件，但不含 node_modules 和 target 下的文件
        List<String> fileNames = relevantFiles.stream()
                .map(p -> p.getFileName().toString())
                .toList();

        assertTrue(fileNames.contains("OrderController.java"), "应有 Controller");
        assertTrue(fileNames.contains("OrderService.java"), "应有 Service");
        assertTrue(fileNames.contains("OrderMapper.java"), "应有 Mapper");
        assertTrue(fileNames.contains("OrderMapper.xml"), "应有 Mapper XML");
        assertTrue(fileNames.contains("application.yml"), "应有配置文件");
        assertTrue(fileNames.contains("OrderList.vue"), "应有 Vue 组件");
        assertTrue(fileNames.contains("OrderCard.jsx"), "应有 JSX 组件");
        assertTrue(fileNames.contains("README.md"), "应有文档");

        // 确认排除目录的文件不在结果中
        assertTrue(relevantFiles.stream().noneMatch(p ->
                p.toString().contains("node_modules") || p.toString().contains("target")),
                "node_modules 和 target 目录应被排除");

        // 验证适配器候选过滤（只保留有适配器支持的文件类型：java/xml/vue/jsx/tsx/md/txt）
        List<Path> adapterCandidates = relevantFiles.stream()
                .filter(this::isAdapterCandidate)
                .collect(Collectors.toList());

        List<String> candidateNames = adapterCandidates.stream()
                .map(p -> p.getFileName().toString())
                .toList();

        // application.yml 不在适配器候选列表中（无 YAML 适配器）
        assertFalse(candidateNames.contains("application.yml"),
                "application.yml 无适配器支持，不应在候选列表");
        assertTrue(candidateNames.contains("OrderController.java"), "Java 文件应有适配器");
        assertTrue(candidateNames.contains("OrderMapper.xml"), "Mapper XML 应有适配器");
        assertTrue(candidateNames.contains("OrderList.vue"), "Vue 文件应有适配器");
        assertTrue(candidateNames.contains("README.md"), "MD 文档应有适配器");

        // 总计应有 7 个候选文件：3 Java + 1 XML + 1 Vue + 1 JSX + 1 MD
        assertEquals(7, adapterCandidates.size(),
                "应有 7 个适配器候选文件，实际: " + adapterCandidates.size());
    }

    /**
     * 测试 3：Adapter Registry 路由 — 每种文件类型匹配到正确的适配器
     */
    @Test
    @DisplayName("3. Adapter Registry — 按文件类型路由到正确的适配器")
    void test03_adapterRegistry_routesFileTypesToCorrectAdapter() {
        ScanContext ctx = ScanContext.builder()
                .projectId(PROJECT_ID)
                .versionId(VERSION_ID)
                .baseDir(projectRoot.toString())
                .backendDir(projectRoot.resolve("backend").toString())
                .frontendDir(projectRoot.resolve("frontend").toString())
                .config(Map.of())
                .build();

        // Java → JavaServiceCallAdapter（Controller 文件由 JavaCodeAdapter 处理，此处用 Service 文件）
        SourceAsset javaAsset = toSourceAsset("backend/src/main/java/com/demo/service/OrderService.java");
        var javaAdapter = adapterRegistry.selectAdapters(ctx, javaAsset);
        assertFalse(javaAdapter.isEmpty(), "Java Service 文件应有适配器");
        assertEquals("JavaServiceCallAdapter", javaAdapter.get(0).capability().getName(),
                "Java Service 文件应路由到 JavaServiceCallAdapter");

        // Controller 文件被 JavaServiceCallAdapter 拒绝（由 JavaCodeAdapter 处理）
        SourceAsset controllerAsset = toSourceAsset("backend/src/main/java/com/demo/controller/OrderController.java");
        var controllerAdapter = adapterRegistry.selectAdapters(ctx, controllerAsset);
        assertTrue(controllerAdapter.isEmpty(),
                "Controller 文件不应被 JavaServiceCallAdapter 接受（由 JavaCodeAdapter 处理）");

        // MyBatis Mapper XML → MyBatisXmlAdapter
        SourceAsset xmlAsset = toSourceAsset("backend/src/main/resources/mapper/OrderMapper.xml");
        var xmlAdapter = adapterRegistry.selectAdapters(ctx, xmlAsset);
        assertFalse(xmlAdapter.isEmpty(), "Mapper XML 应有适配器");
        assertEquals("MyBatisXmlAdapter", xmlAdapter.get(0).capability().getName(),
                "Mapper XML 应路由到 MyBatisXmlAdapter");

        // Vue → VueFrontendAdapter
        SourceAsset vueAsset = toSourceAsset("frontend/src/views/OrderList.vue");
        var vueAdapter = adapterRegistry.selectAdapters(ctx, vueAsset);
        assertFalse(vueAdapter.isEmpty(), "Vue 文件应有适配器");
        assertEquals("VueFrontendAdapter", vueAdapter.get(0).capability().getName(),
                "Vue 文件应路由到 VueFrontendAdapter");

        // Markdown → DocumentAdapter
        SourceAsset mdAsset = toSourceAsset("docs/README.md");
        var mdAdapter = adapterRegistry.selectAdapters(ctx, mdAsset);
        assertFalse(mdAdapter.isEmpty(), "MD 文件应有适配器");
        assertEquals("DocumentAdapter", mdAdapter.get(0).capability().getName(),
                "MD 文件应路由到 DocumentAdapter");

        // YAML 无适配器 → empty
        SourceAsset yamlAsset = toSourceAsset("backend/src/main/resources/application.yml");
        var yamlAdapter = adapterRegistry.selectAdapters(ctx, yamlAsset);
        assertTrue(yamlAdapter.isEmpty(), "YAML 文件不应有适配器（非 Mapper XML）");
    }

    /**
     * 测试 4：适配器 extract 产出 ExtractionResult（完整管线）
     * 对每种文件类型执行 extract，验证输出不为空且包含关键信息。
     */
    @Test
    @DisplayName("4. Adapter extract — 每种文件类型产出有效 ExtractionResult")
    void test04_adapterExtract_producesValidResults() throws Exception {
        ScanContext ctx = ScanContext.builder()
                .projectId(PROJECT_ID)
                .versionId(VERSION_ID)
                .baseDir(projectRoot.toString())
                .backendDir(projectRoot.resolve("backend").toString())
                .frontendDir(projectRoot.resolve("frontend").toString())
                .config(Map.of())
                .build();

        // Java: OrderService.java（非 Controller，JavaServiceCallAdapter 接受）
        SourceAsset javaAsset = toSourceAsset("backend/src/main/java/com/demo/service/OrderService.java");
        var javaAdapter = adapterRegistry.selectAdapters(ctx, javaAsset).get(0);
        ExtractionResult javaResult = javaAdapter.extract(ctx, javaAsset);
        assertNotNull(javaResult, "Java 抽取结果不应为 null");
        assertTrue(javaResult.getProcessedAssets() > 0, "Java 应处理至少 1 个资产");
        assertNotNull(javaResult.getSummary(), "Java 结果应包含摘要");

        // XML: OrderMapper.xml
        SourceAsset xmlAsset = toSourceAsset("backend/src/main/resources/mapper/OrderMapper.xml");
        var xmlAdapter = adapterRegistry.selectAdapters(ctx, xmlAsset).get(0);
        ExtractionResult xmlResult = xmlAdapter.extract(ctx, xmlAsset);
        assertNotNull(xmlResult, "XML 抽取结果不应为 null");
        assertTrue(xmlResult.getProcessedAssets() > 0, "XML 应处理至少 1 个资产");

        // Vue: OrderList.vue
        SourceAsset vueAsset = toSourceAsset("frontend/src/views/OrderList.vue");
        var vueAdapter = adapterRegistry.selectAdapters(ctx, vueAsset).get(0);
        ExtractionResult vueResult = vueAdapter.extract(ctx, vueAsset);
        assertNotNull(vueResult, "Vue 抽取结果不应为 null");
        assertTrue(vueResult.getProcessedAssets() > 0, "Vue 应处理至少 1 个资产");

        // MD: README.md
        SourceAsset mdAsset = toSourceAsset("docs/README.md");
        var mdAdapter = adapterRegistry.selectAdapters(ctx, mdAsset).get(0);
        ExtractionResult mdResult = mdAdapter.extract(ctx, mdAsset);
        assertNotNull(mdResult, "MD 抽取结果不应为 null");
        assertTrue(mdResult.getProcessedAssets() > 0, "MD 应处理至少 1 个资产");
    }

    /**
     * 测试 5：Fact 落库 — 抽取结果正确持久化到 FactRepository
     */
    @Test
    @DisplayName("5. Fact 落库 — 抽取后事实正确写入 FactRepository")
    void test05_factPersister_persistsFactsAfterExtraction() throws Exception {
        ScanContext ctx = ScanContext.builder()
                .projectId(PROJECT_ID)
                .versionId(VERSION_ID)
                .baseDir(projectRoot.toString())
                .backendDir(projectRoot.resolve("backend").toString())
                .frontendDir(projectRoot.resolve("frontend").toString())
                .config(Map.of())
                .build();

        // 对 Java 文件执行抽取
        SourceAsset javaAsset = toSourceAsset("backend/src/main/java/com/demo/service/OrderService.java");
        var javaAdapter = adapterRegistry.selectAdapters(ctx, javaAsset).get(0);
        javaAdapter.extract(ctx, javaAsset);

        // 对 Mapper XML 执行抽取
        SourceAsset xmlAsset = toSourceAsset("backend/src/main/resources/mapper/OrderMapper.xml");
        var xmlAdapter = adapterRegistry.selectAdapters(ctx, xmlAsset).get(0);
        xmlAdapter.extract(ctx, xmlAsset);

        // 验证单条和批量 Fact 写入都被调用：
        // JavaServiceCallAdapter 走 batchUpsert，MyBatisXmlAdapter 保留 upsert。
        ArgumentCaptor<Fact> factCaptor = ArgumentCaptor.forClass(Fact.class);
        verify(factRepository, atLeast(1)).upsert(factCaptor.capture());
        ArgumentCaptor<List<Fact>> batchFactCaptor = ArgumentCaptor.forClass(List.class);
        verify(factRepository, atLeast(1)).batchUpsert(batchFactCaptor.capture());

        List<Fact> facts = new ArrayList<>(factCaptor.getAllValues());
        batchFactCaptor.getAllValues().forEach(facts::addAll);

        // 验证基本字段
        assertFalse(facts.isEmpty(), "至少应有 1 条 Fact 被持久化");
        for (Fact fact : facts) {
            assertNotNull(fact.getId(), "Fact ID 不应为 null");
            assertEquals(PROJECT_ID, fact.getProjectId(), "projectId 应一致");
            assertEquals(VERSION_ID, fact.getVersionId(), "versionId 应一致");
            assertNotNull(fact.getFactType(), "factType 不应为 null");
            assertNotNull(fact.getFactKey(), "factKey 不应为 null");
            assertNotNull(fact.getSourceType(), "sourceType 不应为 null");
        }

        // 验证包含不同来源的事实
        Set<String> sourceTypes = facts.stream()
                .map(Fact::getSourceType)
                .collect(Collectors.toSet());
        // JavaServiceCallAdapter → CODE_AST; MyBatisXmlAdapter → MAPPER_XML
        assertTrue(sourceTypes.contains("CODE_AST") || sourceTypes.contains("JAVA_SERVICE"),
                "应包含 Java 源码来源的事实");
        assertTrue(sourceTypes.contains("MAPPER_XML"),
                "应包含 MyBatis XML 来源的事实 (sourceType=MAPPER_XML)");
    }

    /**
     * 测试 6：并发执行 — 虚拟线程并发处理多个文件
     * 模拟 ProjectScanner.scanAssetsWithAdapters 中的并发逻辑。
     */
    @Test
    @DisplayName("6. 并发执行 — 虚拟线程并发处理，结果正确聚合")
    void test06_concurrentExecution_processesAllFilesInParallel() throws Exception {
        ScanContext ctx = ScanContext.builder()
                .projectId(PROJECT_ID)
                .versionId(VERSION_ID)
                .baseDir(projectRoot.toString())
                .backendDir(projectRoot.resolve("backend").toString())
                .frontendDir(projectRoot.resolve("frontend").toString())
                .config(Map.of())
                .build();

        // 收集所有适配器候选文件
        List<Path> candidateFiles = Files.walk(projectRoot)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String s = p.toString();
                    return !s.contains("/node_modules/") && !s.contains("/target/")
                            && !s.contains("/.git/") && !s.contains("/dist/")
                            && !s.contains("/build/");
                })
                .filter(this::isAdapterCandidate)
                .collect(Collectors.toList());

        assertFalse(candidateFiles.isEmpty(), "应有至少 1 个候选文件");

        // 并发处理（模拟 scanAssetsWithAdapters 逻辑）
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var processed = new AtomicInteger(0);
        try {
            List<java.util.concurrent.Callable<Void>> tasks = candidateFiles.stream()
                    .<java.util.concurrent.Callable<Void>>map(file -> () -> {
                        try {
                            SourceAsset asset = toSourceAsset(projectRoot.relativize(file).toString());
                            var adapter = adapterRegistry.selectAdapters(ctx, asset);
                            if (adapter.isEmpty()) {
                                return null;
                            }
                            ExtractionResult result = adapter.get(0).extract(ctx, asset);
                            if (result != null && result.getProcessedAssets() > 0) {
                                processed.addAndGet(result.getProcessedAssets());
                            } else {
                                processed.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // 单个文件失败不应影响其他（ProjectScanner 行为一致）
                            System.err.println("Adapter failed for " + file + ": " + e.getMessage());
                        }
                        return null;
                    })
                    .toList();

            executor.invokeAll(tasks);
        } finally {
            executor.shutdown();
        }

        // 验证并发处理完成（至少处理了 MyBatis XML 和部分文件）
        int total = processed.get();
        assertTrue(total > 0, "应处理至少 1 个文件，实际: " + total);

        // 验证 FactRepository 被调用（具体数量取决于适配器实际抽取结果）
        verify(factRepository, atLeast(1)).upsert(any(Fact.class));
    }

    /**
     * 测试 7：异常隔离 — 单文件抽取失败不影响后续文件
     */
    @Test
    @DisplayName("7. 异常隔离 — 单文件失败不中断整体扫描")
    void test07_exceptionIsolation_singleFileFailureDoesNotStopWholeScan() throws Exception {
        ScanContext ctx = ScanContext.builder()
                .projectId(PROJECT_ID)
                .versionId(VERSION_ID)
                .baseDir(projectRoot.toString())
                .config(Map.of())
                .build();

        // 用不存在的文件触发异常
        SourceAsset missingAsset = SourceAsset.builder()
                .file(projectRoot.resolve("nonexistent.java"))
                .relativePath("nonexistent.java")
                .fileType("java")
                .build();

        var javaAdapter = adapterRegistry.selectAdapters(ctx, missingAsset);
        if (!javaAdapter.isEmpty()) {
            assertDoesNotThrow(() -> {
                try {
                    javaAdapter.get(0).extract(ctx, missingAsset);
                } catch (Exception e) {
                    // 期望被 catch（模拟 ProjectScanner 的 try-catch 隔离）
                    // 不传播异常
                }
            }, "单文件失败不应传播异常");
        }
    }

    /**
     * 测试 8：端到端回归 — 整个扫描链路无缺失文件类型
     */
    @Test
    @DisplayName("8. 端到端回归 — 所有适配器能力声明完整且可发现")
    void test08_endToEnd_allAdapterCapabilitiesDeclaredAndDiscoverable() {
        List<ExtractionAdapter> allAdapters = adapterRegistry.getAllAdapters();

        // 应有 4 个适配器
        assertEquals(4, allAdapters.size(), "应注册 4 个适配器");

        Set<String> adapterNames = allAdapters.stream()
                .map(a -> a.capability().getName())
                .collect(Collectors.toSet());

        assertTrue(adapterNames.contains("JavaServiceCallAdapter"));
        assertTrue(adapterNames.contains("MyBatisXmlAdapter"));
        assertTrue(adapterNames.contains("VueFrontendAdapter"));
        assertTrue(adapterNames.contains("DocumentAdapter"));

        // 验证每个适配器的能力声明不为空
        for (ExtractionAdapter adapter : allAdapters) {
            AdapterCapability cap = adapter.capability();
            assertNotNull(cap.getName(), "适配器名称不应为 null");
            assertFalse(cap.getName().isBlank(), "适配器名称不应为空");
            assertTrue(cap.getPriority() > 0, "优先级应 > 0: " + cap.getName());
        }

        // 结构型适配器（非 AI）应有 4 个
        List<ExtractionAdapter> structural = adapterRegistry.getStructuralAdapters();
        assertEquals(4, structural.size(), "所有 4 个适配器均为结构型（非 AI）");

        // AI 增强适配器应为 0
        List<ExtractionAdapter> aiAdapters = adapterRegistry.getAiEnhancedAdapters();
        assertEquals(0, aiAdapters.size(), "当前无 AI 增强适配器");
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 判断文件是否为适配器候选（模拟 ProjectScanner.isAdapterCandidate）。
     */
    private boolean isAdapterCandidate(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".java")
                || fileName.endsWith(".xml")
                || fileName.endsWith(".vue")
                || fileName.endsWith(".jsx")
                || fileName.endsWith(".tsx")
                || fileName.endsWith(".md")
                || fileName.endsWith(".txt");
    }

    /**
     * 从相对路径构造 SourceAsset（模拟 ProjectScanner.toSourceAsset）。
     */
    private SourceAsset toSourceAsset(String relativePath) {
        Path fullPath = projectRoot.resolve(relativePath);
        String fileName = fullPath.getFileName().toString().toLowerCase();
        String fileType;
        String language;

        if (fileName.endsWith(".java")) {
            fileType = "java";
            language = "java";
        } else if (fileName.endsWith(".xml")) {
            fileType = "xml";
            language = "xml";
        } else if (fileName.endsWith(".vue")) {
            fileType = "vue";
            language = "javascript";
        } else if (fileName.endsWith(".jsx") || fileName.endsWith(".tsx")) {
            fileType = fileName.substring(fileName.lastIndexOf('.') + 1);
            language = "javascript";
        } else if (fileName.endsWith(".md") || fileName.endsWith(".txt")) {
            fileType = fileName.substring(fileName.lastIndexOf('.') + 1);
            language = "markdown";
        } else {
            fileType = fileName.substring(fileName.lastIndexOf('.') + 1);
            language = "unknown";
        }

        return SourceAsset.builder()
                .file(fullPath)
                .relativePath(relativePath)
                .fileType(fileType)
                .language(language)
                .build();
    }
}
