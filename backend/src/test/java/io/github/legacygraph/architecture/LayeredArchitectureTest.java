package io.github.legacygraph.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构分层规则测试（见 doc/前后端架构整体分析与改进建议.md B-H1）。
 * <p>确保 controller 不直接依赖 repository/dao 层，防止越层调用。</p>
 */
class LayeredArchitectureTest {

    private static final Set<String> REPOSITORY_BASELINE_CONTROLLERS = Set.of(
            "AuthController", "FactController", "ReviewController", "SourceController", "TestCaseController");

    private static final Set<String> DAO_BASELINE_CONTROLLERS = Set.of(
            "FactController", "GraphQueryController", "LlmAgentController", "TestCaseController");

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.legacygraph");
    }

    /**
     * 架构测试必须真实导入生产代码，避免 ArchUnit 与 Java class file 版本不兼容时规则假绿。
     */
    @Test
    void architectureImportShouldLoadProductionClasses() {
        assertTrue(classes.size() > 0, "ArchUnit must import production classes");
    }

    /**
     * Controller 不得新增直接依赖 Repository 层。
     * <p>以下白名单为文档 B-H1 已记录的历史遗留 Controller，待拆分 Service 后逐个移除。</p>
     */
    @Test
    void controllersShouldNotDependOnRepositories() {
        ArchRule rule = classes()
                .that(controllerOutsideBaseline(REPOSITORY_BASELINE_CONTROLLERS))
                .should().onlyDependOnClassesThat()
                .resideOutsideOfPackage("..repository..");
        rule.check(classes);
    }

    /**
     * Controller 不得新增直接依赖 DAO 层（Neo4jGraphDao）。
     * <p>以下白名单为文档 B-H1 已记录的历史遗留 Controller，待拆分 Service 后逐个移除。</p>
     */
    @Test
    void controllersShouldNotDependOnDao() {
        ArchRule rule = classes()
                .that(controllerOutsideBaseline(DAO_BASELINE_CONTROLLERS))
                .should().onlyDependOnClassesThat()
                .resideOutsideOfPackage("..dao..");
        rule.check(classes);
    }

    /**
     * Service 层不得依赖 Controller 层（单向依赖）。
     */
    @Test
    void servicesShouldNotDependOnControllers() {
        ArchRule rule = classes()
                .that().resideInAPackage("..service..")
                .should().onlyDependOnClassesThat()
                .resideOutsideOfPackage("..controller..");
        rule.check(classes);
    }

    /**
     * Builder/Task 层不得依赖 Controller 层。
     */
    @Test
    void buildersShouldNotDependOnControllers() {
        ArchRule rule = classes()
                .that().resideInAnyPackage("..builder..", "..task..")
                .should().onlyDependOnClassesThat()
                .resideOutsideOfPackage("..controller..");
        rule.check(classes);
    }

    /**
     * LLM 网络调用入口不得开启方法级事务，避免长 IO 占用数据库连接（B-H6）。
     */
    @Test
    void llmGatewayCallsShouldNotOpenDatabaseTransactions() {
        boolean hasTransactionalCallWithTemplate = Arrays.stream(LlmGateway.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("callWithTemplate"))
                .anyMatch(method -> method.isAnnotationPresent(Transactional.class));

        assertFalse(hasTransactionalCallWithTemplate,
                "LlmGateway.callWithTemplate must not be annotated with @Transactional");
    }

    // ==================== Phase 1-3: 架构门禁新增规则 ====================

    /**
     * task 包不得直接依赖具体 Extractor（如 JavaControllerExtractor 等），
     * 只依赖 extractors.adapter 包。确保扫描编排通过 Adapter seam 而非硬编码抽取器。
     */
    @Test
    void taskPackageShouldNotDependOnConcreteExtractors() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..task..")
                .should().dependOnClassesThat(new DescribedPredicate<>("concrete extractor outside adapter") {
                    @Override
                    public boolean test(JavaClass input) {
                        return input.getPackageName().contains(".extractors")
                                && !input.getPackageName().contains(".extractors.adapter");
                    }
                });
        rule.check(classes);
    }

    /**
     * service 包不得直接持有 org.neo4j.driver.Driver。
     * 图谱查询应统一走 Neo4jGraphDao 或 Read Model，不直接拼 Cypher。
     */
    @Test
    void servicePackageShouldNotDependOnNeo4jDriver() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat().areAssignableTo(org.neo4j.driver.Driver.class);
        rule.check(classes);
    }

    /**
     * Agent 只产出结构化结果，不直接写 Repository；持久化统一下沉到 Service 层。
     */
    @Test
    void agentsShouldNotDependOnRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..agent..")
                .should().dependOnClassesThat().resideInAPackage("..repository..");
        rule.check(classes);
    }

    /**
     * 高风险 Agent 的 AgentEnvelope 入口不允许退回默认四参 callWithTemplate。
     * 兼容旧 API 可以保留四参调用，但 Envelope 入口必须通过 LlmGateway.callWithEnvelope。
     */
    @Test
    void highRiskAgentEnvelopeEntriesShouldUseEvidenceGateway() throws Exception {
        String sources = readAgentSources(
                "PatchPlanAgent.java",
                "ChangeImpactAgent.java",
                "FeatureMappingAgent.java",
                "GraphMergeAgent.java",
                "TestFailureAnalysisAgent.java",
                "GapFinderAgent.java");

        assertFalse(sources.contains("callWithTemplate(envelope.getProjectId()"),
                "AgentEnvelope entry must call callWithEnvelope, not default callWithTemplate");
        assertFalse(sources.contains("callWithTemplate(env.getProjectId()"),
                "AgentEnvelope entry must call callWithEnvelope, not default callWithTemplate");
        assertFalse(sources.contains("return mapFeatures(env.getInput())"),
                "FeatureMappingAgent envelope entry must not delegate to legacy default-contract API");
        assertFalse(sources.contains("return analyze(env.getInput())"),
                "TestFailureAnalysisAgent envelope entry must not delegate to legacy default-contract API");
    }

    /**
     * GapFinderAgent 必须使用 AgentEnvelope + callWithEnvelope（Phase M1-M4 架构门禁）。
     */
    @Test
    void gapFinderAgentMustUseAgentEnvelope() throws Exception {
        Path agentFile = Path.of("src/main/java/io/github/legacygraph/agent/GapFinderAgent.java");
        if (!Files.exists(agentFile)) {
            agentFile = Path.of("backend/src/main/java/io/github/legacygraph/agent/GapFinderAgent.java");
        }
        if (!Files.exists(agentFile)) {
            return; // skip if file not found
        }
        String source = Files.readString(agentFile);
        assertTrue(source.contains("callWithEnvelope"),
                "GapFinderAgent must use callWithEnvelope for LLM calls");
        assertTrue(source.contains("AgentEnvelope"),
                "GapFinderAgent must reference AgentEnvelope");
        assertFalse(source.contains("callWithTemplate(projectId"),
                "GapFinderAgent must not use default callWithTemplate bypassing envelope");
    }

    private static String readAgentSources(String... fileNames) throws Exception {
        Path agentDir = Path.of("src/main/java/io/github/legacygraph/agent");
        if (!Files.exists(agentDir)) {
            agentDir = Path.of("backend/src/main/java/io/github/legacygraph/agent");
        }
        StringBuilder source = new StringBuilder();
        try (Stream<Path> paths = Files.list(agentDir)) {
            Set<String> targets = Set.of(fileNames);
            for (Path path : paths.filter(p -> targets.contains(p.getFileName().toString())).toList()) {
                source.append(Files.readString(path)).append('\n');
            }
        }
        return source.toString();
    }

    private static DescribedPredicate<JavaClass> controllerOutsideBaseline(Set<String> baselineControllerNames) {
        return new DescribedPredicate<>("controller outside known architecture baseline") {
            @Override
            public boolean test(JavaClass input) {
                return input.getPackageName().contains(".controller")
                        && !baselineControllerNames.contains(input.getSimpleName());
            }
        };
    }
}
