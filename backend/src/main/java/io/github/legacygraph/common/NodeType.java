package io.github.legacygraph.common;

/**
 * 图谱节点类型
 */
public enum NodeType {

    Project("项目"),
    System("系统"),
    BusinessDomain("业务域"),
    BusinessProcess("业务流程"),
    BusinessObject("业务对象"),
    BusinessRule("业务规则"),
    Role("业务角色"),
    FeatureModule("功能模块"),
    Feature("功能点"),
    Menu("菜单"),
    Page("页面"),
    Button("按钮"),
    Permission("权限"),
    ApiEndpoint("接口"),
    Controller("控制器"),
    Service("服务类"),
    Method("方法"),
    Mapper("Mapper"),
    SqlStatement("SQL语句"),
    Table("数据库表"),
    Index("数据库索引"),
    Column("数据库字段"),
    ConfigItem("配置项"),
    ScheduledJob("定时任务"),
    MQConsumer("消息消费者"),
    MQTopic("消息主题"),
    ExternalSystem("外部系统"),
    TestCase("测试用例"),
    Assertion("断言"),
    Evidence("证据"),
    User("用户"),

    // ========== 代码包（架构依赖链路） ==========
    Package("代码包"),

    // ========== 变更闭环节点（增强版2：ChangeTask 管道） ==========
    ChangeTask("变更任务"),
    Patch("补丁"),
    PullRequest("PR"),
    Dependency("依赖"),
    VersionRisk("版本风险");

    private final String description;

    NodeType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
