package io.github.legacygraph.service.requirement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.requirement.ItemPatchRequest;
import io.github.legacygraph.entity.AcceptanceCriterion;
import io.github.legacygraph.entity.RequirementItem;
import io.github.legacygraph.repository.AcceptanceCriterionRepository;
import io.github.legacygraph.repository.RequirementItemRepository;
import io.github.legacygraph.repository.RequirementRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求条目增量更新服务（G-22）。
 * <p>支持对单条 RequirementItem 的部分字段更新与增量 clarify，
 * 避免整需求重新抽取。</p>
 */
@Slf4j
@Service
public class RequirementPatchService {

    private final RequirementRepository requirementRepository;
    private final RequirementItemRepository itemRepository;
    private final AcceptanceCriterionRepository criterionRepository;
    private final ObjectMapper objectMapper;

    public RequirementPatchService(RequirementRepository requirementRepository,
                                   RequirementItemRepository itemRepository,
                                   AcceptanceCriterionRepository criterionRepository,
                                   ObjectMapper objectMapper) {
        this.requirementRepository = requirementRepository;
        this.itemRepository = itemRepository;
        this.criterionRepository = criterionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 增量更新单条需求条目（PATCH 语义）。
     * <p>仅更新 patch 中非 null 的字段：
     * <ul>
     *   <li>text：直接覆盖条目描述</li>
     *   <li>constraints：序列化为 JSON 覆盖 constraintsJson</li>
     *   <li>acceptanceCriteria：先删除该条目的旧 AC，再插入新的</li>
     * </ul></p>
     *
     * @param requirementId 需求 ID
     * @param itemCode      条目编码（如 R1、R2）
     * @param patch         增量更新请求
     * @return 更新后的需求条目
     */
    @Transactional
    public RequirementItem patchItem(String requirementId, String itemCode, ItemPatchRequest patch) {
        RequirementItem item = loadItem(requirementId, itemCode);

        // 更新 text
        if (patch.getText() != null) {
            item.setText(patch.getText());
        }

        // 更新 constraints（序列化为 JSON）
        if (patch.getConstraints() != null) {
            item.setConstraintsJson(writeJsonSafe(patch.getConstraints()));
        }

        // 更新验收条件：先删旧 AC，再插新 AC
        if (patch.getAcceptanceCriteria() != null) {
            replaceAcceptanceCriteria(item.getId(), patch.getAcceptanceCriteria());
        }

        itemRepository.updateById(item);
        log.info("Item patched: requirementId={}, itemCode={}", requirementId, itemCode);
        return item;
    }

    /**
     * 增量 clarify 单条需求条目。
     * <p>简化实现：将用户回答追加到 item.text，实际 LLM 调用在后续接入。</p>
     *
     * @param requirementId 需求 ID
     * @param itemCode      条目编码
     * @param answers       问答列表
     * @return 更新后的需求条目
     */
    @Transactional
    public RequirementItem clarifyItem(String requirementId, String itemCode, List<QA> answers) {
        RequirementItem item = loadItem(requirementId, itemCode);

        if (answers != null && !answers.isEmpty()) {
            StringBuilder sb = new StringBuilder(item.getText() == null ? "" : item.getText());
            sb.append("\n\n## 补充信息\n");
            for (QA qa : answers) {
                sb.append("- ").append(qa.getQuestion())
                        .append(": ").append(qa.getAnswer()).append("\n");
            }
            item.setText(sb.toString());
        }

        itemRepository.updateById(item);
        log.info("Item clarified: requirementId={}, itemCode={}, answers={}",
                requirementId, itemCode, answers != null ? answers.size() : 0);
        return item;
    }

    // ==================== 内部方法 ====================

    /**
     * 根据需求 ID 与条目编码加载单条 RequirementItem。
     */
    private RequirementItem loadItem(String requirementId, String itemCode) {
        LambdaQueryWrapper<RequirementItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementItem::getRequirementId, requirementId)
                .eq(RequirementItem::getCode, itemCode);
        RequirementItem item = itemRepository.selectOne(wrapper);
        if (item == null) {
            throw new IllegalArgumentException(
                    "需求条目不存在: requirementId=" + requirementId + ", itemCode=" + itemCode);
        }
        return item;
    }

    /**
     * 替换指定条目的验收条件：先删除旧 AC，再插入新 AC。
     */
    private void replaceAcceptanceCriteria(String itemId, List<String> newCriteria) {
        // 删除旧 AC
        LambdaQueryWrapper<AcceptanceCriterion> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(AcceptanceCriterion::getRequirementItemId, itemId);
        criterionRepository.delete(deleteWrapper);

        // 插入新 AC
        if (newCriteria != null) {
            for (String ac : newCriteria) {
                if (ac == null || ac.isBlank()) {
                    continue;
                }
                AcceptanceCriterion criterion = new AcceptanceCriterion();
                criterion.setRequirementItemId(itemId);
                criterion.setText(ac);
                criterion.setStatus("PENDING");
                criterion.setCreatedAt(LocalDateTime.now());
                criterionRepository.insert(criterion);
            }
        }
    }

    /**
     * 将对象序列化为 JSON 字符串，失败返回 null。
     */
    private String writeJsonSafe(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize {}: {}", value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    // ==================== 内部类 ====================

    /**
     * 问答对（增量 clarify 用）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QA {
        /** 问题 */
        private String question;
        /** 回答 */
        private String answer;
    }
}
