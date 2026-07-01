package io.github.legacygraph.extractors.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.repository.FactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fact 持久化助手（doc 3.2）— 从 {@code ProjectScanner} 抽取的 saveFact 逻辑，
 * 供各 {@link ExtractionAdapter} 复用，避免 Adapter 旁路 Fact 落库。
 */
@Slf4j
@Component
public class FactPersister {

    private final FactRepository factRepository;
    private final ObjectMapper objectMapper;

    public FactPersister(FactRepository factRepository, ObjectMapper objectMapper) {
        this.factRepository = factRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存抽取事实（唯一约束：project_id + version_id + fact_type + fact_key，重复跳过）。
     */
    public void saveFact(String projectId, String versionId, String sourceType, String factType,
                         String factKey, String factName, String sourcePath,
                         Integer startLine, Integer endLine, Object data,
                         BigDecimal confidence, String status) {
        try {
            long exists = factRepository.selectCount(
                    new LambdaQueryWrapper<Fact>()
                            .eq(Fact::getProjectId, projectId)
                            .eq(Fact::getVersionId, versionId)
                            .eq(Fact::getFactType, factType)
                            .eq(Fact::getFactKey, factKey));
            if (exists > 0) {
                return;
            }
            Fact fact = new Fact();
            fact.setId(UUID.randomUUID().toString());
            fact.setProjectId(projectId);
            fact.setVersionId(versionId);
            fact.setFactType(factType);
            fact.setFactKey(factKey);
            fact.setFactName(factName);
            fact.setSourceType(sourceType);
            fact.setSourcePath(sourcePath);
            fact.setStartLine(startLine);
            fact.setEndLine(endLine);
            fact.setNormalizedData(objectMapper.writeValueAsString(data));
            fact.setConfidence(confidence != null ? confidence.doubleValue() : 0.0);
            fact.setStatus(status);
            fact.setCreatedAt(LocalDateTime.now());
            fact.setUpdatedAt(LocalDateTime.now());
            factRepository.insert(fact);
        } catch (Exception e) {
            log.error("Failed to save fact", e);
        }
    }
}
