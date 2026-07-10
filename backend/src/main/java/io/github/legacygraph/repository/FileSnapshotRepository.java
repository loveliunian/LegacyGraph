package io.github.legacygraph.repository;

import io.github.legacygraph.entity.FileSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件快照 Mapper — 基于文件 SHA-256 哈希的增量扫描数据访问。
 */
@Mapper
public interface FileSnapshotRepository extends LegacyBaseMapper<FileSnapshot> {
}
