package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.SysDict;
import io.github.legacygraph.entity.SysDictItem;
import io.github.legacygraph.repository.SysDictItemRepository;
import io.github.legacygraph.repository.SysDictRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 字典服务
 */
@Service
public class SysDictService {

    private final SysDictRepository sysDictRepository;
    private final SysDictItemRepository sysDictItemRepository;

    public SysDictService(SysDictRepository sysDictRepository, SysDictItemRepository sysDictItemRepository) {
        this.sysDictRepository = sysDictRepository;
        this.sysDictItemRepository = sysDictItemRepository;
    }

    /**
     * 分页查询字典类型
     */
    public PageResult<SysDict> list(int pageNum, int pageSize, String keyword, String status) {
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SysDict::getSortOrder);

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                .like(SysDict::getDictCode, keyword)
                .or().like(SysDict::getDictName, keyword)
            );
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SysDict::getStatus, status);
        }

        Page<SysDict> page = new Page<>(pageNum, pageSize);
        Page<SysDict> result = sysDictRepository.selectPage(page, wrapper);

        return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
    }

    /**
     * 获取所有激活的字典列表
     */
    public List<SysDict> listAll() {
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDict::getStatus, "ACTIVE");
        wrapper.orderByAsc(SysDict::getSortOrder);
        return sysDictRepository.selectList(wrapper);
    }

    /**
     * 根据ID获取字典类型
     */
    public SysDict getById(String id) {
        return sysDictRepository.selectById(id);
    }

    /**
     * 根据编码获取字典类型
     */
    public SysDict getByCode(String dictCode) {
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDict::getDictCode, dictCode);
        return sysDictRepository.selectOne(wrapper);
    }

    /**
     * 获取所有激活字典的全量映射（缓存：dict-map）
     * 返回 { dictCode: { value: label } }
     * 供前端一次加载全量字典内存缓存。
     */
    @Cacheable(cacheNames = "dict-map", key = "'all'")
    public Map<String, Map<String, String>> getAllItemMaps() {
        List<SysDict> dicts = listAll();
        Map<String, Map<String, String>> result = new java.util.LinkedHashMap<>();
        for (SysDict dict : dicts) {
            List<SysDictItem> items = getItemsByDictId(dict.getId());
            Map<String, String> map = items.stream()
                .collect(Collectors.toMap(SysDictItem::getItemValue, SysDictItem::getItemLabel));
            result.put(dict.getDictCode(), map);
        }
        return result;
    }

    /**
     * 创建字典类型
     */
    public SysDict create(SysDict dict) {
        dict.setId(UUID.randomUUID().toString());
        dict.setStatus(dict.getStatus() == null ? "ACTIVE" : dict.getStatus());
        dict.setSortOrder(dict.getSortOrder() == null ? 0 : dict.getSortOrder());
        dict.setCreatedAt(LocalDateTime.now());
        dict.setUpdatedAt(LocalDateTime.now());
        sysDictRepository.insert(dict);
        return dict;
    }

    /**
     * 更新字典类型
     */
    public boolean update(SysDict dict) {
        dict.setUpdatedAt(LocalDateTime.now());
        return sysDictRepository.updateById(dict) > 0;
    }

    /**
     * 删除字典类型（级联删除字典项，清空字典项缓存）
     */
    @Transactional
    @CacheEvict(cacheNames = {"dict-items", "dict-map"}, allEntries = true)
    public boolean delete(String id) {
        // 删除字典下所有项
        LambdaQueryWrapper<SysDictItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(SysDictItem::getDictId, id);
        sysDictItemRepository.delete(itemWrapper);
        // 删除字典
        return sysDictRepository.deleteById(id) > 0;
    }

    /**
     * 获取字典下所有项
     */
    public List<SysDictItem> getItemsByDictId(String dictId) {
        LambdaQueryWrapper<SysDictItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDictItem::getDictId, dictId);
        wrapper.eq(SysDictItem::getStatus, "ACTIVE");
        wrapper.orderByAsc(SysDictItem::getSortOrder);
        return sysDictItemRepository.selectList(wrapper);
    }

    /**
     * 根据字典编码获取所有项（缓存：dict-items）
     */
    @Cacheable(cacheNames = "dict-items", key = "#dictCode")
    public List<SysDictItem> getItemsByDictCode(String dictCode) {
        SysDict dict = getByCode(dictCode);
        if (dict == null) {
            return List.of();
        }
        return getItemsByDictId(dict.getId());
    }

    /**
     * 获取字典项映射（值 -> 标签）（缓存：dict-map）
     */
    @Cacheable(cacheNames = "dict-map", key = "#dictCode")
    public Map<String, String> getItemMap(String dictCode) {
        List<SysDictItem> items = getItemsByDictCode(dictCode);
        return items.stream()
            .collect(Collectors.toMap(SysDictItem::getItemValue, SysDictItem::getItemLabel));
    }

    /**
     * 创建字典项（写操作清空字典项缓存）
     */
    @CacheEvict(cacheNames = {"dict-items", "dict-map"}, allEntries = true)
    public SysDictItem createItem(SysDictItem item) {
        item.setId(UUID.randomUUID().toString());
        item.setStatus(item.getStatus() == null ? "ACTIVE" : item.getStatus());
        item.setIsDefault(item.getIsDefault() == null ? false : item.getIsDefault());
        item.setSortOrder(item.getSortOrder() == null ? 0 : item.getSortOrder());
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        sysDictItemRepository.insert(item);
        return item;
    }

    /**
     * 更新字典项（写操作清空字典项缓存）
     */
    @CacheEvict(cacheNames = {"dict-items", "dict-map"}, allEntries = true)
    public boolean updateItem(SysDictItem item) {
        item.setUpdatedAt(LocalDateTime.now());
        return sysDictItemRepository.updateById(item) > 0;
    }

    /**
     * 删除字典项（写操作清空字典项缓存）
     */
    @CacheEvict(cacheNames = {"dict-items", "dict-map"}, allEntries = true)
    public boolean deleteItem(String id) {
        return sysDictItemRepository.deleteById(id) > 0;
    }
}
