package io.github.legacygraph.extractors.bpmn;

/**
 * 流程引擎类型。
 */
public enum EngineType {
    /** Flowable (act_ 表前缀) */
    FLOWABLE,
    /** Activiti (act_ 表前缀) */
    ACTIVITI,
    /** Camunda (act_ 表前缀) */
    CAMUNDA,
    /** 自研流程引擎 (业务表,需配置表名映射) */
    CUSTOM
}
