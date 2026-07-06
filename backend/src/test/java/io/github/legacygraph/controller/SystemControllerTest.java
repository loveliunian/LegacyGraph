package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.SysUser;
import io.github.legacygraph.repository.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserRepository sysUserRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        sysUserRepository.delete(new QueryWrapper<>());
    }

    @Test
    void testListUsers() throws Exception {
        SysUser user = new SysUser();
        user.setUsername("testuser");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setNickname("Test User");
        user.setEmail("test@example.com");
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserRepository.insert(user);

        mockMvc.perform(get("/lg/system/users/list")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").exists())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void testListUsers_KeywordFilter() throws Exception {
        SysUser user1 = new SysUser();
        user1.setUsername("admin");
        user1.setPassword(passwordEncoder.encode("password123"));
        user1.setNickname("Admin");
        user1.setEmail("admin@example.com");
        user1.setStatus("ACTIVE");
        user1.setCreatedAt(LocalDateTime.now());
        user1.setUpdatedAt(LocalDateTime.now());
        sysUserRepository.insert(user1);

        SysUser user2 = new SysUser();
        user2.setUsername("user");
        user2.setPassword(passwordEncoder.encode("password123"));
        user2.setNickname("User");
        user2.setEmail("user@example.com");
        user2.setStatus("ACTIVE");
        user2.setCreatedAt(LocalDateTime.now());
        user2.setUpdatedAt(LocalDateTime.now());
        sysUserRepository.insert(user2);

        mockMvc.perform(get("/lg/system/users/list")
                        .param("keyword", "admin")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void testGetUser() throws Exception {
        SysUser user = new SysUser();
        user.setUsername("testuser");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setNickname("Test User");
        user.setEmail("test@example.com");
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserRepository.insert(user);

        String id = sysUserRepository.selectList(null).get(0).getId();

        mockMvc.perform(get("/lg/system/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("testuser"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void testCreateUser_Success() throws Exception {
        SysUser user = new SysUser();
        user.setUsername("newuser");
        user.setPassword("password123");
        user.setNickname("New User");
        user.setEmail("new@example.com");
        user.setStatus("ACTIVE");

        mockMvc.perform(post("/lg/system/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("newuser"));
    }

    @Test
    void testCreateUser_DuplicateUsername() throws Exception {
        SysUser existing = new SysUser();
        existing.setUsername("duplicate");
        existing.setPassword(passwordEncoder.encode("password123"));
        existing.setNickname("Existing");
        existing.setEmail("existing@example.com");
        existing.setStatus("ACTIVE");
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        sysUserRepository.insert(existing);

        SysUser user = new SysUser();
        user.setUsername("duplicate");
        user.setPassword("password123");
        user.setNickname("Duplicate");
        user.setEmail("duplicate@example.com");

        mockMvc.perform(post("/lg/system/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void testUpdateUser() throws Exception {
        SysUser user = new SysUser();
        user.setUsername("testuser");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setNickname("Old Name");
        user.setEmail("old@example.com");
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserRepository.insert(user);

        String id = sysUserRepository.selectList(null).get(0).getId();

        SysUser updated = new SysUser();
        updated.setId(id);
        updated.setNickname("New Name");
        updated.setEmail("new@example.com");

        mockMvc.perform(put("/lg/system/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testDeleteUser() throws Exception {
        SysUser user = new SysUser();
        user.setUsername("testuser");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setNickname("Test");
        user.setEmail("test@example.com");
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserRepository.insert(user);

        String id = sysUserRepository.selectList(null).get(0).getId();

        mockMvc.perform(delete("/lg/system/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/lg/system/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void testUpdateUserStatus() throws Exception {
        SysUser user = new SysUser();
        user.setUsername("testuser");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setNickname("Test");
        user.setEmail("test@example.com");
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserRepository.insert(user);

        String id = sysUserRepository.selectList(null).get(0).getId();

        mockMvc.perform(put("/lg/system/users/{id}/status", id)
                        .param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGetAllUsers() throws Exception {
        // /lg/system/users/all 已下线（与 /lg/system/users/list 重复）；list 端点已由 testListUsers 覆盖
        mockMvc.perform(get("/lg/system/users/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
