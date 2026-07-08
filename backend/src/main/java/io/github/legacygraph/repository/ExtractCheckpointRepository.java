package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.ExtractCheckpoint;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提取断点 Mapper — 支持按 (project, version, step) 查询已完成文件集合，实现断点续传。
 */
@Mapper
public interface ExtractCheckpointRepository extends BaseMapper<ExtractCheckpoint> {

    /**
     * 查询已完成提取的文件路径集合，用于跳过已处理的文件。
     */
    default Set<String> findDonePaths(String projectId, String versionId, String stepName) {
        LambdaQueryWrapper<ExtractCheckpoint> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExtractCheckpoint::getProjectId, projectId)
                .eq(ExtractCheckpoint::getVersionId, versionId)
                .eq(ExtractCheckpoint::getStepName, stepName)
                .eq(ExtractCheckpoint::getStatus, "DONE")
                .select(ExtractCheckpoint::getFilePath);
        return selectList(wrapper).stream()
                .map(ExtractCheckpoint::getFilePath)
                .collect(Collectors.toSet());
    }
}
