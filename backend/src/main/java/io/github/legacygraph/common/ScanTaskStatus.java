package io.github.legacygraph.common;

/**
 * 扫描子任务状态枚举（H15）。
 *
 * <p>用于 {@code lg_scan_task.task_status} 字段，收编原先散落在各处的字符串字面量，
 * 防止拼写错误。数据库仍存 VARCHAR（enum name），实体字段保留 String 类型，
 * 所有赋值改为 {@code setTaskStatus(ScanTaskStatus.XXX.name())}。</p>
 *
 * <p>状态流转：QUEUED → RUNNING → {SUCCESS | FAILED | WARNING | SKIPPED | REJECTED | CANCELLED}。
 * COMPLETED/PENDING 为旧值兼容（等价于 SUCCESS/QUEUED），新代码不应使用。</p>
 */
public enum ScanTaskStatus {
    QUEUED("排队中"),
    RUNNING("运行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    WARNING("部分成功/有警告"),
    SKIPPED("已跳过"),
    REJECTED("已拒绝"),
    CANCELLED("已取消"),
    /** 旧值兼容，等价于 SUCCESS，新代码不应使用 */
    @Deprecated
    COMPLETED("已完成"),
    /** 旧值兼容，等价于 QUEUED，新代码不应使用 */
    @Deprecated
    PENDING("等待中");

    private final String description;

    ScanTaskStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 从字符串安全转换为枚举，无法匹配时返回 null（不抛异常，避免阻断扫描流程）。
     */
    public static ScanTaskStatus from(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return ScanTaskStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
