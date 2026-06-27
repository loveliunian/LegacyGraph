package io.github.legacygraph.common;

/**
 * 错误码枚举
 */
public enum ErrorCode {

    SUCCESS(0, "操作成功"),

    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),

    SERVER_ERROR(500, "服务器内部错误"),

    USER_NOT_FOUND(1001, "用户不存在"),
    USER_PASSWORD_ERROR(1002, "密码错误"),
    USER_DISABLED(1003, "用户已被禁用"),

    PROJECT_NOT_FOUND(2001, "项目不存在"),
    PROJECT_CODE_EXISTS(2002, "项目编码已存在"),

    SOURCE_NOT_FOUND(3001, "资料不存在"),
    FILE_SIZE_EXCEED(3002, "文件大小超过限制"),
    FILE_TYPE_NOT_ALLOWED(3003, "文件类型不允许"),
    FILE_UPLOAD_FAILED(3004, "文件上传失败"),

    SCAN_TASK_NOT_FOUND(4001, "扫描任务不存在"),

    TEST_CASE_NOT_FOUND(5001, "测试用例不存在"),
    TEST_RUN_NOT_FOUND(5002, "测试执行不存在");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
