package io.github.legacygraph.service.evaluation;

import io.github.legacygraph.dto.evaluation.RagasReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ragas 指标计算服务 — 简化版实现，不调用 LLM。
 * <p>
 * 通过关键词匹配与句子级别的依据检测，估算四项核心 Ragas 指标：
 * <ul>
 *   <li>contextPrecision：期望实体在检索上下文中的命中率</li>
 *   <li>contextRecall：检索上下文对期望实体的覆盖率</li>
 *   <li>faithfulness：答案句子在检索上下文中有依据的比例（蕴含率）</li>
 *   <li>answerRelevancy：答案与问题的关键词重合度</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class RagasMetricsService {

    /**
     * 对单次问答进行 Ragas 指标评估。
     *
     * @param question          用户问题
     * @param answer            实际回答
     * @param retrievedContexts 检索到的上下文片段列表
     * @param expectedEntities  期望被命中的实体列表（如表名 / 类名 / 字段名）
     * @param expectedKeywords  期望被覆盖的关键词列表
     * @return Ragas 指标报告
     */
    public RagasReport evaluate(String question, String answer,
                               List<String> retrievedContexts,
                               List<String> expectedEntities,
                               List<String> expectedKeywords) {
        // 空值保护
        question = question == null ? "" : question;
        answer = answer == null ? "" : answer;
        retrievedContexts = retrievedContexts == null ? List.of() : retrievedContexts;
        expectedEntities = expectedEntities == null ? List.of() : expectedEntities;
        expectedKeywords = expectedKeywords == null ? List.of() : expectedKeywords;

        double contextPrecision = calculateContextPrecision(retrievedContexts, expectedEntities, expectedKeywords);
        double contextRecall = calculateContextRecall(retrievedContexts, expectedEntities);
        double faithfulness = calculateFaithfulness(answer, retrievedContexts);
        double answerRelevancy = calculateAnswerRelevancy(question, answer);

        // 附加详情
        Map<String, String> details = new HashMap<>();
        details.put("contextCount", String.valueOf(retrievedContexts.size()));
        details.put("expectedEntityCount", String.valueOf(expectedEntities.size()));
        details.put("expectedKeywordCount", String.valueOf(expectedKeywords.size()));
        details.put("answerLength", String.valueOf(answer.length()));
        details.put("questionLength", String.valueOf(question.length()));
        details.put("answerSentenceCount", String.valueOf(splitSentences(answer).size()));

        log.info("RagasMetricsService: questionLen={}, answerLen={}, contexts={}, contextPrecision={}, contextRecall={}, faithfulness={}, answerRelevancy={}",
                question.length(), answer.length(), retrievedContexts.size(),
                String.format(Locale.ROOT, "%.4f", contextPrecision),
                String.format(Locale.ROOT, "%.4f", contextRecall),
                String.format(Locale.ROOT, "%.4f", faithfulness),
                String.format(Locale.ROOT, "%.4f", answerRelevancy));

        return RagasReport.builder()
                .contextPrecision(contextPrecision)
                .contextRecall(contextRecall)
                .faithfulness(faithfulness)
                .answerRelevancy(answerRelevancy)
                .details(details)
                .build();
    }

    /**
     * 上下文精确度：expectedEntities 中有多少出现在 retrievedContexts 中 → 命中率。
     * <p>
     * 同时考虑 expectedKeywords 的命中情况，综合计算命中率。
     * </p>
     */
    private double calculateContextPrecision(List<String> retrievedContexts,
                                            List<String> expectedEntities,
                                            List<String> expectedKeywords) {
        if (expectedEntities.isEmpty() && expectedKeywords.isEmpty()) {
            return 1.0;
        }
        String contextsLower = joinLower(retrievedContexts);
        int total = 0;
        int hit = 0;
        // 检查期望实体命中情况
        for (String entity : expectedEntities) {
            if (entity == null || entity.isBlank()) continue;
            total++;
            if (contextsLower.contains(entity.toLowerCase(Locale.ROOT))) {
                hit++;
            }
        }
        // 检查期望关键词命中情况
        for (String keyword : expectedKeywords) {
            if (keyword == null || keyword.isBlank()) continue;
            total++;
            if (contextsLower.contains(keyword.toLowerCase(Locale.ROOT))) {
                hit++;
            }
        }
        return total == 0 ? 1.0 : (double) hit / total;
    }

    /**
     * 上下文召回率：retrievedContexts 中有多少包含 expectedEntities → 覆盖率。
     * <p>
     * 计算期望实体被检索上下文覆盖的比例（即多少期望实体能在上下文中找到）。
     * </p>
     */
    private double calculateContextRecall(List<String> retrievedContexts,
                                         List<String> expectedEntities) {
        if (expectedEntities.isEmpty()) {
            return 1.0;
        }
        if (retrievedContexts.isEmpty()) {
            return 0.0;
        }
        String contextsLower = joinLower(retrievedContexts);
        int covered = 0;
        for (String entity : expectedEntities) {
            if (entity == null || entity.isBlank()) continue;
            if (contextsLower.contains(entity.toLowerCase(Locale.ROOT))) {
                covered++;
            }
        }
        return (double) covered / expectedEntities.size();
    }

    /**
     * 答案忠实度：answer 中有多少句子能在 retrievedContexts 中找到依据 → 蕴含率。
     * <p>
     * 将答案按句切分，逐句检查其核心词是否在检索上下文中出现，
     * 若一半以上的词命中则认为该句有依据。
     * </p>
     */
    private double calculateFaithfulness(String answer, List<String> retrievedContexts) {
        List<String> sentences = splitSentences(answer);
        if (sentences.isEmpty()) {
            return 1.0;
        }
        if (retrievedContexts.isEmpty()) {
            return 0.0;
        }
        String contextsLower = joinLower(retrievedContexts);
        int grounded = 0;
        for (String sentence : sentences) {
            String sentenceLower = sentence.toLowerCase(Locale.ROOT);
            // 提取句子中的核心词（长度>1 的非停用词）进行匹配
            List<String> tokens = tokenize(sentenceLower);
            if (tokens.isEmpty()) continue;
            // 至少一半的词在上下文中出现，认为该句子有依据
            long matched = tokens.stream()
                    .filter(token -> contextsLower.contains(token))
                    .count();
            if ((double) matched / tokens.size() >= 0.5) {
                grounded++;
            }
        }
        return (double) grounded / sentences.size();
    }

    /**
     * 答案相关性：answer 与 question 的关键词重合度。
     * <p>
     * 提取问题关键词，计算其中有多少出现在答案中。
     * </p>
     */
    private double calculateAnswerRelevancy(String question, String answer) {
        Set<String> questionTokens = new HashSet<>(tokenize(question.toLowerCase(Locale.ROOT)));
        Set<String> answerTokens = new HashSet<>(tokenize(answer.toLowerCase(Locale.ROOT)));
        if (questionTokens.isEmpty()) {
            return 0.0;
        }
        // 问题关键词中有多少出现在答案中
        long overlap = questionTokens.stream()
                .filter(answerTokens::contains)
                .count();
        return (double) overlap / questionTokens.size();
    }

    // ==================== 辅助方法 ====================

    /** 将多个上下文合并为小写字符串 */
    private String joinLower(List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "";
        }
        return contexts.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

    /**
     * 分句：按中英文标点分句。
     */
    private List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> sentences = new ArrayList<>();
        // 按中英文句末标点分句
        String[] parts = text.split("[。？！.!?;；\\n]+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() > 2) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    /**
     * 分词：简化分词（按非字母数字汉字切分，过滤停用词与短词）。
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 按非字母数字汉字切分
        String[] raw = text.split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
        return Arrays.stream(raw)
                .filter(t -> t != null && t.length() > 1)
                .filter(t -> !isStopWord(t))
                .collect(Collectors.toList());
    }

    /** 简单英文 + 中文停用词表 */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "shall", "can", "of", "to", "in",
            "for", "on", "with", "as", "by", "at", "from", "this", "that", "these",
            "those", "it", "its", "and", "or", "but", "if", "then", "else",
            "what", "which", "who", "when", "where", "why", "how", "all", "any",
            "both", "each", "few", "more", "most", "other", "some", "such",
            "no", "nor", "not", "only", "own", "same", "so", "than", "too",
            "very", "just", "about", "above", "after", "again", "against",
            "below", "between", "into", "through", "during", "before",
            "up", "down", "out", "off", "over", "under", "further", "once",
            "here", "there", "your", "you", "we", "they", "he", "she",
            "的", "了", "是", "在", "和", "与", "或", "也", "都", "就", "还",
            "把", "被", "让", "给", "向", "于", "对", "为", "以", "由",
            "这", "那", "它", "他", "她", "我", "你", "们", "个", "中",
            "上", "下", "里", "外", "前", "后", "又", "但", "而", "如",
            "要", "会", "能", "可", "已", "正", "将", "着", "过");

    private boolean isStopWord(String word) {
        return STOP_WORDS.contains(word.toLowerCase(Locale.ROOT));
    }
}
