package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 脚手架模板实体（G-21）。
 * <p>从图谱中识别的标准 CRUD 模板，按 Controller/Service/Mapper/Entity 分层存储，
 * 供 SolutionPlanner 在生成 CREATE 步骤时复用项目既有代码骨架。</p>
 */
@Data
@TableName("lg_scaffold_template")
public class ScaffoldTemplate {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 项目 ID */
    private String projectId;

    /** 实体名（从类名提取，如 UserController → User） */
    private String entityName;

    /** 分层：Controller / Service / Mapper / Entity */
    private String layer;

    /** 源文件路径 */
    private String filePath;

    /** 代码骨架（含类声明、继承关系等） */
    private String codeSkeleton;

    /** 注解信息（JSON 字符串） */
    private String annotations;

    /** 方法签名列表（JSON 字符串） */
    private String methodSignatures;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
