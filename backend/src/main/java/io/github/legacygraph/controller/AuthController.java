package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.LoginRequest;
import io.github.legacygraph.dto.LoginResponse;
import io.github.legacygraph.entity.User;
import io.github.legacygraph.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/lg/auth")
@Tag(name = "用户认证", description = "登录、登出、获取用户信息等")
public class AuthController {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    @Log(value = "用户登录", type = Log.OperationType.LOGIN)
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User user = userRepository.selectOne(wrapper);

        if (user == null) {
            if ("admin".equals(request.getUsername())) {
                user = new User();
                user.setId(UUID.randomUUID().toString());
                user.setUsername("admin");
                user.setPassword(passwordEncoder.encode("admin123"));
                user.setDisplayName("管理员");
                user.setEmail("admin@legacygraph.io");
                user.setStatus("ACTIVE");
                user.setRoles("ADMIN,USER");
                user.setPermissions("*");
                user.setCreatedAt(LocalDateTime.now());
                user.setUpdatedAt(LocalDateTime.now());
                userRepository.insert(user);
            } else {
                return Result.error("用户名或密码错误");
            }
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return Result.error("用户名或密码错误");
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            return Result.error("用户已被禁用");
        }

        String accessToken = UUID.randomUUID().toString().replace("-", "");
        String refreshToken = UUID.randomUUID().toString().replace("-", "");

        List<String> roles = user.getRoles() != null
                ? Arrays.asList(user.getRoles().split(","))
                : List.of("USER");
        List<String> permissions = user.getPermissions() != null
                ? Arrays.asList(user.getPermissions().split(","))
                : List.of("read", "write");

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getAvatar(),
                roles,
                permissions
        );

        user.setLastLoginTime(LocalDateTime.now());
        userRepository.updateById(user);

        LoginResponse response = new LoginResponse(
                accessToken,
                refreshToken,
                7200L,
                userInfo
        );

        return Result.success(response);
    }

    @PostMapping("/logout")
    @Operation(summary = "用户登出")
    @Log(value = "用户登出", type = Log.OperationType.LOGOUT)
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        return Result.success();
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息")
    @Log(value = "获取当前用户信息", type = Log.OperationType.QUERY)
    public Result<LoginResponse.UserInfo> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String token) {
        User user = userRepository.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, "admin")
        );

        if (user == null) {
            return Result.error("用户未登录");
        }

        List<String> roles = user.getRoles() != null
                ? Arrays.asList(user.getRoles().split(","))
                : List.of("USER");
        List<String> permissions = user.getPermissions() != null
                ? Arrays.asList(user.getPermissions().split(","))
                : List.of("read", "write");

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getAvatar(),
                roles,
                permissions
        );

        return Result.success(userInfo);
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新Token")
    @Log(value = "刷新Token", type = Log.OperationType.OTHER)
    public Result<LoginResponse> refreshToken(@RequestBody String refreshToken) {
        User user = userRepository.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, "admin")
        );

        if (user == null) {
            return Result.error("Token无效或已过期");
        }

        String accessToken = UUID.randomUUID().toString().replace("-", "");
        String newRefreshToken = UUID.randomUUID().toString().replace("-", "");

        List<String> roles = user.getRoles() != null
                ? Arrays.asList(user.getRoles().split(","))
                : List.of("USER");
        List<String> permissions = user.getPermissions() != null
                ? Arrays.asList(user.getPermissions().split(","))
                : List.of("read", "write");

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getAvatar(),
                roles,
                permissions
        );

        LoginResponse response = new LoginResponse(
                accessToken,
                newRefreshToken,
                7200L,
                userInfo
        );

        return Result.success(response);
    }
}
