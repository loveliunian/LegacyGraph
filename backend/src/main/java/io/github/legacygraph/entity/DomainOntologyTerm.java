package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 领域本体术语实体。
 * <p>
 * 表示项目领域内的一个核心术语（如业务对象、实体、属性、动作等），
 * 支持多别名词表、规范术语映射和人工审核标记。
 * </p>
 *
 * <p>术语间关系通过 {@link DomainOntologyRelation} 表达：</p>
 * <ul>
 *   <li>SYNONYM —— 同义词（如"客户"与"用户"互相替换）</li>
 *   <li>HYPERNYM —— 上下位关系（如"订单"包含"退款单"）</li>
 *   <li>FORBIDDEN_MERGE —— 禁止合并（两个术语虽然相似但语义不同）</li>
 * </ul>
 */
@Data
@TableName("lg_domain_ontology_term")
public class DomainOntologyTerm {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属项目 UUID */
    private String projectId;

    /** 术语原文（唯一约束 project_id + term） */
    private String term;

    /** 规范术语（标准化后的首选名称） */
    private String canonicalTerm;

    /** 别名列表 JSONB 数组，如 ["顾客","消费者","user"] */
    private String aliases;

    /** 术语分类：entity / attribute / relation / action / event 等 */
    private String category;

    /** 来源：CODE_DOC / PROJECT_DOC / DB / MANUAL / AI */
    private String source;

    /** 是否已人工审核 */
    private Boolean reviewed;

    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
