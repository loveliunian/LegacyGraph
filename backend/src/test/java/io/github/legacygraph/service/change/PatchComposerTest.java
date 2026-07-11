package io.github.legacygraph.service.change;

import io.github.legacygraph.dto.change.ChangeTaskProposal;
import io.github.legacygraph.entity.SolutionStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PatchComposer} 单测（G-12 收尾）。
 * <p>验证：
 * <ul>
 *   <li>CREATE 步骤生成新文件 hunk 头（/dev/null → b/path）</li>
 *   <li>DELETE 步骤生成删除 hunk（a/path → /dev/null），含全部 `-` 行</li>
 *   <li>MODIFY 步骤读取项目原文件，与新内容生成 LCS unified diff；含 `@@` hunk 头与 `+/-` 行</li>
 *   <li>MODIFY 时原文件不存在则 actionType 退回为 CREATE，diff 头退化为 CREATE</li>
 * </ul>
 * 覆盖目标：行 ≥ 80，符合方案文档 §7 验收标准。
 */
class PatchComposerTest {

    private final PatchComposer composer = new PatchComposer();

    @Test
    void composeCreateDiff_shouldGenerateNewFileHunk() {
        String diff = composer.composeDiff(
                "src/main/java/OrderService.java",
                null,
                "public class OrderService {}\n",
                "CREATE");
        assertTrue(diff.startsWith("--- /dev/null\n"), "CREATE 必须以 /dev/null 开头");
        assertTrue(diff.contains("+++ b/src/main/java/OrderService.java"),
                "CREATE 必须含 b/path 头");
        assertTrue(diff.contains("@@ -0,0 +1,1 @@"),
                "CREATE hunk 头应指向空端跳到第 1 行");
        assertTrue(diff.contains("+public class OrderService {}"),
                "CREATE hunk 应包含新增行");
    }

    @Test
    void composeDeleteDiff_shouldGenerateRemovalHunk() {
        String diff = composer.composeDiff(
                "src/main/java/OldService.java",
                "public class OldService {}\n",
                null,
                "DELETE");
        assertTrue(diff.startsWith("--- a/src/main/java/OldService.java"),
                "DELETE 必须以 a/path 开头");
        assertTrue(diff.contains("+++ /dev/null"), "DELETE 必须以 /dev/null 结尾");
        assertTrue(diff.contains("-public class OldService {}"),
                "DELETE 必须含减号行");
    }

    @Test
    void composeModifyDiff_shouldFallbackToCreateWhenOriginalMissing() {
        // 原内容为空：即便声明是 MODIFY，也应退化为 CREATE
        String diff = composer.composeDiff(
                "src/main/java/NewService.java",
                "",
                "public class NewService {}\n",
                "MODIFY");
        assertTrue(diff.startsWith("--- /dev/null\n"),
                "原文件为空时 MODIFY 应退化为 CREATE hunk");
        assertTrue(diff.contains("+++ b/src/main/java/NewService.java"));
    }

    @Test
    void composeModifyDiff_shouldGenerateLcsBasedHunk(@TempDir Path baseDir) throws IOException {
        // 原文件：5 行
        String original = String.join("\n",
                "public class OrderService {",
                "    public void a() {}",
                "    public void b() {}",
                "    public void c() {}",
                "}");
        Path originalFile = baseDir.resolve("OrderService.java");
        Files.writeString(originalFile, original);

        SolutionStep step = new SolutionStep();
        step.setFilePath("OrderService.java");
        step.setActionType("MODIFY");
        step.setSymbolName("OrderService#b");
        step.setCodeSnippet(String.join("\n",
                "public class OrderService {",
                "    public void a() {}",
                "    public void bModified() {}",
                "    public void c() {}",
                "}"));
        step.setEvidenceIds("[\"ev-001\"]");

        ChangeTaskProposal.ProposalFile proposal = composer.composePatch(step, baseDir.toString());

        assertNotNull(proposal, "MODIFY 时产出不能为空");
        assertEquals("MODIFY", proposal.getOp(), "原文件存在时不应退化为 CREATE");
        assertEquals("OrderService.java", proposal.getFilePath());
        assertTrue(proposal.getDiff().startsWith("--- a/OrderService.java"));
        assertTrue(proposal.getDiff().contains("+++ b/OrderService.java"));
        assertTrue(proposal.getDiff().contains("@@"),
                "MODIFY 必须含 hunk 头");
        assertTrue(proposal.getDiff().contains("-    public void b() {}"),
                "MODIFY 必须含被删除的原行");
        assertTrue(proposal.getDiff().contains("+    public void bModified() {}"),
                "MODIFY 必须含新增行");
        assertEquals(Collections.singletonList("ev-001"), proposal.getEvidenceIds());
    }

    @Test
    void composePatch_shouldReturnNullWhenStepMissingCodeAndFile() {
        SolutionStep empty = new SolutionStep();
        // filePath 与 codeSnippet 都为空
        assertNull(composer.composePatch(empty, "/tmp"));

        SolutionStep noFilePath = new SolutionStep();
        noFilePath.setCodeSnippet("class A {}");
        assertNull(composer.composePatch(noFilePath, "/tmp"));
    }

    @Test
    void composeModifyDiff_shouldProduceEmptyDiffWhenContentsMatch(@TempDir Path baseDir) throws IOException {
        String same = "public class Same {}\n";
        Path p = baseDir.resolve("Same.java");
        Files.writeString(p, same);

        SolutionStep step = new SolutionStep();
        step.setFilePath("Same.java");
        step.setActionType("MODIFY");
        step.setCodeSnippet(same);

        ChangeTaskProposal.ProposalFile proposal = composer.composePatch(step, baseDir.toString());
        assertNotNull(proposal);
        assertEquals("MODIFY", proposal.getOp());
        // 内容相同时无变更行，hunk 段为空字符串
        assertFalse(proposal.getDiff().contains("@@"), "内容相同不应产生 hunk");
        assertTrue(proposal.getDiff().contains("--- a/Same.java"));
    }

    @Test
    void composeDeletePatch_shouldGenerateDeleteDiffWhenCodeSnippetIsEmpty(@TempDir Path baseDir) throws IOException {
        Path p = baseDir.resolve("Legacy.java");
        Files.writeString(p, "public class Legacy {}\n");

        SolutionStep step = new SolutionStep();
        step.setFilePath("Legacy.java");
        step.setActionType("DELETE");
        step.setCodeSnippet(""); // DELETE 允许 codeSnippet 为空

        ChangeTaskProposal.ProposalFile proposal = composer.composePatch(step, baseDir.toString());
        assertNotNull(proposal);
        assertEquals("DELETE", proposal.getOp());
        assertTrue(proposal.getDiff().startsWith("--- a/Legacy.java"));
        assertTrue(proposal.getDiff().contains("-public class Legacy {}"));
    }
}
