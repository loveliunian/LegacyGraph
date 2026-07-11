package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * QA 评测测试用例实体。
 * <p>
 * 黄金集 / Smoke 集，用于发布前 QA 门禁评测。覆盖 7 种 {@code QueryIntent}。
 * <ul>
 *   <li>{@code expectedEntities}：期望被答案引用的实体 key（如表名 / 类名 / 字段名）</li>
 *   <li>{@code expectedKeywords}：期望答案覆盖的关键词</li>
 *   <li>{@code shouldAbstain}：该问题是否应拒答（图谱中无答案时模型应拒答）</li>
 *   <li>{@code status}：SMOKE（冒烟集，发布门禁） / GOLDEN（黄金集，全量评测）</li>
 * </ul>
 */
@Data
@TableName(value = "lg_qa_test_case", autoResultMap = true)
public class QaTestCase {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属项目 ID（NULL 表示全局/模板用例，不参与项目级门禁） */
    private String projectId;

    private String question;

    /** 期望实体（JSONB，通过 JacksonTypeHandler 序列化） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> expectedEntities;

    /** 期望关键词（JSONB，通过 JacksonTypeHandler 序列化） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> expectedKeywords;

    /** 该问题是否应拒答 */
    private Boolean shouldAbstain;

    /** 意图：FACT_LOOKUP / STRUCTURAL / RELATIONAL / COMPARATIVE / TEMPORAL / EXPLANATION / CHANGE_IMPACT */
    private String intent;

    /** 状态：SMOKE / GOLDEN */
    private String status;

    private LocalDateTime createdAt;
}
