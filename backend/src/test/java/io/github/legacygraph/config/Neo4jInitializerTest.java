package io.github.legacygraph.config;

import io.github.legacygraph.dao.Neo4jGraphDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Neo4jInitializer 单元测试。
 * 验证应用启动时约束与索引的创建逻辑。
 */
@ExtendWith(MockitoExtension.class)
class Neo4jInitializerTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @InjectMocks
    private Neo4jInitializer initializer;

    /**
     * 测试 init 正常创建约束和索引。
     */
    @Test
    void init_createsConstraintsAndIndexes() {
        initializer.init();

        verify(neo4jGraphDao).createConstraints();
        verify(neo4jGraphDao).createIndexes();
    }

    /**
     * 测试 init 异常时被捕获不传播。
     */
    @Test
    void init_whenCreateConstraintsFails_doesNotThrow() {
        doThrow(new RuntimeException("Neo4j 不可用"))
                .when(neo4jGraphDao).createConstraints();

        assertDoesNotThrow(() -> initializer.init());

        verify(neo4jGraphDao).createConstraints();
        verify(neo4jGraphDao, never()).createIndexes();
    }

    /**
     * 测试 init 索引创建失败时被捕获。
     */
    @Test
    void init_whenCreateIndexesFails_doesNotThrow() {
        doThrow(new RuntimeException("索引创建失败"))
                .when(neo4jGraphDao).createIndexes();

        assertDoesNotThrow(() -> initializer.init());

        verify(neo4jGraphDao).createConstraints();
        verify(neo4jGraphDao).createIndexes();
    }

    /**
     * 验证类上有 @Component 注解。
     */
    @Test
    void class_hasComponentAnnotation() {
        assertNotNull(Neo4jInitializer.class.getAnnotation(
                org.springframework.stereotype.Component.class));
    }
}
