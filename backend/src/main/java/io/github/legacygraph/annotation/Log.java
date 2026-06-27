package io.github.legacygraph.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /**
     * 操作描述
     */
    String value() default "";

    /**
     * 操作类型
     */
    OperationType type() default OperationType.OTHER;

    /**
     * 是否记录请求参数
     */
    boolean logParams() default true;

    /**
     * 是否记录返回结果
     */
    boolean logResult() default true;

    /**
     * 慢请求阈值（毫秒），超过则记录告警
     */
    long slowRequestThreshold() default 3000;

    /**
     * 操作类型枚举
     */
    enum OperationType {
        CREATE,      // 新增
        UPDATE,      // 修改
        DELETE,      // 删除
        QUERY,       // 查询
        UPLOAD,      // 上传
        DOWNLOAD,    // 下载
        IMPORT,      // 导入
        EXPORT,      // 导出
        SCAN,        // 扫描
        TEST,        // 测试
        REVIEW,      // 审核
        REPORT,      // 报告
        LOGIN,       // 登录
        LOGOUT,      // 登出
        OTHER        // 其他
    }
}
