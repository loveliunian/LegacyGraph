package io.github.legacygraph;

import io.github.legacygraph.extractors.VueRouteExtractor;
import io.github.legacygraph.model.FrontendPageFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VueRouteExtractor 单元测试
 * 覆盖：简易路由、嵌套路由、非标准降级正则、空文件
 */
class VueRouteExtractorTest {

    @TempDir
    Path tempDir;

    private Path createTsFile(String content) throws IOException {
        Path file = tempDir.resolve("routes.ts");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void testSimpleRoutes() throws IOException {
        // given: 一个简易路由文件，包含两个并列路由定义
        String content = """
                import { RouteConfig } from 'vue-router';
                                
                const routes: RouteConfig[] = [
                  {
                    path: '/dashboard',
                    name: 'Dashboard',
                    component: DashboardComponent,
                    meta: { title: '仪表盘', icon: 'dashboard' }
                  },
                  {
                    path: '/user',
                    name: 'User',
                    component: UserComponent,
                    meta: { title: '用户管理', permission: 'user:list' }
                  }
                ];
                                
                export default routes;
                """;
        Path file = createTsFile(content);

        VueRouteExtractor extractor = new VueRouteExtractor();

        // when
        List<FrontendPageFact> facts = extractor.extractFromFile(file);

        // then
        assertNotNull(facts);
        assertEquals(2, facts.size(), "应有 2 个路由被抽取");

        // 第一个路由: /dashboard
        FrontendPageFact dashboard = facts.stream()
                .filter(f -> "/dashboard".equals(f.getRoutePath()))
                .findFirst().orElse(null);
        assertNotNull(dashboard);
        assertEquals("Dashboard", dashboard.getRouteName());
        assertEquals("DashboardComponent", dashboard.getComponentPath());
        assertEquals("仪表盘", dashboard.getTitle());
        assertEquals("dashboard", dashboard.getIcon());

        // 第二个路由: /user
        FrontendPageFact user = facts.stream()
                .filter(f -> "/user".equals(f.getRoutePath()))
                .findFirst().orElse(null);
        assertNotNull(user);
        assertEquals("User", user.getRouteName());
        assertEquals("UserComponent", user.getComponentPath());
        assertEquals("用户管理", user.getTitle());
        assertEquals("user:list", user.getPermission());
    }

    @Test
    void testNestedRoutes() throws IOException {
        // given: 包含多级嵌套路由的文件
        String content = """
                import { RouteConfig } from 'vue-router';
                                
                const routes: RouteConfig[] = [
                  {
                    path: '/system',
                    name: 'System',
                    component: LayoutComponent,
                    meta: { title: '系统管理' },
                    children: [
                      {
                        path: 'user',
                        name: 'SysUser',
                        component: SysUserComponent,
                        meta: { title: '用户管理', permission: 'system:user' }
                      },
                      {
                        path: 'role',
                        name: 'SysRole',
                        component: SysRoleComponent,
                        meta: { title: '角色管理', permission: 'system:role' }
                      }
                    ]
                  }
                ];
                                
                export default routes;
                """;
        Path file = createTsFile(content);

        VueRouteExtractor extractor = new VueRouteExtractor();

        // when
        List<FrontendPageFact> facts = extractor.extractFromFile(file);

        // then — convertToPageFacts 展平了子路由，所以平铺出来有 3 条记录
        assertNotNull(facts);
        assertEquals(3, facts.size(), "应有 3 个 PageFact：父路由 + 2 个子路由");

        // 父路由
        FrontendPageFact parent = facts.stream()
                .filter(f -> "/system".equals(f.getRoutePath()))
                .findFirst().orElse(null);
        assertNotNull(parent);
        assertEquals("System", parent.getRouteName());
        assertEquals("LayoutComponent", parent.getComponentPath());
        assertEquals("系统管理", parent.getTitle());
        assertNotNull(parent.getChildren());
        assertEquals(2, parent.getChildren().size());

        // 子路由: /system/user
        FrontendPageFact childUser = facts.stream()
                .filter(f -> "/system/user".equals(f.getRoutePath()))
                .findFirst().orElse(null);
        assertNotNull(childUser);
        assertEquals("SysUser", childUser.getRouteName());
        assertEquals("系统管理", parent.getTitle()); // 父路由没有向下透传 title，子路由自己有

        // 子路由: /system/role
        FrontendPageFact childRole = facts.stream()
                .filter(f -> "/system/role".equals(f.getRoutePath()))
                .findFirst().orElse(null);
        assertNotNull(childRole);
        assertEquals("SysRole", childRole.getRouteName());
    }

    @Test
    void testNonStandardRoute_fallbackToRegex() throws IOException {
        // given: 非标准格式（使用函数/变量引用等），JSON 解析会失败，触发正则降级
        String content = """
                import { RouteConfig } from 'vue-router';
                                
                // 动态 import + 变量引用，不是标准字面量对象
                const UserList = () => import('./views/UserList.vue');
                                
                function buildRoute(path, name, comp) {
                  return { path: path, name: name, component: comp };
                }
                                
                const customRoute = buildRoute('/custom', 'Custom', UserList);
                                
                const routes = [
                  { path: '/login', name: 'Login', component: LoginView },
                  customRoute,
                  { path: '/about', name: 'About', component: AboutView, meta: { title: '关于' } }
                ];
                                
                export default routes;
                """;
        Path file = createTsFile(content);

        VueRouteExtractor extractor = new VueRouteExtractor();

        // when — JSON 解析失败 fallback 到正则模式
        List<FrontendPageFact> facts = extractor.extractFromFile(file);

        // then — 正则模式至少能抽取出那些 path 是字面量的路由
        assertNotNull(facts);
        assertFalse(facts.isEmpty(), "降级正则模式至少应抽取到部分路由");

        // /login 和 /about 是字面量 path，应被正则捕获
        FrontendPageFact login = facts.stream()
                .filter(f -> "/login".equals(f.getRoutePath()))
                .findFirst().orElse(null);
        assertNotNull(login, "正则应捕获 /login");
        assertEquals("Login", login.getRouteName());
        assertEquals("LoginView", login.getComponentPath());

        FrontendPageFact about = facts.stream()
                .filter(f -> "/about".equals(f.getRoutePath()))
                .findFirst().orElse(null);
        assertNotNull(about, "正则应捕获 /about");
        assertEquals("About", about.getRouteName());
        assertEquals("AboutView", about.getComponentPath());
        assertEquals("关于", about.getTitle());

        // /custom 的 path 是变量引用，不会被字面量正则捕获
        boolean hasCustom = facts.stream().anyMatch(f -> "/custom".equals(f.getRoutePath()));
        assertFalse(hasCustom, "变量引用的 path 不应被正则抽取");
    }

    @Test
    void testEmptyFile() throws IOException {
        // given: 空文件
        Path file = createTsFile("");

        VueRouteExtractor extractor = new VueRouteExtractor();

        // when
        List<FrontendPageFact> facts = extractor.extractFromFile(file);

        // then
        assertNotNull(facts);
        assertTrue(facts.isEmpty(), "空文件应返回空列表");
    }
}
