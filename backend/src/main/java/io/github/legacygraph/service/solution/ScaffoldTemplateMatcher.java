package io.github.legacygraph.service.solution;

import io.github.legacygraph.entity.ScaffoldTemplate;
import io.github.legacygraph.repository.ScaffoldTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 脚手架模板匹配服务（G-21）。
 *
 * <p>按实体名和层级匹配项目脚手架模板，供 {@link SolutionPlanner} 在生成 CREATE 步骤时
 * 复用项目既有的标准 CRUD 代码骨架（Controller/Service/Mapper/Entity）。</p>
 *
 * <p>典型用法：方案规划时，对需要新建的实体，先调用 {@link #matchAll} 获取该实体
 * 各层级的模板，将代码骨架作为参考上下文注入 LLM prompt，提升生成代码的一致性。</p>
 */
@Slf4j
@Service
public class ScaffoldTemplateMatcher {

    private final ScaffoldTemplateRepository scaffoldTemplateRepository;

    public ScaffoldTemplateMatcher(ScaffoldTemplateRepository scaffoldTemplateRepository) {
        this.scaffoldTemplateRepository = scaffoldTemplateRepository;
    }

    /**
     * 按实体名和层级匹配单个模板。
     *
     * @param projectId  项目 ID
     * @param entityName 实体名（如 User）
     * @param layer      层级（Controller / Service / Mapper / Entity）
     * @return 匹配到的模板，未匹配到返回 empty
     */
    public Optional<ScaffoldTemplate> match(String projectId, String entityName, String layer) {
        if (projectId == null || projectId.isBlank()
                || entityName == null || entityName.isBlank()
                || layer == null || layer.isBlank()) {
            return Optional.empty();
        }
        try {
            ScaffoldTemplate template = scaffoldTemplateRepository
                    .findByProjectIdAndEntityNameAndLayer(projectId, entityName, layer);
            if (template != null) {
                log.debug("ScaffoldTemplateMatcher: matched template for entity={}, layer={}", entityName, layer);
            }
            return Optional.ofNullable(template);
        } catch (Exception e) {
            log.warn("ScaffoldTemplateMatcher: failed to match template for entity={}, layer={}: {}",
                    entityName, layer, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 匹配实体名对应的所有层级模板。
     *
     * @param projectId  项目 ID
     * @param entityName 实体名（如 User）
     * @return 匹配到的模板列表（可能为空，不会为 null）
     */
    public List<ScaffoldTemplate> matchAll(String projectId, String entityName) {
        if (projectId == null || projectId.isBlank()
                || entityName == null || entityName.isBlank()) {
            return List.of();
        }
        try {
            List<ScaffoldTemplate> templates = scaffoldTemplateRepository
                    .findByProjectIdAndEntityName(projectId, entityName);
            log.debug("ScaffoldTemplateMatcher: matched {} templates for entity={}",
                    templates != null ? templates.size() : 0, entityName);
            return templates != null ? templates : List.of();
        } catch (Exception e) {
            log.warn("ScaffoldTemplateMatcher: failed to match templates for entity={}: {}",
                    entityName, e.getMessage());
            return List.of();
        }
    }
}
