package io.github.legacygraph.repository;

import io.github.legacygraph.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserRepository extends LegacyBaseMapper<SysUser> {
}
