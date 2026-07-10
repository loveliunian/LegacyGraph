package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.GraphRelease;
import org.apache.ibatis.annotations.Mapper;

/**
 * 图谱发布 Repository。
 * <p>
 * 提供标准的 CRUD（继承自 {@link LegacyBaseMapper}）以及
 * {@link #findByProjectAndVersion} 唯一约束查询。
 * </p>
 */
@Mapper
public interface GraphReleaseRepository extends LegacyBaseMapper<GraphRelease> {

    /**
     * 按项目 ID 和扫描版本 ID 查询发布记录（唯一约束查询）。
     *
     * @param projectId     项目 ID
     * @param scanVersionId 扫描版本 ID
     * @return 匹配的发布记录，不存在时返回 {@code null}
     */
    default GraphRelease findByProjectAndVersion(String projectId, String scanVersionId) {
        return selectOne(new LambdaQueryWrapper<GraphRelease>()
                .eq(GraphRelease::getProjectId, projectId)
                .eq(GraphRelease::getScanVersionId, scanVersionId));
    }
}
