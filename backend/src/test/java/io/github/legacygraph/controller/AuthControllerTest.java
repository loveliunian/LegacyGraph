package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.LoginRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userRepository.delete(new QueryWrapper<>());
    }

    @Test
    void testLogin_Success() throws Exception {
        SysUser user = new SysUser();
        user.setId("00000000-0000-0000-0000-000000000001");
        user.setUsername("testuser");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setNickname("Test User");
        user.setEmail("test@example.com");
        user.setStatus("ACTIVE");
        user.setRoles("USER");
        userRepository.insert(user);

        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("password123");

        mockMvc.perform(post("/lg/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.user.username").value("testuser"));
    }

    @Test
    void testLogin_UserNotFound() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("nonexistent");
        request.setPassword("password");

        mockMvc.perform(post("/lg/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testLogin_WrongPassword() throws Exception {
        SysUser user = new SysUser();
        user.setId("00000000-0000-0000-0000-000000000002");
        user.setUsername("testuser2");
        user.setPassword(passwordEncoder.encode("correctpassword"));
        user.setNickname("Test User 2");
        user.setStatus("ACTIVE");
        userRepository.insert(user);

        LoginRequest request = new LoginRequest();
        request.setUsername("testuser2");
        request.setPassword("wrongpassword");

        mockMvc.perform(post("/lg/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testLogin_InactiveUser() throws Exception {
        SysUser user = new SysUser();
        user.setId("00000000-0000-0000-0000-000000000003");
        user.setUsername("inactiveuser");
        user.setPassword(passwordEncoder.encode("password"));
        user.setNickname("Inactive User");
        user.setStatus("INACTIVE");
        userRepository.insert(user);

        LoginRequest request = new LoginRequest();
        request.setUsername("inactiveuser");
        request.setPassword("password");

        mockMvc.perform(post("/lg/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testLogout_Success() throws Exception {
        mockMvc.perform(post("/lg/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testAdminAutoCreation() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        mockMvc.perform(post("/lg/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.user.username").value("admin"))
                .andExpect(jsonPath("$.data.user.nickname").value("管理员"));
    }
}
