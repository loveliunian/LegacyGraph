package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.legacygraph.entity.FileSnapshot;
import io.github.legacygraph.repository.FileSnapshotRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileChangeDetector 单元测试。
 * <p>
 * 验证 SHA-256 哈希计算与基于内容哈希的变更检测逻辑：
 * 首次扫描全量变更、内容未变更返回空、内容修改/新增文件被检出、快照 upsert 等。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class FileChangeDetectorTest {

    @Mock
    private FileSnapshotRepository repository;

    private FileChangeDetector detector;

    @BeforeAll
    static void initLambdaCache() {
        // 纯 Mockito 测试中需手动注册 FileSnapshot 的 TableInfo，否则 LambdaQueryWrapper 解析方法引用会失败
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                FileSnapshot.class);
    }

    @BeforeEach
    void setUp() {
        detector = new FileChangeDetector(repository);
    }

    @Test
    void computeHash_相同内容哈希一致且为64位十六进制() {
        String content = "public class Foo {}";
        String h1 = detector.computeHash(content);
        String h2 = detector.computeHash(content);

        assertEquals(h1, h2, "相同内容的哈希应一致");
        assertEquals(64, h1.length(), "SHA-256 哈希应为 64 位十六进制");
        assertTrue(h1.matches("[0-9a-f]{64}"), "哈希应为小写十六进制");
        // 空内容也能计算
        assertNotNull(detector.computeHash(""));
    }

    @Test
    void computeHash_不同内容哈希不同() {
        assertNotEquals(detector.computeHash("a"), detector.computeHash("b"));
    }

    @Test
    void detectChangedFiles_首次扫描全部视为变更() {
        when(repository.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        Map<String, String> pathToContent = new HashMap<>();
        pathToContent.put("src/Foo.java", "class Foo {}");
        pathToContent.put("src/Bar.java", "class Bar {}");

        List<String> changed = detector.detectChangedFiles("p1", pathToContent);

        assertEquals(2, changed.size(), "无历史快照时所有文件都应视为变更");
        assertTrue(changed.containsAll(List.of("src/Foo.java", "src/Bar.java")));
    }

    @Test
    void detectChangedFiles_内容未变更返回空() {
        String fooContent = "class Foo {}";
        List<FileSnapshot> stored = new ArrayList<>();
        stored.add(buildSnapshot("p1", "src/Foo.java", detector.computeHash(fooContent)));
        when(repository.selectList(any(LambdaQueryWrapper.class))).thenReturn(stored);

        Map<String, String> pathToContent = new HashMap<>();
        pathToContent.put("src/Foo.java", fooContent);

        List<String> changed = detector.detectChangedFiles("p1", pathToContent);
        assertTrue(changed.isEmpty(), "内容未变更时应返回空列表");
    }

    @Test
    void detectChangedFiles_仅返回变更与新增文件() {
        String fooOld = "class Foo {}";
        String fooNew = "class Foo { void m() {} }";
        String barContent = "class Bar {}";

        List<FileSnapshot> stored = new ArrayList<>();
        stored.add(buildSnapshot("p1", "src/Foo.java", detector.computeHash(fooOld)));
        stored.add(buildSnapshot("p1", "src/Bar.java", detector.computeHash(barContent)));
        when(repository.selectList(any(LambdaQueryWrapper.class))).thenReturn(stored);

        Map<String, String> pathToContent = new HashMap<>();
        pathToContent.put("src/Foo.java", fooNew);   // 内容修改 → 变更
        pathToContent.put("src/Bar.java", barContent); // 未变更
        pathToContent.put("src/Baz.java", "class Baz {}"); // 新文件 → 变更

        List<String> changed = detector.detectChangedFiles("p1", pathToContent);

        assertEquals(2, changed.size(), "变更文件 + 新文件应被检出");
        assertTrue(changed.contains("src/Foo.java"));
        assertTrue(changed.contains("src/Baz.java"));
        assertFalse(changed.contains("src/Bar.java"), "未变更文件不应出现在变更列表");
    }

    @Test
    void detectChangedFiles_仅比较不写入快照() {
        when(repository.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        Map<String, String> pathToContent = new HashMap<>();
        pathToContent.put("src/Foo.java", "class Foo {}");

        detector.detectChangedFiles("p1", pathToContent);

        verify(repository, never()).insert((FileSnapshot) any());
        verify(repository, never()).updateById((FileSnapshot) any());
    }

    @Test
    void recordFileSnapshot_无历史记录则新增() {
        when(repository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        detector.recordFileSnapshot("p1", "src/Foo.java", "class Foo {}");

        ArgumentCaptor<FileSnapshot> captor = ArgumentCaptor.forClass(FileSnapshot.class);
        verify(repository).insert(captor.capture());
        FileSnapshot saved = captor.getValue();
        assertEquals("p1", saved.getProjectId());
        assertEquals("src/Foo.java", saved.getFilePath());
        assertEquals(64, saved.getFileHash().length());
        assertNotNull(saved.getScannedAt());
        verify(repository, never()).updateById((FileSnapshot) any());
    }

    @Test
    void recordFileSnapshot_有历史记录则更新() {
        FileSnapshot existing = buildSnapshot("p1", "src/Foo.java", "oldhash");
        when(repository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        detector.recordFileSnapshot("p1", "src/Foo.java", "class Foo {}");

        verify(repository).updateById(existing);
        verify(repository, never()).insert((FileSnapshot) any());
        assertEquals(64, existing.getFileHash().length());
        assertNotEquals("oldhash", existing.getFileHash());
    }

    @Test
    void recordSnapshots_批量记录所有文件() {
        Map<String, String> pathToContent = new HashMap<>();
        pathToContent.put("src/A.java", "class A {}");
        pathToContent.put("src/B.java", "class B {}");
        when(repository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        detector.recordSnapshots("p1", pathToContent);

        verify(repository, times(2)).insert((FileSnapshot) any());
    }

    @Test
    void getUnchangedFiles_返回已存快照路径() {
        List<FileSnapshot> stored = new ArrayList<>();
        stored.add(buildSnapshot("p1", "src/A.java", "h1"));
        stored.add(buildSnapshot("p1", "src/B.java", "h2"));
        when(repository.selectList(any(LambdaQueryWrapper.class))).thenReturn(stored);

        Set<String> unchanged = detector.getUnchangedFiles("p1");
        assertEquals(2, unchanged.size());
        assertTrue(unchanged.contains("src/A.java"));
        assertTrue(unchanged.contains("src/B.java"));
    }

    @Test
    void getUnchangedFiles_首次扫描返回空集() {
        when(repository.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        assertTrue(detector.getUnchangedFiles("p1").isEmpty());
    }

    @Test
    void clearSnapshots_按项目删除全部快照() {
        when(repository.delete(any(LambdaQueryWrapper.class))).thenReturn(3);

        detector.clearSnapshots("p1");

        verify(repository).delete(any(LambdaQueryWrapper.class));
    }

    private FileSnapshot buildSnapshot(String projectId, String filePath, String hash) {
        return FileSnapshot.builder()
                .projectId(projectId)
                .filePath(filePath)
                .fileHash(hash)
                .fileSize(0L)
                .scannedAt(LocalDateTime.now())
                .build();
    }
}
