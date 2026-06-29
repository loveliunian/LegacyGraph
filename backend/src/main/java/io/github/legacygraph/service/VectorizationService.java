package io.github.legacygraph.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 向量化服务 - 占位符实现（Spring AI 已禁用）
 * 所有方法返回默认值并记录警告日志
 */
@Slf4j
@Service
public class VectorizationService {

    /**
     * 对单个文档分片进行向量化并存储（占位符实现）
     * @return null 表示未实际执行
     */
    public Long embedAndStore(Long projectId, String chunkType, String sourceUri,
                              int chunkIndex, String content, String embeddingModelName, int dim) {
        log.warn("VectorizationService.embedAndStore is a placeholder - Spring AI disabled");
        return null;
    }

    /**
     * 对文本进行分片（占位符实现）
     */
    public List<String> chunkDocument(String content, int maxTokens, int overlapTokens) {
        log.warn("VectorizationService.chunkDocument is a placeholder - Spring AI disabled");
        return Collections.emptyList();
    }

    /**
     * 近似去重检测（占位符实现）
     */
    public boolean isProbablyDuplicate(List<Double> embedding1, List<Double> embedding2,
                                       int len1, int len2) {
        log.warn("VectorizationService.isProbablyDuplicate is a placeholder - Spring AI disabled");
        return false;
    }
}
