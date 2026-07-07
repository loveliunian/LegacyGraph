package io.github.legacygraph.extractors;

import lombok.extern.slf4j.Slf4j;
import io.github.legacygraph.model.FrontendPageFact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前端API调用抽取器
 * 从前端 .ts/.js/.vue 文件中抽取 axios/request/fetch API 调用
 */
@Slf4j
public class FrontendApiExtractor {

    private static final Pattern URL_PATTERN = Pattern.compile("url\\s*:\\s*([`'\"])(.*?)\\1");
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "method\\s*:\\s*['\"](get|post|put|delete|patch)['\"]",
            Pattern.CASE_INSENSITIVE);

    /**
     * 从文件抽取API调用
     */
    public List<FrontendPageFact.FrontendApiCall> extractFromFile(Path file) throws IOException {
        List<FrontendPageFact.FrontendApiCall> result = new ArrayList<>();
        String content = Files.readString(file);
        String[] lines = content.split("\n");

        // 匹配各种API调用模式
        // 1. axios.get/post/put/delete
        extractAxiosCalls(lines, result, file);

        // 2. 封装的 get/post/put/delete('/url') helper
        extractRequestHelperCalls(lines, result, file);

        // 3. 封装的 request({ url, method })
        extractRequestCalls(lines, result, file);

        // 4. api.xxx() 调用
        extractApiModuleCalls(lines, result, file);

        // 5. fetch(url)
        extractFetchCalls(lines, result, file);

        log.debug("Extracted {} API calls from {}", result.size(), file);
        return result;
    }

    /**
     * 抽取 axios.get/post/put/delete
     */
    private void extractAxiosCalls(String[] lines, List<FrontendPageFact.FrontendApiCall> result, Path file) {
        Pattern pattern = Pattern.compile("axios\\.(get|post|put|delete|patch)\\(\\s*['\"]([^'\"]+)['\"]");
        extractByPattern(pattern, 1, 2, lines, result, file);
    }

    /**
     * 抽取项目常用的 request helper：get('/xxx') / post(`/xxx/${id}`)
     */
    private void extractRequestHelperCalls(String[] lines, List<FrontendPageFact.FrontendApiCall> result, Path file) {
        Pattern pattern = Pattern.compile(
                "(?<![\\w.])(get|post|put|delete|patch)\\s*(?:<[^>]+>)?\\s*\\(\\s*([`'\"])([^`'\"]+)\\2",
                Pattern.CASE_INSENSITIVE);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                String trailing = line.substring(matcher.end()).stripLeading();
                if (trailing.startsWith("+")) {
                    continue;
                }
                FrontendPageFact.FrontendApiCall call = new FrontendPageFact.FrontendApiCall();
                call.setMethod(matcher.group(1).toLowerCase());
                call.setUrl(matcher.group(3));
                call.setSourceFile(file.toString());
                call.setLineNumber(i + 1);
                result.add(call);
            }
        }
    }

    /**
     * 抽取 request({ url: '/xxx', method: 'post' })
     */
    private void extractRequestCalls(String[] lines, List<FrontendPageFact.FrontendApiCall> result, Path file) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.contains("request(")) {
                continue;
            }

            StringBuilder block = new StringBuilder();
            int urlLineNumber = i + 1;
            for (int j = i; j < lines.length; j++) {
                block.append(lines[j]).append('\n');
                if (URL_PATTERN.matcher(lines[j]).find()) {
                    urlLineNumber = j + 1;
                }
                if (lines[j].contains("})") || lines[j].contains("});")) {
                    break;
                }
            }

            Matcher urlMatcher = URL_PATTERN.matcher(block);
            if (urlMatcher.find()) {
                Matcher methodMatcher = METHOD_PATTERN.matcher(block);
                String method = methodMatcher.find() ? methodMatcher.group(1).toLowerCase() : "get";
                FrontendPageFact.FrontendApiCall call = new FrontendPageFact.FrontendApiCall();
                call.setUrl(urlMatcher.group(2));
                call.setMethod(method);
                call.setSourceFile(file.toString());
                call.setLineNumber(urlLineNumber);
                result.add(call);
            }
        }
    }

    /**
     * 抽取 api 模块中的调用
     * export function xxx(data) { return request({ ... }) }
     */
    private void extractApiModuleCalls(String[] lines, List<FrontendPageFact.FrontendApiCall> result, Path file) {
        boolean inFunction = false;
        String currentFunction = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            // 函数定义
            Pattern funcPattern = Pattern.compile("export\\s+function\\s+(\\w+)");
            Matcher funcMatcher = funcPattern.matcher(line);
            if (funcMatcher.find()) {
                inFunction = true;
                currentFunction = funcMatcher.group(1);
            }

            if (inFunction) {
                Pattern urlPattern = Pattern.compile("url\\s*:\\s*['\"]([^'\"]+)['\"]");
                Matcher urlMatcher = urlPattern.matcher(line);
                if (urlMatcher.find()) {
                    String url = urlMatcher.group(1);

                    Matcher methodMatcher = METHOD_PATTERN.matcher(line);
                    String method = "get";
                    if (methodMatcher.find()) {
                        method = methodMatcher.group(1).toLowerCase();
                    }

                    FrontendPageFact.FrontendApiCall call = new FrontendPageFact.FrontendApiCall();
                    call.setUrl(url);
                    call.setMethod(method);
                    call.setFunctionName(currentFunction);
                    call.setSourceFile(file.toString());
                    call.setLineNumber(lineNum);
                    result.add(call);
                }
            }

            // 函数结束
            if (line.contains("}")) {
                inFunction = false;
                currentFunction = null;
            }
        }
    }

    /**
     * 抽取 fetch 调用
     */
    private void extractFetchCalls(String[] lines, List<FrontendPageFact.FrontendApiCall> result, Path file) {
        Pattern pattern = Pattern.compile("fetch\\(\\s*['\"]([^'\"]+)['\"]");
        extractByPattern(pattern, 1, 1, lines, result, file);
    }

    /**
     * 通用按模式提取
     */
    private void extractByPattern(Pattern pattern, int methodGroup, int urlGroup,
            String[] lines, List<FrontendPageFact.FrontendApiCall> result, Path file) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                String method = methodGroup == urlGroup ? "get" : matcher.group(methodGroup).toLowerCase();
                String url = matcher.group(urlGroup);

                FrontendPageFact.FrontendApiCall call = new FrontendPageFact.FrontendApiCall();
                call.setUrl(url);
                call.setMethod(method);
                call.setSourceFile(file.toString());
                call.setLineNumber(lineNum);
                result.add(call);
            }
        }
    }

    /**
     * 从Vue单文件组件抽取按钮和权限
     */
    public List<FrontendPageFact.FrontendButton> extractButtonsFromVue(Path file) throws IOException {
        List<FrontendPageFact.FrontendButton> result = new ArrayList<>();
        String content = Files.readString(file);
        String[] lines = content.split("\n");

        // 匹配 <el-button ...> 或者 <button ...>
        Pattern buttonPattern = Pattern.compile("<(el-button|button)[^>]*>");
        // 匹配 @click="xxx"
        Pattern clickPattern = Pattern.compile("@click\\s*=\\s*['\"]([^'\"]+)['\"]");
        // 匹配 v-permission="xxx" 或者 has-permission="xxx"
        Pattern permPattern = Pattern.compile("(v-permission|has-permission|permission)\\s*=\\s*(['\"])(.*?)\\2");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            Matcher buttonMatcher = buttonPattern.matcher(line);
            while (buttonMatcher.find()) {
                FrontendPageFact.FrontendButton button = new FrontendPageFact.FrontendButton();
                button.setLineNumber(lineNum);

                // 提取按钮文本
                // 简化处理，查找 /> 前的文本
                int buttonStart = buttonMatcher.start();
                int endPos = line.indexOf("/>", buttonStart);
                if (endPos == -1) endPos = line.indexOf(">", buttonStart);
                if (endPos != -1) {
                    String buttonBlock = line.substring(buttonStart, endPos);

                    // 查找click
                    Matcher clickMatcher = clickPattern.matcher(buttonBlock);
                    if (clickMatcher.find()) {
                        button.setClickMethod(clickMatcher.group(1));
                    }

                    // 查找权限
                    Matcher permMatcher = permPattern.matcher(buttonBlock);
                    if (permMatcher.find()) {
                        button.setPermission(cleanAttributeValue(permMatcher.group(3)));
                    }
                }

                // 提取文本（在下一行）
                if (endPos != -1 && endPos < line.length()) {
                    String text = line.substring(endPos + 1).trim();
                    if (!text.isEmpty() && !text.startsWith("/")) {
                        int textEnd = text.indexOf("<");
                        if (textEnd != -1) {
                            text = text.substring(0, textEnd).trim();
                        }
                        button.setText(text);
                    }
                }

                result.add(button);
            }
        }

        return result;
    }

    private String cleanAttributeValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        while ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\""))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }
}
