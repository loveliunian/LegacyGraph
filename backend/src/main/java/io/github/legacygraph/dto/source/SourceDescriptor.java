package io.github.legacygraph.dto.source;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 源描述符（G-01）。
 * <p>
 * 描述一个被扫描的源资产（代码 / 文档 / 数据库 / 运行时 / 外部系统），
 * 承载定位、内容指纹与访问控制元信息。由 {@code SourceConnector.discover} 产出，
 * 作为后续 fetch、ACL 校验与 diff 的统一输入。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceDescriptor {

    /** 源类型：CODE | DOC | DB | RUN | EXTERNAL */
    private String sourceType;

    /** 项目 ID */
    private String projectId;

    /** 仓库 ID */
    private String repositoryId;

    /** 分支 */
    private String branch;

    /** 提交 */
    private String commit;

    /** MIME 类型 */
    private String mimeType;

    /** 编程语言 */
    private String language;

    /** 字符集 */
    private String charset;

    /** ETag */
    private String etag;

    /** 内容哈希 */
    private String contentHash;

    /** 大小 */
    private String size;

    /** 修改时间 */
    private String modifiedAt;

    /** 所有者 */
    private String owner;

    /** ACL 用户列表 */
    private List<String> aclUsers;

    /** ACL 用户组列表 */
    private List<String> aclGroups;

    /** 密级 */
    private String classification;

    /** 发现者 */
    private String discoveredBy;

    /** 发现时间 */
    private String discoveredAt;
}
