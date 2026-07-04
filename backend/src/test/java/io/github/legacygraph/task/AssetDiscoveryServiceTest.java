package io.github.legacygraph.task;

import io.github.legacygraph.dto.scan.ResolvedDocScope;
import io.github.legacygraph.dto.scan.ResolvedRepoScope;
import io.github.legacygraph.dto.scan.ResolvedScanPlan;
import io.github.legacygraph.extractors.adapter.SourceAsset;
import io.github.legacygraph.repository.SourceAssetSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AssetDiscoveryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void discoverAssets_respectsDocParseScanType() throws Exception {
        Files.writeString(tempDir.resolve("UserService.java"), "class UserService {}");
        Files.writeString(tempDir.resolve("guide.md"), "# Guide");
        AssetDiscoveryService service = new AssetDiscoveryService(mock(SourceAssetSnapshotRepository.class));

        ResolvedScanPlan plan = planWithRepo(Set.of("DOC_PARSE"));

        List<String> paths = service.discoverAssets(plan).stream()
                .map(SourceAsset::getRelativePath)
                .toList();

        assertEquals(List.of("guide.md"), paths);
    }

    @Test
    void discoverAssets_includesExplicitDocumentsWithoutRepo() throws Exception {
        Path selectedDoc = tempDir.resolve("selected.md");
        Files.writeString(selectedDoc, "# Selected");
        AssetDiscoveryService service = new AssetDiscoveryService(mock(SourceAssetSnapshotRepository.class));

        ResolvedScanPlan plan = ResolvedScanPlan.builder()
                .projectId("project-1")
                .versionId("version-1")
                .repos(List.of())
                .documents(List.of(ResolvedDocScope.builder()
                        .docId("doc-1")
                        .docName("selected.md")
                        .filePath(selectedDoc.toString())
                        .build()))
                .scanTypes(Set.of("DOC_PARSE"))
                .maxFiles(100)
                .maxDocs(10)
                .maxDbTables(100)
                .rawScope(Map.of())
                .build();

        List<String> paths = service.discoverAssets(plan).stream()
                .map(SourceAsset::getRelativePath)
                .toList();

        assertEquals(List.of("selected.md"), paths);
    }

    private ResolvedScanPlan planWithRepo(Set<String> scanTypes) {
        return ResolvedScanPlan.builder()
                .projectId("project-1")
                .versionId("version-1")
                .repos(List.of(ResolvedRepoScope.builder()
                        .repoId("repo-1")
                        .baseDir(tempDir.toString())
                        .backendDir(tempDir.toString())
                        .frontendDir(tempDir.toString())
                        .includePatterns(List.of())
                        .excludePatterns(List.of())
                        .build()))
                .documents(List.of())
                .scanTypes(scanTypes)
                .maxFiles(100)
                .maxDocs(10)
                .maxDbTables(100)
                .rawScope(Map.of())
                .build();
    }
}
