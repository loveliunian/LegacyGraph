package io.github.legacygraph.task.step;

import io.github.legacygraph.dto.AiScanConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StepExecutionContext} 增量字段的单元测试。
 */
class StepExecutionContextTest {

    @Test
    void defaultBuild_hasEmptyCollectionsAndFalseIncremental() {
        StepExecutionContext ctx = StepExecutionContext.builder()
                .projectId("p1")
                .versionId("v1")
                .build();

        assertNotNull(ctx.getChangedFilePaths(), "changedFilePaths 默认不应为 null");
        assertTrue(ctx.getChangedFilePaths().isEmpty(), "changedFilePaths 默认应为空集合");
        assertNotNull(ctx.getAffectedNodeIds(), "affectedNodeIds 默认不应为 null");
        assertTrue(ctx.getAffectedNodeIds().isEmpty(), "affectedNodeIds 默认应为空集合");
        assertFalse(ctx.isIncremental(), "incremental 默认应为 false");
    }

    @Test
    void customBuild_setsIncrementalFieldsCorrectly() {
        Set<String> changedFiles = Set.of("src/main/Foo.java", "src/main/Bar.java");
        Set<String> affectedNodes = Set.of("node-1", "node-2", "node-3");
        AiScanConfig config = new AiScanConfig();

        StepExecutionContext ctx = StepExecutionContext.builder()
                .projectId("p1")
                .versionId("v1")
                .config(config)
                .changedFilePaths(changedFiles)
                .affectedNodeIds(affectedNodes)
                .incremental(true)
                .build();

        assertEquals(changedFiles, ctx.getChangedFilePaths());
        assertEquals(affectedNodes, ctx.getAffectedNodeIds());
        assertTrue(ctx.isIncremental(), "incremental 应为 true");
        assertEquals(config, ctx.getConfig());
    }
}
