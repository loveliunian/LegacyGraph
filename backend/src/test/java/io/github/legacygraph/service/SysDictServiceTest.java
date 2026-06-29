package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.SysDict;
import io.github.legacygraph.entity.SysDictItem;
import io.github.legacygraph.repository.SysDictItemRepository;
import io.github.legacygraph.repository.SysDictRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysDictServiceTest {

    @Mock
    private SysDictRepository sysDictRepository;

    @Mock
    private SysDictItemRepository sysDictItemRepository;

    @InjectMocks
    private SysDictService sysDictService;

    @Test
    void testList_Empty() {
        Page<SysDict> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(sysDictRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<SysDict> result = sysDictService.list(1, 10, null, null);

        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    @Test
    void testList_WithData() {
        List<SysDict> dicts = new ArrayList<>();
        SysDict dict = new SysDict();
        dict.setId("dict-1");
        dict.setDictCode("user_status");
        dict.setDictName("用户状态");
        dicts.add(dict);

        Page<SysDict> mockPage = new Page<>(1, 10);
        mockPage.setRecords(dicts);
        mockPage.setTotal(1);

        when(sysDictRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<SysDict> result = sysDictService.list(1, 10, null, null);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("user_status", result.getList().get(0).getDictCode());
    }

    @Test
    void testList_WithKeywordSearch() {
        Page<SysDict> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(sysDictRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<SysDict> result = sysDictService.list(1, 10, "user", "ACTIVE");

        assertNotNull(result);
        verify(sysDictRepository).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void testListAll_OnlyActive() {
        List<SysDict> dicts = new ArrayList<>();
        SysDict dict = new SysDict();
        dict.setId("dict-1");
        dict.setDictCode("user_status");
        dict.setStatus("ACTIVE");
        dicts.add(dict);

        when(sysDictRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(dicts);

        List<SysDict> result = sysDictService.listAll();

        assertEquals(1, result.size());
        assertEquals("ACTIVE", result.get(0).getStatus());
    }

    @Test
    void testGetById_Found() {
        SysDict dict = new SysDict();
        dict.setId("dict-1");
        dict.setDictCode("user_status");

        when(sysDictRepository.selectById("dict-1")).thenReturn(dict);

        SysDict result = sysDictService.getById("dict-1");

        assertNotNull(result);
        assertEquals("dict-1", result.getId());
        assertEquals("user_status", result.getDictCode());
    }

    @Test
    void testGetByCode_Found() {
        SysDict dict = new SysDict();
        dict.setId("dict-1");
        dict.setDictCode("user_status");

        when(sysDictRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dict);

        SysDict result = sysDictService.getByCode("user_status");

        assertNotNull(result);
        assertEquals("user_status", result.getDictCode());
    }

    @Test
    void testCreate_Success() {
        SysDict dict = new SysDict();
        dict.setDictCode("user_status");
        dict.setDictName("用户状态");

        SysDict result = sysDictService.create(dict);

        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals("user_status", result.getDictCode());
        assertEquals("ACTIVE", result.getStatus()); // default
        assertEquals(0, result.getSortOrder()); // default
        assertNotNull(result.getCreatedAt());
        verify(sysDictRepository).insert(any(SysDict.class));
    }

    @Test
    void testCreate_WithCustomValues() {
        SysDict dict = new SysDict();
        dict.setDictCode("user_status");
        dict.setDictName("用户状态");
        dict.setStatus("INACTIVE");
        dict.setSortOrder(10);

        SysDict result = sysDictService.create(dict);

        assertNotNull(result);
        assertEquals("INACTIVE", result.getStatus());
        assertEquals(10, result.getSortOrder());
    }

    @Test
    void testUpdate_Success() {
        SysDict dict = new SysDict();
        dict.setId("dict-1");
        dict.setDictName("Updated Name");

        when(sysDictRepository.updateById(any(SysDict.class))).thenReturn(1);

        boolean result = sysDictService.update(dict);

        assertTrue(result);
        verify(sysDictRepository).updateById(any(SysDict.class));
    }

    @Test
    void testDelete_Success() {
        when(sysDictRepository.deleteById("dict-1")).thenReturn(1);
        when(sysDictItemRepository.delete(any(LambdaQueryWrapper.class))).thenReturn(1);

        boolean result = sysDictService.delete("dict-1");

        assertTrue(result);
        // Should delete items first
        verify(sysDictItemRepository).delete(any(LambdaQueryWrapper.class));
        verify(sysDictRepository).deleteById("dict-1");
    }

    @Test
    void testGetItemsByDictId() {
        List<SysDictItem> items = new ArrayList<>();
        SysDictItem item = new SysDictItem();
        item.setId("item-1");
        item.setDictId("dict-1");
        item.setItemValue("active");
        item.setItemLabel("激活");
        item.setStatus("ACTIVE");
        items.add(item);

        when(sysDictItemRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(items);

        List<SysDictItem> result = sysDictService.getItemsByDictId("dict-1");

        assertEquals(1, result.size());
        assertEquals("dict-1", result.get(0).getDictId());
        assertEquals("ACTIVE", result.get(0).getStatus());
    }

    @Test
    void testGetItemsByDictCode_DictNotFound() {
        when(sysDictRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        List<SysDictItem> result = sysDictService.getItemsByDictCode("nonexistent");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetItemsByDictCode_Found() {
        SysDict dict = new SysDict();
        dict.setId("dict-1");
        dict.setDictCode("user_status");

        List<SysDictItem> items = new ArrayList<>();
        SysDictItem item = new SysDictItem();
        item.setId("item-1");
        item.setDictId("dict-1");
        item.setItemValue("active");
        item.setItemLabel("激活");
        item.setStatus("ACTIVE");
        items.add(item);

        when(sysDictRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dict);
        when(sysDictItemRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(items);

        List<SysDictItem> result = sysDictService.getItemsByDictCode("user_status");

        assertEquals(1, result.size());
        assertEquals("active", result.get(0).getItemValue());
    }

    @Test
    void testGetItemMap() {
        SysDict dict = new SysDict();
        dict.setId("dict-1");
        dict.setDictCode("user_status");

        List<SysDictItem> items = new ArrayList<>();
        SysDictItem item1 = new SysDictItem();
        item1.setId("item-1");
        item1.setDictId("dict-1");
        item1.setItemValue("ACTIVE");
        item1.setItemLabel("激活");
        item1.setStatus("ACTIVE");
        items.add(item1);

        SysDictItem item2 = new SysDictItem();
        item2.setId("item-2");
        item2.setDictId("dict-1");
        item2.setItemValue("INACTIVE");
        item2.setItemLabel("禁用");
        item2.setStatus("ACTIVE");
        items.add(item2);

        when(sysDictRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dict);
        when(sysDictItemRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(items);

        Map<String, String> result = sysDictService.getItemMap("user_status");

        assertEquals(2, result.size());
        assertEquals("激活", result.get("ACTIVE"));
        assertEquals("禁用", result.get("INACTIVE"));
    }

    @Test
    void testCreateItem_Success() {
        SysDictItem item = new SysDictItem();
        item.setDictId("dict-1");
        item.setItemValue("ACTIVE");
        item.setItemLabel("激活");

        SysDictItem result = sysDictService.createItem(item);

        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals("ACTIVE", result.getStatus()); // default
        assertFalse(result.getIsDefault()); // default
        assertEquals(0, result.getSortOrder()); // default
        assertNotNull(result.getCreatedAt());
        verify(sysDictItemRepository).insert(any(SysDictItem.class));
    }

    @Test
    void testUpdateItem_Success() {
        SysDictItem item = new SysDictItem();
        item.setId("item-1");
        item.setItemLabel("Updated Label");

        when(sysDictItemRepository.updateById(any(SysDictItem.class))).thenReturn(1);

        boolean result = sysDictService.updateItem(item);

        assertTrue(result);
        verify(sysDictItemRepository).updateById(any(SysDictItem.class));
    }

    @Test
    void testDeleteItem_Success() {
        when(sysDictItemRepository.deleteById("item-1")).thenReturn(1);

        boolean result = sysDictService.deleteItem("item-1");

        assertTrue(result);
        verify(sysDictItemRepository).deleteById("item-1");
    }
}
