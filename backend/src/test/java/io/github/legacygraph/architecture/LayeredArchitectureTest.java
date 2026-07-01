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

import java.util.Arrays;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
