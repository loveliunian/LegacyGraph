package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验收条件实体（Task 6）。
 * <p>V72 起增加闭环验证字段：status / verificationType /
 * verifiedBy / verifiedAt / verificationNote / evidenceUrl。</p>
 */
@Data
@TableName("lg_acceptance_criterion")
public class AcceptanceCriterion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String requirementItemId;

    /** 验收条件描述 */
    private String text;

    /** 验证状态：PENDING / VERIFIED / WAIVED */
    private String status;

    /** 验证类型：AUTOMATIC / MANUAL / NONE */
    private String verificationType;

    /** 验证人 */
    private String verifiedBy;

    /** 验证时间 */
    private LocalDateTime verifiedAt;

    /** 验证备注 */
    private String verificationNote;

    /** 证据链接（如测试报告、截图地址） */
    private String evidenceUrl;

    private LocalDateTime createdAt;
}
