package io.github.legacygraph.repository;

import io.github.legacygraph.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentRepository extends LegacyBaseMapper<Document> {
}
