package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysUserRepository extends LegacyBaseMapper<SysUser> {

    /**
     * 自定义更新方法，处理 UUID 类型转换问题
     */
    @Update("UPDATE lg_sys_user SET username = #{user.username}, password = #{user.password}, " +
            "nickname = #{user.nickname}, email = #{user.email}, phone = #{user.phone}, " +
            "avatar = #{user.avatar}, roles = #{user.roles}, permissions = #{user.permissions}, " +
            "status = #{user.status}, last_login_at = #{user.lastLoginAt}, " +
            "last_login_ip = #{user.lastLoginIp}, created_at = #{user.createdAt}, " +
            "updated_at = #{user.updatedAt} " +
            "WHERE id = CAST(#{user.id} AS uuid)")
    int updateUserById(@Param("user") SysUser user);

    /**
     * 自定义根据用户名查询方法
     */
    default SysUser selectByUsername(String username) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username);
        return selectOne(wrapper);
    }
}
