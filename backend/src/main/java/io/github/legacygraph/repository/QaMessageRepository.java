package io.github.legacygraph.repository;

import io.github.legacygraph.entity.QaMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QaMessageRepository extends LegacyBaseMapper<QaMessage> {
}
