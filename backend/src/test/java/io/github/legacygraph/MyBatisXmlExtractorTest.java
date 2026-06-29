package io.github.legacygraph;

import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.extractors.MyBatisXmlExtractor.SqlStatement;
import io.github.legacygraph.model.MapperSqlFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试 {@link MyBatisXmlExtractor} 对 MyBatis XML Mapper 文件的解析
 * <p>
 * 使用 {@link TempDir @TempDir} 提供临时 XML 文件，覆盖以下场景：
 * <ol>
 *   <li>包含 select / insert / update 的多类型 Mapper 文件</li>
 *   <li>只有 select 语句的 Mapper 文件</li>
 *   <li>非 XML 文件（纯文本）</li>
 *   <li>格式错误的 XML 文件</li>
 * </ol>
 */
class MyBatisXmlExtractorTest {

    private final MyBatisXmlExtractor extractor = new MyBatisXmlExtractor();

    // ==================== 辅助方法 ====================

    /**
     * 在临时目录下创建文件并写入内容。
     *
     * @param tempDir {@link TempDir @TempDir} 注入的临时目录
     * @param fileName 文件名（如 "UserMapper.xml"）
     * @param content  文件内容
     * @return 创建好的 {@link File} 对象
     */
    private static File createXmlFile(Path tempDir, String fileName, String content) throws IOException {
        Path filePath = tempDir.resolve(fileName);
        Files.writeString(filePath, content);
        return filePath.toFile();
    }

    // ==================== 用例 1：含 select / insert / update 的多类型 Mapper ====================

    @Test
    @DisplayName("解析包含 select / insert / update 的完整 Mapper XML")
    void shouldExtractMultipleStatementTypes(@TempDir Path tempDir) throws IOException {
        // language=xml
        String xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.mapper.UserMapper">
                    <select id="findById" resultType="User">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                    <insert id="insertUser">
                        INSERT INTO users (name, email) VALUES (#{name}, #{email})
                    </insert>
                    <update id="updateUser">
                        UPDATE users SET name = #{name} WHERE id = #{id}
                    </update>
                </mapper>
                """;

        File xmlFile = createXmlFile(tempDir, "MultiTypeMapper.xml", xml);

        MapperSqlFact fact = extractor.extractFromFile(xmlFile);

        // namespace
        assertThat(fact.getNamespace()).isEqualTo("com.example.mapper.UserMapper");

        // 一共 3 条语句
        assertThat(fact.getStatements()).hasSize(3);

        // select
        SqlStatement selectStmt = fact.getStatements().get(0);
        assertThat(selectStmt.getId()).isEqualTo("findById");
        assertThat(selectStmt.getType()).isEqualTo("select");
        assertThat(selectStmt.getSql()).contains("SELECT * FROM users");

        // insert
        SqlStatement insertStmt = fact.getStatements().get(1);
        assertThat(insertStmt.getId()).isEqualTo("insertUser");
        assertThat(insertStmt.getType()).isEqualTo("insert");
        assertThat(insertStmt.getSql()).contains("INSERT INTO users");

        // update
        SqlStatement updateStmt = fact.getStatements().get(2);
        assertThat(updateStmt.getId()).isEqualTo("updateUser");
        assertThat(updateStmt.getType()).isEqualTo("update");
        assertThat(updateStmt.getSql()).contains("UPDATE users");
    }

    // ==================== 用例 2：只有 select 语句的 Mapper ====================

    @Test
    @DisplayName("解析只有 select 语句的 Mapper XML")
    void shouldExtractSelectOnlyMapper(@TempDir Path tempDir) throws IOException {
        // language=xml
        String xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.mapper.BookMapper">
                    <select id="findAll" resultType="Book">
                        SELECT * FROM books
                    </select>
                    <select id="findByAuthor" resultType="Book">
                        SELECT * FROM books WHERE author = #{author}
                    </select>
                </mapper>
                """;

        File xmlFile = createXmlFile(tempDir, "SelectOnlyMapper.xml", xml);

        MapperSqlFact fact = extractor.extractFromFile(xmlFile);

        // namespace
        assertThat(fact.getNamespace()).isEqualTo("com.example.mapper.BookMapper");

        // 只有 2 条 select
        assertThat(fact.getStatements()).hasSize(2);
        assertThat(fact.getStatements())
                .allSatisfy(stmt -> assertThat(stmt.getType()).isEqualTo("select"));

        // 验证具体内容
        assertThat(fact.getStatements().get(0).getId()).isEqualTo("findAll");
        assertThat(fact.getStatements().get(1).getId()).isEqualTo("findByAuthor");
        assertThat(fact.getStatements().get(1).getSql()).contains("author = #{author}");
    }

    // ==================== 用例 3：非 XML 文件 ====================

    @Test
    @DisplayName("解析非 XML 文件（纯文本）应返回空的 MapperSqlFact（namespace 为空，statements 为空列表）")
    void shouldHandleNonXmlFile(@TempDir Path tempDir) throws IOException {
        // 不是 XML，只是一个文本文件
        String content = "This is not an XML file.\nJust some plain text.\n";

        File textFile = createXmlFile(tempDir, "plain.txt", content);

        MapperSqlFact fact = extractor.extractFromFile(textFile);

        // sourcePath 应该被设置
        assertThat(fact.getSourcePath()).isEqualTo(textFile.getAbsolutePath());

        // 解析失败后 namespace 为 null / empty，statements 为空
        assertThat(fact.getNamespace()).isNullOrEmpty();
        assertThat(fact.getStatements()).isNullOrEmpty();
    }

    // ==================== 用例 4：格式错误的 XML 文件 ====================

    @Test
    @DisplayName("解析格式错误的 XML 文件应返回空的 MapperSqlFact（内部 catch 异常，不抛出）")
    void shouldHandleMalformedXml(@TempDir Path tempDir) throws IOException {
        // language=xml — 故意制造格式错误：缺少闭合标签
        String malformedXml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <mapper namespace="com.example.mapper.BadMapper">
                    <select id="broken">
                        SELECT * FROM users
                    <!-- 缺少 </select> 和 </mapper> 的闭合
                """;

        File badXmlFile = createXmlFile(tempDir, "MalformedMapper.xml", malformedXml);

        // extractor 内部 catch 异常，不对外抛出
        MapperSqlFact fact = extractor.extractFromFile(badXmlFile);

        // namespace 为空；statements 应该为空列表
        assertThat(fact.getNamespace()).isNullOrEmpty();
        assertThat(fact.getStatements()).isNullOrEmpty();
    }

    // ==================== 用例 5：include 片段展开 ====================

    @Test
    @DisplayName("解析含 <sql> + <include refid> 的 Mapper，expandedSql 应展开片段")
    void shouldExpandIncludeFragments(@TempDir Path tempDir) throws IOException {
        // language=xml
        String xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.mapper.OrderMapper">
                    <sql id="baseColumns">id, order_no, amount, status</sql>
                    <select id="findById" resultType="Order">
                        SELECT <include refid="baseColumns"/> FROM orders WHERE id = #{id}
                    </select>
                </mapper>
                """;

        File xmlFile = createXmlFile(tempDir, "OrderMapper.xml", xml);

        MapperSqlFact fact = extractor.extractFromFile(xmlFile);

        assertThat(fact.getStatements()).hasSize(1);
        SqlStatement stmt = fact.getStatements().get(0);
        // expandedSql 应包含被 include 引入的列片段
        assertThat(stmt.getExpandedSql()).contains("id, order_no, amount, status");
        assertThat(stmt.getExpandedSql()).contains("FROM orders");
        // expandedSql 中不应再残留 include 标签
        assertThat(stmt.getExpandedSql()).doesNotContain("include");
    }
}
