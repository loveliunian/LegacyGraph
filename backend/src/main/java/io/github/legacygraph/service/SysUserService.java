package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.SysUser;
import io.github.legacygraph.repository.SysUserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 系统用户服务
 */
@Service
public class SysUserService {

    private final SysUserRepository sysUserRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public SysUserService(SysUserRepository sysUserRepository) {
        this.sysUserRepository = sysUserRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 分页查询用户
     */
    public PageResult<SysUser> list(int pageNum, int pageSize, String keyword, String status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SysUser::getCreatedAt);

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                .like(SysUser::getUsername, keyword)
                .or().like(SysUser::getNickname, keyword)
                .or().like(SysUser::getEmail, keyword)
            );
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SysUser::getStatus, status);
        }

        Page<SysUser> page = new Page<>(pageNum, pageSize);
        Page<SysUser> result = sysUserRepository.selectPage(page, wrapper);

        // 清除密码，不返回给前端
        result.getRecords().forEach(user -> user.setPassword(null));

        return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
    }

    /**
     * 根据ID获取用户
     */
    public SysUser getById(String id) {
        SysUser user = sysUserRepository.selectById(id);
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }

    /**
     * 根据用户名获取用户
     */
    public SysUser getByUsername(String username) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username);
        return sysUserRepository.selectOne(wrapper);
    }

    /**
     * 创建用户
     */
    public SysUser create(SysUser user) {
        user.setId(UUID.randomUUID().toString());
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setStatus(user.getStatus() == null ? "ACTIVE" : user.getStatus());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserRepository.insert(user);
        user.setPassword(null);
        return user;
    }

    /**
     * 更新用户
     */
    public boolean update(SysUser user) {
        SysUser existing = sysUserRepository.selectById(user.getId());
        if (existing == null) {
            return false;
        }
        // 如果密码为空，不更新密码
        if (!StringUtils.hasText(user.getPassword())) {
            user.setPassword(null);
        } else {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        user.setUpdatedAt(LocalDateTime.now());
        return sysUserRepository.updateById(user) > 0;
    }

    /**
     * 删除用户
     */
    public boolean delete(String id) {
        return sysUserRepository.deleteById(id) > 0;
    }

    /**
     * 更新状态
     */
    public boolean updateStatus(String id, String status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        return sysUserRepository.updateById(user) > 0;
    }

    /**
     * 更新最后登录信息
     */
    public void updateLastLogin(String id, String ip) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ip);
        sysUserRepository.updateById(user);
    }

    /**
     * 查询所有用户
     */
    public List<SysUser> listAll() {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getStatus, "ACTIVE");
        wrapper.orderByAsc(SysUser::getCreatedAt);
        List<SysUser> users = sysUserRepository.selectList(wrapper);
        users.forEach(user -> user.setPassword(null));
        return users;
    }
}
