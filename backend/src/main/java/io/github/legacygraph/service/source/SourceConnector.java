package io.github.legacygraph.service.source;

import io.github.legacygraph.dto.source.AccessPolicy;
import io.github.legacygraph.dto.source.SourceDelta;
import io.github.legacygraph.dto.source.SourceDescriptor;
import io.github.legacygraph.dto.source.SourceSnapshot;

import java.util.List;

/**
 * 源连接器抽象接口（G-01）。
 * <p>
 * 统一不同类型源（代码 / 文档 / 数据库 / 运行时 / 外部系统）的发现、抓取、
 * 访问控制与增量比对行为。每个具体实现声明其支持的 {@link #supportedSourceType()}，
 * 由 {@link SourceRegistry} 按 sourceType 路由调用。
 * </p>
 */
public interface SourceConnector {

    /**
     * 发现项目下该类型的所有源资产。
     *
     * @param projectId 项目 ID
     * @return 源描述符列表
     */
    List<SourceDescriptor> discover(String projectId);

    /**
     * 抓取指定源的内容快照。
     *
     * @param descriptor 源描述符
     * @param cursor     游标（用于增量抓取，可为空）
     * @return 源快照
     */
    SourceSnapshot fetch(SourceDescriptor descriptor, String cursor);

    /**
     * 获取指定源的访问控制策略。
     *
     * @param descriptor 源描述符
     * @return 访问策略
     */
    AccessPolicy getAcl(SourceDescriptor descriptor);

    /**
     * 比较两个快照之间的增量。
     *
     * @param previous 前一个快照（可为空）
     * @param current  当前快照
     * @return 源增量
     */
    SourceDelta diff(SourceSnapshot previous, SourceSnapshot current);

    /**
     * 记录或更新检查点，用于后续增量抓取的游标恢复。
     *
     * @param sourceId 源 ID
     * @param cursor   游标
     * @return 持久化后的检查点标识
     */
    String checkpoint(String sourceId, String cursor);

    /**
     * 返回该连接器支持的 sourceType（CODE | DOC | DB | RUN | EXTERNAL）。
     *
     * @return 支持的源类型
     */
    String supportedSourceType();
}
