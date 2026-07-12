package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 流程一致性校验结果实体（H25）。
 *
 * <p>存储 PM4Py conformance checking 的 fitness/precision/generalization 指标，
 * 由 {@link io.github.legacygraph.task.step.ProcessMiningStep} 在扫描后写入。</p>
 */
@Data
@TableName("lg_process_fitness")
public class ProcessFitness {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectId;

    private String versionId;

    private Long processId;

    private BigDecimal fitness;

    private BigDecimal precision;

    private BigDecimal generalization;

    private LocalDateTime calculatedAt;
}
