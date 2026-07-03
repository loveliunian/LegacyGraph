package io.github.legacygraph.service.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.EvidenceConflict;
import io.github.legacygraph.repository.EvidenceConflictRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EvidenceConflictService {

    private final EvidenceConflictRepository repository;

    public List<EvidenceConflict> list(String projectId, boolean includeResolved) {
        LambdaQueryWrapper<EvidenceConflict> query = new LambdaQueryWrapper<EvidenceConflict>()
                .eq(EvidenceConflict::getProjectId, projectId)
                .orderByDesc(EvidenceConflict::getCreatedAt);
        if (!includeResolved) {
            query.eq(EvidenceConflict::getResolved, false);
        }
        return repository.selectList(query);
    }

    public EvidenceConflict resolve(String id, String resolution) {
        EvidenceConflict conflict = repository.selectById(id);
        if (conflict == null) {
            throw new IllegalArgumentException("证据冲突不存在: " + id);
        }
        conflict.setResolved(true);
        conflict.setResolution(resolution);
        conflict.setResolvedAt(LocalDateTime.now());
        repository.updateById(conflict);
        return conflict;
    }
}
