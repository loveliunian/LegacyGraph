package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 领域本体术语关系实体。
 * <p>
 * 描述两个 {@link DomainOntologyTerm} 之间的语义关系，
 * 支持同义词、上下位关系和禁止合并三种类型。
 * </p>
 *
 * <p>关系类型说明：</p>
 * <ul>
 *   <li><b>SYNONYM</b> —— 同义词，两个术语语义等价</li>
 *   <li><b>HYPERNYM</b> —— 上下位关系，from_term 是 to_term 的上位概念</li>
 *   <li><b>FORBIDDEN_MERGE</b> —— 禁止合并，两个相似术语有本质区别</li>
 * </ul>
 */
@Data
@TableName("lg_domain_ontology_relation")
public class DomainOntologyRelation {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属项目 UUID */
    private String projectId;

    /** 源术语 UUID（外键 → lg_domain_ontology_term.id） */
    private String fromTermId;

    /** 目标术语 UUID（外键 → lg_domain_ontology_term.id） */
    private String toTermId;

    /** 关系类型：SYNONYM / HYPERNYM / FORBIDDEN_MERGE */
    private String relationType;

    /** 置信度 0~1，NUMERIC(5,4) */
    private BigDecimal confidence;

    /** 来源：CODE_DOC / PROJECT_DOC / DB / MANUAL / AI */
    private String source;

    /** 是否已人工审核 */
    private Boolean reviewed;

    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
