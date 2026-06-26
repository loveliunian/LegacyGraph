package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentRepository extends BaseMapper<Document> {
}
