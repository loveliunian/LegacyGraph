package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.SysUser;
import io.github.legacygraph.repository.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysUserServiceTest {

    @Mock
    private SysUserRepository sysUserRepository;

    @InjectMocks
    private SysUserService sysUserService;

    private SysUser testUser;

    @BeforeEach
    void setUp() {
        testUser = new SysUser();
        testUser.setId("user-1");
        testUser.setUsername("testuser");
        testUser.setNickname("Test User");
        testUser.setEmail("test@example.com");
        testUser.setPassword("password123");
        testUser.setStatus("ACTIVE");
    }

    @Test
    void testList_Empty() {
        Page<SysUser> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(sysUserRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<SysUser> result = sysUserService.list(1, 10, null, null);

        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    @Test
    void testList_WithData() {
        List<SysUser> users = new ArrayList<>();
        users.add(testUser);

        Page<SysUser> mockPage = new Page<>(1, 10);
        mockPage.setRecords(users);
        mockPage.setTotal(1);

        when(sysUserRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<SysUser> result = sysUserService.list(1, 10, null, null);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        // Password should be cleared
        assertNull(result.getList().get(0).getPassword());
    }

    @Test
    void testList_WithKeywordSearch() {
        Page<SysUser> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(sysUserRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<SysUser> result = sysUserService.list(1, 10, "test", null);

        assertNotNull(result);
        // Verify the query was called
        verify(sysUserRepository).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void testList_WithStatusFilter() {
        Page<SysUser> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(sysUserRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<SysUser> result = sysUserService.list(1, 10, null, "ACTIVE");

        assertNotNull(result);
        verify(sysUserRepository).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void testGetById_Found() {
        when(sysUserRepository.selectById("user-1")).thenReturn(testUser);

        SysUser result = sysUserService.getById("user-1");

        assertNotNull(result);
        assertEquals("user-1", result.getId());
        assertEquals("testuser", result.getUsername());
        assertNull(result.getPassword()); // Password should be cleared
    }

    @Test
    void testGetById_NotFound() {
        when(sysUserRepository.selectById("nonexistent")).thenReturn(null);

        SysUser result = sysUserService.getById("nonexistent");

        assertNull(result);
    }

    @Test
    void testGetByUsername_Found() {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, "testuser");

        when(sysUserRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);

        SysUser result = sysUserService.getByUsername("testuser");

        assertNotNull(result);
        assertEquals("testuser", result.getUsername());
    }

    @Test
    void testCreate_Success() {
        SysUser newUser = new SysUser();
        newUser.setUsername("newuser");
        newUser.setNickname("New User");
        newUser.setEmail("new@example.com");
        newUser.setPassword("password123");

        when(sysUserRepository.insert(any(SysUser.class))).thenReturn(1);

        SysUser result = sysUserService.create(newUser);

        assertNotNull(result);
        assertNotNull(result.getId()); // UUID generated
        assertEquals("newuser", result.getUsername());
        assertNull(result.getPassword()); // Password cleared in return
        verify(sysUserRepository).insert(any(SysUser.class));

        // Capture the inserted user to verify password was encoded
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserRepository).insert(captor.capture());
        SysUser inserted = captor.getValue();

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertTrue(encoder.matches("password123", inserted.getPassword()));
        assertEquals("ACTIVE", inserted.getStatus()); // default status
        assertNotNull(inserted.getCreatedAt());
    }

    @Test
    void testCreate_WithCustomStatus() {
        SysUser newUser = new SysUser();
        newUser.setUsername("newuser");
        newUser.setPassword("password123");
        newUser.setStatus("INACTIVE");

        when(sysUserRepository.insert(any(SysUser.class))).thenReturn(1);

        SysUser result = sysUserService.create(newUser);

        assertNotNull(result);
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserRepository).insert(captor.capture());
        assertEquals("INACTIVE", captor.getValue().getStatus());
    }

    @Test
    void testUpdate_NotFound() {
        when(sysUserRepository.selectById("nonexistent")).thenReturn(null);

        SysUser user = new SysUser();
        user.setId("nonexistent");

        boolean result = sysUserService.update(user);

        assertFalse(result);
    }

    @Test
    void testUpdate_WithoutPasswordChange() {
        SysUser existing = new SysUser();
        existing.setId("user-1");
        existing.setUsername("testuser");
        existing.setPassword("encoded-password");

        when(sysUserRepository.selectById("user-1")).thenReturn(existing);
        when(sysUserRepository.updateById(any(SysUser.class))).thenReturn(1);

        SysUser update = new SysUser();
        update.setId("user-1");
        update.setNickname("Updated Name");
        // No password provided

        boolean result = sysUserService.update(update);

        assertTrue(result);
        verify(sysUserRepository).updateById(any(SysUser.class));

        // Password should not be set (remains null in update object)
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserRepository).updateById(captor.capture());
        assertNull(captor.getValue().getPassword());
    }

    @Test
    void testUpdate_WithPasswordChange() {
        SysUser existing = new SysUser();
        existing.setId("user-1");
        existing.setUsername("testuser");
        existing.setPassword("old-encoded");

        when(sysUserRepository.selectById("user-1")).thenReturn(existing);
        when(sysUserRepository.updateById(any(SysUser.class))).thenReturn(1);

        SysUser update = new SysUser();
        update.setId("user-1");
        update.setPassword("newpassword");

        boolean result = sysUserService.update(update);

        assertTrue(result);
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserRepository).updateById(captor.capture());

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertTrue(encoder.matches("newpassword", captor.getValue().getPassword()));
    }

    @Test
    void testDelete_Success() {
        when(sysUserRepository.deleteById("user-1")).thenReturn(1);

        boolean result = sysUserService.delete("user-1");

        assertTrue(result);
        verify(sysUserRepository).deleteById("user-1");
    }

    @Test
    void testDelete_Failed() {
        when(sysUserRepository.deleteById("nonexistent")).thenReturn(0);

        boolean result = sysUserService.delete("nonexistent");

        assertFalse(result);
    }

    @Test
    void testUpdateStatus_Success() {
        when(sysUserRepository.updateById(any(SysUser.class))).thenReturn(1);

        boolean result = sysUserService.updateStatus("user-1", "INACTIVE");

        assertTrue(result);
        verify(sysUserRepository).updateById(any(SysUser.class));
    }

    @Test
    void testListAll_OnlyActive() {
        List<SysUser> users = new ArrayList<>();
        users.add(testUser);

        when(sysUserRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(users);

        List<SysUser> result = sysUserService.listAll();

        assertEquals(1, result.size());
        // Password should be cleared
        assertNull(result.get(0).getPassword());
    }
}
