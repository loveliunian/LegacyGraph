package io.github.legacygraph.repository;

import io.github.legacygraph.entity.QaFeedbackEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * QA 声明反馈 Mapper（区别于 G-08 的 {@link QaFeedbackRepository}）。
 */
@Mapper
public interface QaFeedbackEntityRepository extends LegacyBaseMapper<QaFeedbackEntity> {
}
