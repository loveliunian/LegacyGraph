package io.github.legacygraph.model;

import lombok.Data;

import java.util.List;

/**
 * 前端页面抽取结果事实
 */
@Data
public class FrontendPageFact {

    private String pageName;
    private Integer startLine;
    private Integer endLine;
    private String routePath;
    private String routeName;
    private String componentPath;
    private String title;
    private String permission;
    private String icon;
    private List<FrontendPageFact> children;
    private List<FrontendApiCall> apiCalls;
    private List<FrontendButton> buttons;
    private List<String> permissions;

    @Data
    public static class FrontendApiCall {
        private String url;
        private String method;
        private String functionName;
        private String sourceFile;
        private Integer lineNumber;
    }

    @Data
    public static class FrontendButton {
        private String text;
        private String clickMethod;
        private String permission;
        private String apiUrl;
        /** G9: 按钮 HTTP method（get/post/put/delete），缺失时默认 POST */
        private String httpMethod;
        private Integer lineNumber;
    }
}
