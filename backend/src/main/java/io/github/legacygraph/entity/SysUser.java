package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 系统用户实体
 */
@Data
@TableName("lg_sys_user")
public class SysUser {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码（BCrypt加密）
     */
    private String password;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 头像URL
     */
    private String avatar;

    /**
     * 角色（逗号分隔）
     */
    private String roles;

    /**
     * 权限（逗号分隔），支持前端传数组或字符串
     */
    private String permissions;

    /** 兼容前端传数组格式：["admin","user"] → "admin,user" */
    @JsonSetter("permissions")
    public void setPermissionsJson(JsonNode node) {
        if (node == null || node.isNull()) {
            this.permissions = null;
        } else if (node.isArray()) {
            this.permissions = StreamSupport.stream(node.spliterator(), false)
                    .map(JsonNode::asText)
                    .collect(Collectors.joining(","));
        } else {
            this.permissions = node.asText();
        }
    }

    /**
     * 状态: ACTIVE/DISABLED
     */
    private String status;

    /**
     * 最后登录时间
     */
    private LocalDateTime lastLoginAt;

    /**
     * 最后登录IP
     */
    private String lastLoginIp;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
