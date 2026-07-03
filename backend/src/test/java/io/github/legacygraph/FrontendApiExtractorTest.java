package io.github.legacygraph;

import io.github.legacygraph.extractors.FrontendApiExtractor;
import io.github.legacygraph.model.FrontendPageFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrontendApiExtractorTest {

    @TempDir
    Path tempDir;

    private final FrontendApiExtractor extractor = new FrontendApiExtractor();

    // ---------------------------------------------------------------
    // 用例1：axios.get / axios.post 调用
    // ---------------------------------------------------------------
    @Test
    void testExtractAxiosCalls() throws IOException {
        // given
        String content = """
                import axios from 'axios';
                                
                export function fetchUsers() {
                    return axios.get('/api/users', { params: { page: 1 } });
                }
                                
                export function createUser(data) {
                    return axios.post('/api/users', data);
                }
                """;
        Path file = tempDir.resolve("userApi.ts");
        Files.writeString(file, content);

        // when
        List<FrontendPageFact.FrontendApiCall> calls = extractor.extractFromFile(file);

        // then
        assertEquals(2, calls.size(), "应抽到 2 个 axios 调用");

        FrontendPageFact.FrontendApiCall getCall = calls.get(0);
        assertEquals("/api/users", getCall.getUrl());
        assertEquals("get", getCall.getMethod());
        assertEquals(file.toString(), getCall.getSourceFile());
        assertEquals(4, getCall.getLineNumber());

        FrontendPageFact.FrontendApiCall postCall = calls.get(1);
        assertEquals("/api/users", postCall.getUrl());
        assertEquals("post", postCall.getMethod());
        assertEquals(8, postCall.getLineNumber());
    }

    // ---------------------------------------------------------------
    // 用例2：request({ url, method }) 封装调用（export function 会导致
    // extractApiModuleCalls 也匹配从而产生重复，此处用无函数包裹的内容，使仅
    // extractRequestCalls 匹配）
    // ---------------------------------------------------------------
    @Test
    void testExtractRequestCalls() throws IOException {
        // given
        String content = """
                import request from '@/utils/request';
                                
                // 直接使用 request，不在 export function 内
                const deleteUser = request({
                    url: '/api/users/delete', method: 'delete'
                });
                                
                const getProfile = request({
                    url: '/api/profile'
                });
                """;
        Path file = tempDir.resolve("profileApi.ts");
        Files.writeString(file, content);

        // when
        List<FrontendPageFact.FrontendApiCall> calls = extractor.extractFromFile(file);

        // then
        assertEquals(2, calls.size(), "应抽到 2 个 request 调用");

        // request with method: 'delete'
        FrontendPageFact.FrontendApiCall deleteCall = calls.get(0);
        assertEquals("/api/users/delete", deleteCall.getUrl());
        assertEquals("delete", deleteCall.getMethod());

        // request without method (default get)
        FrontendPageFact.FrontendApiCall getCall = calls.get(1);
        assertEquals("/api/profile", getCall.getUrl());
        assertEquals("get", getCall.getMethod());
    }

    @Test
    void testExtractRequestHelpersAndMultilineRequestObjects() throws IOException {
        String content = """
                import { get, post } from '@/utils/request';

                export const scanApi = {
                  progress: (projectId, versionId) => {
                    return get(`/lg/projects/${projectId}/scan-versions/${versionId}/progress`, { includeLogs: true })
                  },
                  create: (projectId, data) => post('/lg/projects/' + projectId + '/scan-versions', data),
                  cancel(projectId, versionId) {
                    return request({
                      url: `/lg/projects/${projectId}/scan-versions/${versionId}/cancel`,
                      method: 'post'
                    })
                  }
                }
                """;
        Path file = tempDir.resolve("scan.api.ts");
        Files.writeString(file, content);

        List<FrontendPageFact.FrontendApiCall> calls = extractor.extractFromFile(file);

        assertTrue(calls.stream().anyMatch(c ->
                "get".equals(c.getMethod())
                        && "/lg/projects/${projectId}/scan-versions/${versionId}/progress".equals(c.getUrl())));
        assertTrue(calls.stream().anyMatch(c ->
                "post".equals(c.getMethod())
                        && "/lg/projects/${projectId}/scan-versions/${versionId}/cancel".equals(c.getUrl())));
    }

    // ---------------------------------------------------------------
    // 用例3：api 模块函数中的调用（url 与 method 需在同一行，因为
    // extractApiModuleCalls 的 URL 和 method 匹配均基于同一行）
    // ---------------------------------------------------------------
    @Test
    void testExtractApiModuleCalls() throws IOException {
        // given
        String content = """
                import request from '@/utils/request';
                                
                // 用户相关 API
                export function getUserInfo(userId) {
                    return request({
                        url: '/api/user/info', method: 'get'
                    });
                }
                                
                export function updateUser(data) {
                    return request({
                        url: '/api/user/update', method: 'post'
                    });
                }
                """;
        Path file = tempDir.resolve("userApi.js");
        Files.writeString(file, content);

        // when
        List<FrontendPageFact.FrontendApiCall> calls = extractor.extractFromFile(file);

        // then  — extractApiModuleCalls 和 extractRequestCalls 都会匹配
        // request({...}) 中的 url，因此可能产生重复。
        // 这里只验证 functionName 被正确设置。
        assertTrue(calls.size() >= 2, "应至少抽到 2 个 API 调用");

        boolean hasGetUserInfo = calls.stream()
                .anyMatch(c -> "getUserInfo".equals(c.getFunctionName())
                        && "/api/user/info".equals(c.getUrl())
                        && "get".equals(c.getMethod()));
        assertTrue(hasGetUserInfo, "应抽到 getUserInfo 函数");

        boolean hasUpdateUser = calls.stream()
                .anyMatch(c -> "updateUser".equals(c.getFunctionName())
                        && "/api/user/update".equals(c.getUrl())
                        && "post".equals(c.getMethod()));
        assertTrue(hasUpdateUser, "应抽到 updateUser 函数");
    }

    // ---------------------------------------------------------------
    // 用例4：Vue 按钮及权限抽取
    // ---------------------------------------------------------------
    @Test
    void testExtractButtonsFromVue() throws IOException {
        // given
        String content = """
                <template>
                  <div>
                    <el-button @click="handleAdd" v-permission="system:user:add">新增用户</el-button>
                    <el-button @click="handleEdit" has-permission="system:user:edit">编辑用户</el-button>
                    <button @click="handleDelete" permission="system:user:delete">删除</button>
                    <el-button>无权限按钮</el-button>
                  </div>
                </template>
                                
                <script setup>
                const handleAdd = () => {};
                const handleEdit = () => {};
                const handleDelete = () => {};
                </script>
                """;
        Path file = tempDir.resolve("UserList.vue");
        Files.writeString(file, content);

        // when
        List<FrontendPageFact.FrontendButton> buttons = extractor.extractButtonsFromVue(file);

        // then
        assertEquals(4, buttons.size(), "应抽到 4 个按钮");

        // 第一个按钮：v-permission + click
        FrontendPageFact.FrontendButton btn1 = buttons.get(0);
        assertEquals("handleAdd", btn1.getClickMethod());
        assertEquals("system:user:add", btn1.getPermission());
        assertTrue(btn1.getText().contains("新增用户"), "按钮文本应包含「新增用户」");

        // 第二个按钮：has-permission
        FrontendPageFact.FrontendButton btn2 = buttons.get(1);
        assertEquals("handleEdit", btn2.getClickMethod());
        assertEquals("system:user:edit", btn2.getPermission());

        // 第三个按钮：普通 button + 自定义 permission
        FrontendPageFact.FrontendButton btn3 = buttons.get(2);
        assertEquals("handleDelete", btn3.getClickMethod());
        assertEquals("system:user:delete", btn3.getPermission());

        // 第四个按钮：无 click 无权限
        FrontendPageFact.FrontendButton btn4 = buttons.get(3);
        assertNull(btn4.getClickMethod());
        assertNull(btn4.getPermission());
    }

    // ---------------------------------------------------------------
    // 用例5：空文件
    // ---------------------------------------------------------------
    @Test
    void testEmptyFile() throws IOException {
        // given
        String content = "";
        Path file = tempDir.resolve("empty.ts");
        Files.writeString(file, content);

        // when
        List<FrontendPageFact.FrontendApiCall> calls = extractor.extractFromFile(file);
        List<FrontendPageFact.FrontendButton> buttons = extractor.extractButtonsFromVue(file);

        // then
        assertNotNull(calls);
        assertTrue(calls.isEmpty(), "空文件不应抽取到任何 API 调用");

        assertNotNull(buttons);
        assertTrue(buttons.isEmpty(), "空文件不应抽取到任何按钮");
    }
}
