package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.LoginRequest;
import io.github.legacygraph.entity.User;
import io.github.legacygraph.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.delete(null);
    }

    @Test
    void testLogin_Success() throws Exception {
        User user = new User();
        user.setId("test-user-1");
        user.setUsername("testuser");
        user.setPassword("password123");
        user.setDisplayName("Test User");
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
                .andExpect(jsonPath("$.code").value(200))
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
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testLogin_WrongPassword() throws Exception {
        User user = new User();
        user.setId("test-user-2");
        user.setUsername("testuser2");
        user.setPassword("correctpassword");
        user.setDisplayName("Test User 2");
        user.setStatus("ACTIVE");
        userRepository.insert(user);

        LoginRequest request = new LoginRequest();
        request.setUsername("testuser2");
        request.setPassword("wrongpassword");

        mockMvc.perform(post("/lg/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testLogin_InactiveUser() throws Exception {
        User user = new User();
        user.setId("test-user-3");
        user.setUsername("inactiveuser");
        user.setPassword("password");
        user.setDisplayName("Inactive User");
        user.setStatus("INACTIVE");
        userRepository.insert(user);

        LoginRequest request = new LoginRequest();
        request.setUsername("inactiveuser");
        request.setPassword("password");

        mockMvc.perform(post("/lg/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testLogout_Success() throws Exception {
        mockMvc.perform(post("/lg/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
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
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.user.username").value("admin"))
                .andExpect(jsonPath("$.data.user.displayName").value("管理员"));
    }
}
