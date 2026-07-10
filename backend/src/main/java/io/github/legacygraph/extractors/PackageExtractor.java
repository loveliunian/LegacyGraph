package io.github.legacygraph.extractors;

import lombok.Data;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码包依赖提取器 — 从 JavaStructureExtractor 的类结构信息中推导包依赖关系。
 *
 * <p>职责：
 * <ul>
 *   <li>收集所有出现的包名</li>
 *   <li>推导 Class → Package 归属关系（BELONGS_TO 候选）</li>
 *   <li>推导 Package → Package 依赖关系（DEPENDS_ON 候选），过滤框架包、去重自环</li>
 * </ul>
 *
 * <p>与 {@link JavaStructureExtractor} 互补：后者解析 AST 抽取类结构（含 packageName/imports），
 * 本类在其输出基础上做包级聚合，产出 {@link PackageGraphFact} 供 GraphBuilder 建图。</p>
 */
@Slf4j
@Component
public class PackageExtractor {

    /** 构建 DEPENDS_ON 边时需排除的框架包前缀 */
    private static final List<String> FRAMEWORK_PACKAGE_PREFIXES = List.of(
            "java", "javax", "jakarta",
            "org.springframework", "com.fasterxml", "lombok",
            "org.slf4j", "org.apache.commons", "org.apache.ibatis",
            "org.mybatis", "sun", "com.sun"
    );

    /**
     * 从类结构信息中提取包依赖图事实。
     *
     * @param classes JavaStructureExtractor 抽取的类结构列表
     * @return 包图事实（包集合 + BELONGS_TO 候选 + DEPENDS_ON 候选）
     */
    public PackageGraphFact extract(List<JavaStructureExtractor.JavaClassInfo> classes) {
        if (classes == null || classes.isEmpty()) {
            return new PackageGraphFact(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        Set<String> packageNames = new LinkedHashSet<>();
        List<BelongsToClaim> belongsTo = new ArrayList<>();
        Set<String> belongsToKeys = new LinkedHashSet<>();
        List<DependsOnClaim> dependsOn = new ArrayList<>();
        Set<String> dependsOnKeys = new LinkedHashSet<>();

        for (JavaStructureExtractor.JavaClassInfo classInfo : classes) {
            String pkgName = classInfo.getPackageName();
            if (pkgName == null || pkgName.isBlank()) {
                continue;
            }
            packageNames.add(pkgName);

            // Class --BELONGS_TO--> Package
            String belongsKey = classInfo.getQualifiedName() + "->belongs_to->" + pkgName;
            if (belongsToKeys.add(belongsKey)) {
                belongsTo.add(new BelongsToClaim(classInfo.getQualifiedName(), pkgName));
            }

            // import 语句 → Package--DEPENDS_ON-->Package
            if (classInfo.getImports() == null || classInfo.getImports().isEmpty()) {
                continue;
            }
            for (String imp : classInfo.getImports()) {
                String targetPkg = resolveImportPackage(imp);
                if (targetPkg == null || targetPkg.isBlank()) {
                    continue;
                }
                // 跳过框架包与自环
                if (targetPkg.equals(pkgName) || isFrameworkPackage(targetPkg)) {
                    continue;
                }
                packageNames.add(targetPkg);
                String depKey = pkgName + "->depends_on->" + targetPkg;
                if (dependsOnKeys.add(depKey)) {
                    dependsOn.add(new DependsOnClaim(pkgName, targetPkg));
                }
            }
        }

        log.debug("PackageExtractor: {} packages, {} belongs_to, {} depends_on",
                packageNames.size(), belongsTo.size(), dependsOn.size());
        return new PackageGraphFact(new ArrayList<>(packageNames), belongsTo, dependsOn);
    }

    /** 判断包是否为需排除的框架包 */
    public static boolean isFrameworkPackage(String pkg) {
        if (pkg == null || pkg.isBlank()) {
            return true;
        }
        for (String prefix : FRAMEWORK_PACKAGE_PREFIXES) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /** 从 import 语句解析出包名 */
    static String resolveImportPackage(String importName) {
        if (importName == null || importName.isBlank()) {
            return null;
        }
        int lastDot = importName.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return importName.substring(0, lastDot);
    }

    /** 取全限定名的最后一段作为简单名 */
    static String simpleName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    // ==================== 数据结构 ====================

    /**
     * 包图事实 — 提取器输出，供 GraphBuilder 建图消费。
     *
     * @param packageNames 所有出现的包名列表
     * @param belongsTo    Class → Package 归属关系候选列表
     * @param dependsOn    Package → Package 依赖关系候选列表（已过滤框架包、去重）
     */
    @Data
    public static class PackageGraphFact {
        private final List<String> packageNames;
        private final List<BelongsToClaim> belongsTo;
        private final List<DependsOnClaim> dependsOn;

        public PackageGraphFact(List<String> packageNames, List<BelongsToClaim> belongsTo, List<DependsOnClaim> dependsOn) {
            this.packageNames = packageNames;
            this.belongsTo = belongsTo;
            this.dependsOn = dependsOn;
        }
    }

    /** Class → Package 归属关系候选 */
    @Data
    public static class BelongsToClaim {
        private final String classQualifiedName;
        private final String packageName;

        public BelongsToClaim(String classQualifiedName, String packageName) {
            this.classQualifiedName = classQualifiedName;
            this.packageName = packageName;
        }
    }

    /** Package → Package 依赖关系候选 */
    @Data
    public static class DependsOnClaim {
        private final String sourcePackage;
        private final String targetPackage;

        public DependsOnClaim(String sourcePackage, String targetPackage) {
            this.sourcePackage = sourcePackage;
            this.targetPackage = targetPackage;
        }
    }
}
