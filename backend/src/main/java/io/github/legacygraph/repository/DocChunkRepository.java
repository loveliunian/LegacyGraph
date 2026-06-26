package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.DocChunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocChunkRepository extends BaseMapper<DocChunk> {
}
