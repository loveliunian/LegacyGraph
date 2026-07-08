package io.github.legacygraph.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionTemplate;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis-Plus 配置
 * 添加 UUID 类型处理器以支持 PostgreSQL uuid 类型与 Java String 互转
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 字符串 UUID 类型处理器
     * 支持 Java String 对应 PostgreSQL uuid 类型
     * 解决：ERROR: operator does not exist: uuid = character varying
     */
    @org.apache.ibatis.type.MappedTypes(String.class)
    @org.apache.ibatis.type.MappedJdbcTypes(org.apache.ibatis.type.JdbcType.OTHER)
    public static class StringUuidTypeHandler extends BaseTypeHandler<String> {
        @Override
        public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
            PGobject pg = new PGobject();
            pg.setType("uuid");
            pg.setValue(parameter);
            ps.setObject(i, pg);
        }

        @Override
        public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
            return rs.getString(columnName);
        }

        @Override
        public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
            return rs.getString(columnIndex);
        }

        @Override
        public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
            return cs.getString(columnIndex);
        }
    }

    /**
     * 配置 SqlSessionFactory
     * 在同时使用 JPA 和 MyBatis-Plus 的情况下需要显式配置
     * 必须使用 MybatisSqlSessionFactoryBean 才能继承 MyBatis-Plus 的功能
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean sqlSessionFactoryBean = new MybatisSqlSessionFactoryBean();
        sqlSessionFactoryBean.setDataSource(dataSource);
        // 设置 mapper XML 文件位置
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        sqlSessionFactoryBean.setMapperLocations(resolver.getResources("classpath*:/mapper/**/*.xml"));
        // 设置插件
        sqlSessionFactoryBean.setPlugins(mybatisPlusInterceptor());
        // 注册 PostgreSQL UUID 类型处理器
        com.baomidou.mybatisplus.core.MybatisConfiguration ibatisConfiguration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        // 显式设置 SQL 日志实现，否则自定义 SqlSessionFactory 会覆盖 yml 中的 log-impl 配置
        ibatisConfiguration.setLogImpl(org.apache.ibatis.logging.slf4j.Slf4jImpl.class);
        // 不要全局注册给 String.class，这会导致所有 String 参数都被当作 UUID 处理
        // 正确的方式是在实体类的 UUID 字段上显式指定 @TypeHandler
        sqlSessionFactoryBean.setConfiguration(ibatisConfiguration);
        return sqlSessionFactoryBean.getObject();
    }

    /**
     * 配置 SqlSessionTemplate
     * 必须使用 sqlSessionFactory 作为参数注入
     */
    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
