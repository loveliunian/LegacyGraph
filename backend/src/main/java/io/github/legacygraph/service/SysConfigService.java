package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.SysConfig;
import io.github.legacygraph.repository.SysConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 系统配置服务
 */
@Service
public class SysConfigService {

    private final SysConfigRepository sysConfigRepository;

    public SysConfigService(SysConfigRepository sysConfigRepository) {
        this.sysConfigRepository = sysConfigRepository;
    }

    /**
     * 分页查询配置
     */
    public PageResult<SysConfig> list(int pageNum, int pageSize, String keyword, String status) {
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SysConfig::getConfigKey);

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                .like(SysConfig::getConfigKey, keyword)
                .or().like(SysConfig::getConfigName, keyword)
            );
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SysConfig::getStatus, status);
        }

        Page<SysConfig> page = new Page<>(pageNum, pageSize);
        Page<SysConfig> result = sysConfigRepository.selectPage(page, wrapper);

        return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
    }

    /**
     * 根据ID获取配置
     */
    public SysConfig getById(String id) {
        return sysConfigRepository.selectById(id);
    }

    /**
     * 根据键获取配置
     */
    public SysConfig getByKey(String configKey) {
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysConfig::getConfigKey, configKey);
        return sysConfigRepository.selectOne(wrapper);
    }

    /**
     * 根据键获取配置值
     */
    public String getValue(String configKey) {
        SysConfig config = getByKey(configKey);
        return config != null ? config.getConfigValue() : null;
    }

    /**
     * 获取所有配置键值对
     */
    public Map<String, String> getAllConfigMap() {
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysConfig::getStatus, "ACTIVE");
        List<SysConfig> list = sysConfigRepository.selectList(wrapper);
        return list.stream()
            .collect(Collectors.toMap(SysConfig::getConfigKey, SysConfig::getConfigValue));
    }

    /**
     * 创建配置
     */
    public SysConfig create(SysConfig config) {
        config.setId(UUID.randomUUID().toString());
        config.setConfigType(config.getConfigType() == null ? "STRING" : config.getConfigType());
        config.setIsSystem(config.getIsSystem() == null ? false : config.getIsSystem());
        config.setStatus(config.getStatus() == null ? "ACTIVE" : config.getStatus());
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        sysConfigRepository.insert(config);
        return config;
    }

    /**
     * 更新配置
     */
    public boolean update(SysConfig config) {
        config.setUpdatedAt(LocalDateTime.now());
        return sysConfigRepository.updateById(config) > 0;
    }

    /**
     * 更新配置值
     */
    public boolean updateValue(String configKey, String value) {
        SysConfig config = getByKey(configKey);
        if (config == null) {
            return false;
        }
        config.setConfigValue(value);
        config.setUpdatedAt(LocalDateTime.now());
        return sysConfigRepository.updateById(config) > 0;
    }

    /**
     * 删除配置
     */
    public boolean delete(String id) {
        return sysConfigRepository.deleteById(id) > 0;
    }

    /**
     * 获取布尔值配置
     */
    public Boolean getBoolean(String configKey) {
        String value = getValue(configKey);
        if (value == null) return null;
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    /**
     * 获取整数配置
     */
    public Integer getInt(String configKey) {
        String value = getValue(configKey);
        if (value == null) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
