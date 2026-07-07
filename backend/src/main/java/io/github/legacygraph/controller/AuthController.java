package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.LoginRequest;
import io.github.legacygraph.dto.LoginResponse;
import io.github.legacygraph.entity.SysUser;
import io.github.legacygraph.repository.SysUserRepository;
import io.github.legacygraph.service.system.AuthSessionService;
import io.github.legacygraph.service.system.TokenBlacklistService;
import io.github.legacygraph.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 用户认证控制器
 * 处理用户登录、登出、刷新令牌等认证相关操作
 */
@RestController
@RequestMapping("/lg/auth")
@Tag(name = "用户认证", description = "登录、登出、获取用户信息、刷新令牌等认证相关操作")
public class AuthController {

    private final SysUserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final AuthSessionService authSessionService;

    /**
     * 构造函数注入
     * @param userRepository 用户数据访问层
     * @param jwtUtil JWT工具
     * @param tokenBlacklistService JWT 登出黑名单服务
     * @param authSessionService 认证会话服务（Redis 存储登录信息）
     */
    public AuthController(SysUserRepository userRepository, JwtUtil jwtUtil,
                          TokenBlacklistService tokenBlacklistService,
                          AuthSessionService authSessionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
        this.authSessionService = authSessionService;
    }

    /**
     * 用户登录
     * 验证用户名密码，返回访问令牌和用户信息
     * 如果是首次登录且用户名为admin，会自动创建默认管理员账号
     * @param request 登录请求，包含用户名和密码
     * @return 登录结果，包含令牌和用户信息
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "使用用户名密码登录系统，返回访问令牌和用户信息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "400", description = "用户名或密码错误，或用户已被禁用")
    })
    @Log(value = "用户登录", type = Log.OperationType.LOGIN)
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, request.getUsername());
        SysUser user = userRepository.selectOne(wrapper);

        if (user == null) {
            if ("admin".equals(request.getUsername())) {
                user = new SysUser();
                user.setId(UUID.randomUUID().toString());
                user.setUsername("admin");
                user.setPassword(passwordEncoder.encode("admin123"));
                user.setNickname("管理员");
                user.setEmail("admin@legacygraph.io");
                user.setRoles("ADMIN");
                user.setPermissions("*");
                user.setStatus("ACTIVE");
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

        List<String> roles = user.getRoles() != null
                ? Arrays.asList(user.getRoles().split(","))
                : List.of("USER");

        String accessToken = jwtUtil.generateToken(user.getId(), user.getUsername(), roles);
        String refreshToken = jwtUtil.generateToken(user.getId(), user.getUsername(), roles);

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getAvatar(),
                roles,
                List.of("*")
        );

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.updateUserById(user);

        LoginResponse response = new LoginResponse(
                accessToken,
                refreshToken,
                7200L,
                userInfo
        );

        // 将登录会话信息存入 Redis
        authSessionService.saveSession(accessToken, refreshToken, userInfo, Duration.ofSeconds(7200));

        return Result.success(response);
    }

    /**
     * 用户登出
     * 客户端清除本地令牌即可，服务端当前不需要处理
     * @param token 访问令牌（可选）
     * @return 成功结果
     */
    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "将当前令牌加入黑名单，并清除 Redis 中的会话信息")
    @Log(value = "用户登出", type = Log.OperationType.LOGOUT)
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        if (token != null && token.startsWith("Bearer ")) {
            String jwt = token.substring(7);
            tokenBlacklistService.blacklist(jwt);
            // 清除 Redis 中的会话信息
            authSessionService.removeSession(jwt);
        }
        return Result.success();
    }

    /**
     * 获取当前登录用户信息
     * @param authorization 访问令牌（可选）
     * @return 当前用户信息
     */
    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的基本信息，包括角色和权限")
    @Log(value = "获取当前用户信息", type = Log.OperationType.QUERY)
    public ResponseEntity<Result<LoginResponse.UserInfo>> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // 从 token 中获取用户信息
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Result.error("用户未登录"));
        }
        String token = authorization.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return ResponseEntity.status(401).body(Result.error("Token无效或已过期"));
        }

        String username = jwtUtil.getUsernameFromToken(token);
        SysUser user = userRepository.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)
        );

        if (user == null) {
            return ResponseEntity.status(401).body(Result.error("用户未登录"));
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            return ResponseEntity.status(403).body(Result.error("用户已被禁用"));
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
                user.getNickname(),
                user.getEmail(),
                user.getAvatar(),
                roles,
                permissions
        );

        return ResponseEntity.ok(Result.success(userInfo));
    }

    /**
     * 刷新访问令牌
     * 使用刷新令牌获取新的访问令牌
     * @param refreshToken 刷新令牌
     * @return 新的登录响应，包含新的访问令牌和刷新令牌
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新访问令牌", description = "使用刷新令牌获取新的访问令牌，延长登录有效期")
    @Log(value = "刷新Token", type = Log.OperationType.OTHER)
    public ResponseEntity<Result<LoginResponse>> refreshToken(@RequestBody String refreshToken) {
        if (!jwtUtil.validateToken(refreshToken)) {
            return ResponseEntity.status(401).body(Result.error("Token无效或已过期"));
        }

        String username = jwtUtil.getUsernameFromToken(refreshToken);
        String userId = jwtUtil.getUserIdFromToken(refreshToken);
        SysUser user = userRepository.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)
        );

        if (user == null) {
            return ResponseEntity.status(401).body(Result.error("Token无效或已过期"));
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            return ResponseEntity.status(403).body(Result.error("用户已被禁用"));
        }

        List<String> roles = user.getRoles() != null
                ? Arrays.asList(user.getRoles().split(","))
                : List.of("USER");
        List<String> permissions = user.getPermissions() != null
                ? Arrays.asList(user.getPermissions().split(","))
                : List.of("read", "write");

        String accessToken = jwtUtil.generateToken(user.getId(), user.getUsername(), roles);
        String newRefreshToken = jwtUtil.generateToken(user.getId(), user.getUsername(), roles);

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
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

        // 刷新会话信息到 Redis
        authSessionService.saveSession(accessToken, newRefreshToken, userInfo, Duration.ofSeconds(7200));

        return ResponseEntity.ok(Result.success(response));
    }
}
