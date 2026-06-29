package io.github.legacygraph.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户登录响应")
public class LoginResponse {

    @Schema(description = "访问令牌", example = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6")
    private String accessToken;

    @Schema(description = "刷新令牌", example = "p6o5n4m3l2k1j0i9h8g7f6e5d4c3b2a1")
    private String refreshToken;

    @Schema(description = "过期时间（秒）", example = "7200")
    private Long expiresIn;

    @Schema(description = "用户信息")
    private UserInfo user;

    /**
     * 用户信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "用户基本信息")
    public static class UserInfo {
        @Schema(description = "用户ID", example = "uuid-xxx")
        private String id;

        @Schema(description = "用户名", example = "admin")
        private String username;

        @Schema(description = "昵称", example = "系统管理员")
        private String nickname;

        @Schema(description = "邮箱", example = "admin@example.com")
        private String email;

        @Schema(description = "头像URL")
        private String avatar;

        @Schema(description = "用户角色列表", example = "[\"ADMIN\", \"USER\"]")
        private List<String> roles;

        @Schema(description = "权限列表", example = "[\"read\", \"write\"]")
        private List<String> permissions;
    }
}
