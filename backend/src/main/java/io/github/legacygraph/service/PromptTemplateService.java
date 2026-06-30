package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.PromptTemplate;
import io.github.legacygraph.repository.PromptTemplateRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提示词模板服务 — 统一管理 LLM 提示词模板。
 */
@Service
public class PromptTemplateService {

    private final PromptTemplateRepository promptTemplateRepository;

    public PromptTemplateService(PromptTemplateRepository promptTemplateRepository) {
        this.promptTemplateRepository = promptTemplateRepository;
    }

    /**
     * 分页查询提示词模板
     */
    public PageResult<PromptTemplate> list(int pageNum, int pageSize, String keyword, String scene, String status) {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(PromptTemplate::getTemplateCode)
                .orderByDesc(PromptTemplate::getVersion);

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(PromptTemplate::getTemplateCode, keyword)
                    .or().like(PromptTemplate::getScene, keyword));
        }
        if (StringUtils.hasText(scene)) {
            wrapper.eq(PromptTemplate::getScene, scene);
        }
        if ("active".equalsIgnoreCase(status)) {
            wrapper.eq(PromptTemplate::getIsActive, true);
        } else if ("inactive".equalsIgnoreCase(status)) {
            wrapper.eq(PromptTemplate::getIsActive, false);
        }

        Page<PromptTemplate> page = new Page<>(pageNum, pageSize);
        Page<PromptTemplate> result = promptTemplateRepository.selectPage(page, wrapper);

        return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
    }

    /**
     * 获取所有激活的模板（用于 LLM 调用）
     */
    @Cacheable(cacheNames = "prompt-templates", key = "'active'")
    public List<PromptTemplate> listActive() {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromptTemplate::getIsActive, true)
                .orderByAsc(PromptTemplate::getTemplateCode)
                .orderByDesc(PromptTemplate::getVersion);
        return promptTemplateRepository.selectList(wrapper);
    }

    /**
     * 根据 templateCode 获取活跃版本（缓存：每次 LLM 渲染都会读取，模板极少变更）
     */
    @Cacheable(cacheNames = "prompt-templates", key = "'code:' + #templateCode", unless = "#result == null")
    public PromptTemplate getActiveByCode(String templateCode) {
        return promptTemplateRepository.findActiveByCode(templateCode);
    }

    /**
     * 获取模板详情
     */
    public PromptTemplate getById(Long id) {
        return promptTemplateRepository.selectById(id);
    }

    /**
     * 创建提示词模板
     */
    @CacheEvict(cacheNames = "prompt-templates", allEntries = true)
    public PromptTemplate create(PromptTemplate template) {
        template.setVersion(template.getVersion() != null ? template.getVersion() : "1.0");
        template.setIsActive(template.getIsActive() != null ? template.getIsActive() : true);
        template.setCreatedAt(LocalDateTime.now());
        promptTemplateRepository.insert(template);
        return template;
    }

    /**
     * 更新提示词模板
     */
    @Transactional
    @CacheEvict(cacheNames = "prompt-templates", allEntries = true)
    public PromptTemplate update(PromptTemplate template) {
        // 创建新版本（版本号递增），旧版本设为 inactive
        PromptTemplate existing = promptTemplateRepository.selectById(template.getId());
        if (existing != null) {
            // 将旧版本停用
            existing.setIsActive(false);
            promptTemplateRepository.updateById(existing);

            // 创建新版本
            PromptTemplate newVersion = new PromptTemplate();
            newVersion.setTemplateCode(existing.getTemplateCode());
            newVersion.setVersion(incrementVersion(existing.getVersion()));
            newVersion.setScene(template.getScene() != null ? template.getScene() : existing.getScene());
            newVersion.setSystemPrompt(template.getSystemPrompt() != null ? template.getSystemPrompt() : existing.getSystemPrompt());
            newVersion.setDomainPrompt(template.getDomainPrompt() != null ? template.getDomainPrompt() : existing.getDomainPrompt());
            newVersion.setTaskPrompt(template.getTaskPrompt() != null ? template.getTaskPrompt() : existing.getTaskPrompt());
            newVersion.setOutputSchema(template.getOutputSchema() != null ? template.getOutputSchema() : existing.getOutputSchema());
            newVersion.setIsActive(true);
            newVersion.setCreatedAt(LocalDateTime.now());
            promptTemplateRepository.insert(newVersion);
            return newVersion;
        }
        return null;
    }

    /**
     * 切换模板激活状态
     */
    @CacheEvict(cacheNames = "prompt-templates", allEntries = true)
    public boolean toggleActive(Long id) {
        PromptTemplate template = promptTemplateRepository.selectById(id);
        if (template == null) return false;
        template.setIsActive(!Boolean.TRUE.equals(template.getIsActive()));
        return promptTemplateRepository.updateById(template) > 0;
    }

    /**
     * 删除提示词模板
     */
    @CacheEvict(cacheNames = "prompt-templates", allEntries = true)
    public boolean delete(Long id) {
        return promptTemplateRepository.deleteById(id) > 0;
    }

    /**
     * 重新加载模板缓存
     */
    @CacheEvict(cacheNames = "prompt-templates", allEntries = true)
    public void evictCache() {
        // 缓存自动清空
    }

    private String incrementVersion(String version) {
        try {
            double v = Double.parseDouble(version);
            return String.format("%.1f", v + 0.1);
        } catch (NumberFormatException e) {
            return version + ".1";
        }
    }
}
