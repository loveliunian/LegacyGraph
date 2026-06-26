package io.github.legacygraph.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * API抽取结果事实
 */
@Data
public class ApiFact {

    private String httpMethod;
    private String path;
    private String fullPath;
    private String controllerClass;
    private String controllerPackage;
    private String methodName;
    private List<ApiParameter> requestParams;
    private ApiParameter requestBody;
    private String responseType;
    private List<String> annotations;
    private List<String> permissions;
    private String sourcePath;
    private Integer startLine;
    private Integer endLine;

    @Data
    public static class ApiParameter {
        private String name;
        private String type;
        private boolean required;
        private String defaultValue;
        private String description;
    }
}
