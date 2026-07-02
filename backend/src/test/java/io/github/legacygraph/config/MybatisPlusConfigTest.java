package io.github.legacygraph.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MybatisPlusConfig 集成测试。
 * <p>
 * 验证 MyBatis-Plus 分页插件是否正确注入（类型、数据库方言）。
 * </p>
 */
@SpringBootTest
class MybatisPlusConfigTest {

    @Autowired
    private MybatisPlusInterceptor mybatisPlusInterceptor;

    /**
     * 验证 MybatisPlusInterceptor Bean 注入成功。
     */
    @Test
    void test_interceptor_bean_injected() {
        assertThat(mybatisPlusInterceptor).isNotNull();
    }

    /**
     * 验证分页插件已注册，且数据库方言为 PostgreSQL。
     */
    @Test
    void test_pagination_plugin_registered() {
        // 检查内部拦截器列表是否包含 PaginationInnerInterceptor
        boolean hasPaginationInterceptor = mybatisPlusInterceptor.getInterceptors().stream()
                .anyMatch(PaginationInnerInterceptor.class::isInstance);

        assertThat(hasPaginationInterceptor)
                .as("MybatisPlusInterceptor 应包含 PaginationInnerInterceptor")
                .isTrue();
    }

    /**
     * 验证 PaginationInnerInterceptor 的数据库方言为 PostgreSQL。
     */
    @Test
    void test_pagination_db_type_postgres() {
        PaginationInnerInterceptor paginationInterceptor = mybatisPlusInterceptor.getInterceptors().stream()
                .filter(PaginationInnerInterceptor.class::isInstance)
                .map(PaginationInnerInterceptor.class::cast)
                .findFirst()
                .orElse(null);

        assertThat(paginationInterceptor).isNotNull();

        // DbType 应为 POSTGRE_SQL
        assertThat(paginationInterceptor.getDbType())
                .as("数据库方言应为 POSTGRE_SQL")
                .isEqualTo(DbType.POSTGRE_SQL);
    }
}
