package io.github.legacygraph.controller;

import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.SysConfig;
import io.github.legacygraph.entity.SysDict;
import io.github.legacygraph.entity.SysDictItem;
import io.github.legacygraph.entity.SysUser;
import io.github.legacygraph.service.SysConfigService;
import io.github.legacygraph.service.SysDictService;
import io.github.legacygraph.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 系统管理控制器
 * 包含用户管理、字典管理、系统配置
 */
@RestController
@RequestMapping("/lg/system")
@Tag(name = "系统管理", description = "用户管理、字典管理、系统配置")
public class SystemController {

    private final SysUserService sysUserService;
    private final SysDictService sysDictService;
    private final SysConfigService sysConfigService;

    public SystemController(SysUserService sysUserService, SysDictService sysDictService, SysConfigService sysConfigService) {
        this.sysUserService = sysUserService;
        this.sysDictService = sysDictService;
        this.sysConfigService = sysConfigService;
    }

    // ==================== 用户管理 ====================

    @GetMapping("/users/list")
    @Operation(summary = "分页查询用户列表")
    @Log(value = "查询用户列表", type = Log.OperationType.QUERY)
    public Result<PageResult<SysUser>> listUsers(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        PageResult<SysUser> result = sysUserService.list(pageNum, pageSize, keyword, status);
        return Result.success(result);
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "获取用户详情")
    @Log(value = "查看用户详情", type = Log.OperationType.QUERY)
    public Result<SysUser> getUser(@PathVariable String id) {
        SysUser user = sysUserService.getById(id);
        return Result.success(user);
    }

    @GetMapping("/users/all")
    @Operation(summary = "获取所有用户列表")
    public Result<List<SysUser>> listAllUsers() {
        List<SysUser> users = sysUserService.listAll();
        return Result.success(users);
    }

    @PostMapping("/users")
    @Operation(summary = "创建用户")
    @Log(value = "创建用户", type = Log.OperationType.CREATE)
    public Result<SysUser> createUser(@Valid @RequestBody SysUser user) {
        if (sysUserService.getByUsername(user.getUsername()) != null) {
            return Result.error("用户名已存在");
        }
        SysUser created = sysUserService.create(user);
        return Result.success(created);
    }

    @PutMapping("/users/{id}")
    @Operation(summary = "更新用户")
    @Log(value = "更新用户", type = Log.OperationType.UPDATE)
    public Result<Void> updateUser(@PathVariable String id, @Valid @RequestBody SysUser user) {
        user.setId(id);
        boolean updated = sysUserService.update(user);
        if (updated) {
            return Result.success();
        } else {
            return Result.error("用户不存在");
        }
    }

    @DeleteMapping("/users/{id}")
    @Operation(summary = "删除用户")
    @Log(value = "删除用户", type = Log.OperationType.DELETE)
    public Result<Void> deleteUser(@PathVariable String id) {
        boolean deleted = sysUserService.delete(id);
        if (deleted) {
            return Result.success();
        } else {
            return Result.error("删除失败，用户不存在");
        }
    }

    @PutMapping("/users/{id}/status")
    @Operation(summary = "更新用户状态")
    @Log(value = "更新用户状态", type = Log.OperationType.UPDATE)
    public Result<Void> updateUserStatus(@PathVariable String id, @RequestParam String status) {
        boolean updated = sysUserService.updateStatus(id, status);
        if (updated) {
            return Result.success();
        } else {
            return Result.error("用户不存在");
        }
    }

    // ==================== 字典管理 ====================

    @GetMapping("/dicts/list")
    @Operation(summary = "分页查询字典类型")
    @Log(value = "查询字典类型列表", type = Log.OperationType.QUERY)
    public Result<PageResult<SysDict>> listDicts(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        PageResult<SysDict> result = sysDictService.list(pageNum, pageSize, keyword, status);
        return Result.success(result);
    }

    @GetMapping("/dicts/all")
    @Operation(summary = "获取所有字典类型")
    public Result<List<SysDict>> listAllDicts() {
        List<SysDict> dicts = sysDictService.listAll();
        return Result.success(dicts);
    }

    @GetMapping("/dicts/{id}")
    @Operation(summary = "获取字典类型详情")
    @Log(value = "查看字典详情", type = Log.OperationType.QUERY)
    public Result<SysDict> getDict(@PathVariable String id) {
        SysDict dict = sysDictService.getById(id);
        return Result.success(dict);
    }

    @PostMapping("/dicts")
    @Operation(summary = "创建字典类型")
    @Log(value = "创建字典类型", type = Log.OperationType.CREATE)
    public Result<SysDict> createDict(@Valid @RequestBody SysDict dict) {
        if (sysDictService.getByCode(dict.getDictCode()) != null) {
            return Result.error("字典编码已存在");
        }
        SysDict created = sysDictService.create(dict);
        return Result.success(created);
    }

    @PutMapping("/dicts/{id}")
    @Operation(summary = "更新字典类型")
    @Log(value = "更新字典类型", type = Log.OperationType.UPDATE)
    public Result<Void> updateDict(@PathVariable String id, @Valid @RequestBody SysDict dict) {
        dict.setId(id);
        boolean updated = sysDictService.update(dict);
        if (updated) {
            return Result.success();
        } else {
            return Result.error("字典不存在");
        }
    }

    @DeleteMapping("/dicts/{id}")
    @Operation(summary = "删除字典类型")
    @Log(value = "删除字典类型", type = Log.OperationType.DELETE)
    public Result<Void> deleteDict(@PathVariable String id) {
        boolean deleted = sysDictService.delete(id);
        if (deleted) {
            return Result.success();
        } else {
            return Result.error("删除失败，字典不存在");
        }
    }

    @GetMapping("/dicts/{id}/items")
    @Operation(summary = "获取字典下所有项")
    public Result<List<SysDictItem>> getDictItems(@PathVariable String id) {
        List<SysDictItem> items = sysDictService.getItemsByDictId(id);
        return Result.success(items);
    }

    @GetMapping("/dicts/code/{code}/items")
    @Operation(summary = "根据字典编码获取所有项")
    public Result<List<SysDictItem>> getDictItemsByCode(@PathVariable String code) {
        List<SysDictItem> items = sysDictService.getItemsByDictCode(code);
        return Result.success(items);
    }

    @GetMapping("/dicts/code/{code}/map")
    @Operation(summary = "根据字典编码获取值-标签映射")
    public Result<Map<String, String>> getDictItemMap(@PathVariable String code) {
        Map<String, String> map = sysDictService.getItemMap(code);
        return Result.success(map);
    }

    @PostMapping("/dicts/items")
    @Operation(summary = "创建字典项")
    @Log(value = "创建字典项", type = Log.OperationType.CREATE)
    public Result<SysDictItem> createDictItem(@Valid @RequestBody SysDictItem item) {
        SysDictItem created = sysDictService.createItem(item);
        return Result.success(created);
    }

    @PutMapping("/dicts/items/{id}")
    @Operation(summary = "更新字典项")
    @Log(value = "更新字典项", type = Log.OperationType.UPDATE)
    public Result<Void> updateDictItem(@PathVariable String id, @Valid @RequestBody SysDictItem item) {
        item.setId(id);
        boolean updated = sysDictService.updateItem(item);
        if (updated) {
            return Result.success();
        } else {
            return Result.error("字典项不存在");
        }
    }

    @DeleteMapping("/dicts/items/{id}")
    @Operation(summary = "删除字典项")
    @Log(value = "删除字典项", type = Log.OperationType.DELETE)
    public Result<Void> deleteDictItem(@PathVariable String id) {
        boolean deleted = sysDictService.deleteItem(id);
        if (deleted) {
            return Result.success();
        } else {
            return Result.error("删除失败，字典项不存在");
        }
    }

    // ==================== 系统配置 ====================

    @GetMapping("/configs/list")
    @Operation(summary = "分页查询系统配置")
    @Log(value = "查询系统配置列表", type = Log.OperationType.QUERY)
    public Result<PageResult<SysConfig>> listConfigs(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        PageResult<SysConfig> result = sysConfigService.list(pageNum, pageSize, keyword, status);
        return Result.success(result);
    }

    @GetMapping("/configs/{id}")
    @Operation(summary = "获取系统配置详情")
    @Log(value = "查看配置详情", type = Log.OperationType.QUERY)
    public Result<SysConfig> getConfig(@PathVariable String id) {
        SysConfig config = sysConfigService.getById(id);
        return Result.success(config);
    }

    @GetMapping("/configs/key/{key}")
    @Operation(summary = "根据键获取配置值")
    public Result<String> getConfigValue(@PathVariable String key) {
        String value = sysConfigService.getValue(key);
        return Result.success(value);
    }

    @GetMapping("/configs/all")
    @Operation(summary = "获取所有配置键值对")
    public Result<Map<String, String>> getAllConfigMap() {
        Map<String, String> map = sysConfigService.getAllConfigMap();
        return Result.success(map);
    }

    @PostMapping("/configs")
    @Operation(summary = "创建系统配置")
    @Log(value = "创建系统配置", type = Log.OperationType.CREATE)
    public Result<SysConfig> createConfig(@Valid @RequestBody SysConfig config) {
        if (sysConfigService.getByKey(config.getConfigKey()) != null) {
            return Result.error("配置键已存在");
        }
        SysConfig created = sysConfigService.create(config);
        return Result.success(created);
    }

    @PutMapping("/configs/{id}")
    @Operation(summary = "更新系统配置")
    @Log(value = "更新系统配置", type = Log.OperationType.UPDATE)
    public Result<Void> updateConfig(@PathVariable String id, @Valid @RequestBody SysConfig config) {
        config.setId(id);
        boolean updated = sysConfigService.update(config);
        if (updated) {
            return Result.success();
        } else {
            return Result.error("配置不存在");
        }
    }

    @PutMapping("/configs/key/{key}")
    @Operation(summary = "更新配置值")
    @Log(value = "更新配置值", type = Log.OperationType.UPDATE)
    public Result<Void> updateConfigValue(@PathVariable String key, @RequestBody String value) {
        boolean updated = sysConfigService.updateValue(key, value);
        if (updated) {
            return Result.success();
        } else {
            return Result.error("配置键不存在");
        }
    }

    @DeleteMapping("/configs/{id}")
    @Operation(summary = "删除系统配置")
    @Log(value = "删除系统配置", type = Log.OperationType.DELETE)
    public Result<Void> deleteConfig(@PathVariable String id) {
        boolean deleted = sysConfigService.delete(id);
        if (deleted) {
            return Result.success();
        } else {
            return Result.error("删除失败，配置不存在");
        }
    }
}
