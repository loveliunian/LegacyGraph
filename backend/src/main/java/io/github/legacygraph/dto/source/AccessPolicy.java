package io.github.legacygraph.dto.source;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 访问策略（G-01）。
 * <p>
 * 表示某个源的访问控制策略，包含 ACL 用户 / 用户组、密级与是否公开。
 * 由 {@code SourceConnector.getAcl} 产出，用于在读取源内容前做权限校验。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessPolicy {

    /** ACL 用户列表 */
    private List<String> aclUsers;

    /** ACL 用户组列表 */
    private List<String> aclGroups;

    /** 密级 */
    private String classification;

    /** 是否公开 */
    private boolean isPublic;
}
