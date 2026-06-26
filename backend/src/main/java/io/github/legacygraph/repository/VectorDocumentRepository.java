package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.VectorDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VectorDocumentRepository extends BaseMapper<VectorDocument> {

    @Select("SELECT * FROM lg_vector_document WHERE project_id = #{projectId} AND chunk_type = #{chunkType}")
    List<VectorDocument> findByProjectAndType(Long projectId, String chunkType);
}
