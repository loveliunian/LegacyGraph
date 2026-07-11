package io.github.legacygraph.repository;

import io.github.legacygraph.entity.ScaffoldTemplate;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 脚手架模板 Repository（G-21）。
 */
@Mapper
public interface ScaffoldTemplateRepository extends LegacyBaseMapper<ScaffoldTemplate> {

    /** 按项目 ID 和实体名查询所有层级模板 */
    default List<ScaffoldTemplate> findByProjectIdAndEntityName(String projectId, String entityName) {
        return this.lambdaQuery()
                .eq(ScaffoldTemplate::getProjectId, projectId)
                .eq(ScaffoldTemplate::getEntityName, entityName)
                .list();
    }

    /** 按项目 ID、实体名、层级查询单条模板 */
    default ScaffoldTemplate findByProjectIdAndEntityNameAndLayer(String projectId, String entityName, String layer) {
        return this.lambdaQuery()
                .eq(ScaffoldTemplate::getProjectId, projectId)
                .eq(ScaffoldTemplate::getEntityName, entityName)
                .eq(ScaffoldTemplate::getLayer, layer)
                .one();
    }

    /** 按项目 ID 查询所有模板 */
    default List<ScaffoldTemplate> findByProjectId(String projectId) {
        return this.lambdaQuery()
                .eq(ScaffoldTemplate::getProjectId, projectId)
                .list();
    }
}
