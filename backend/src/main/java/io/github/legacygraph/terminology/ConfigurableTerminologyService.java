package io.github.legacygraph.terminology;

import io.github.legacygraph.entity.TerminologyMapping;
import io.github.legacygraph.repository.TerminologyMappingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于数据库的术语服务实现。
 *
 * <p>术语映射存储于 {@code lg_terminology_mapping} 表（原 {@code BusinessGraphBuilder.CN_ALIAS}），
 * 以后新增术语直接入库即可，无需改代码/改配置重新部署。启动时（{@link #reload()}）加载全表并建
 * 内存缓存加速匹配，运行期可调用 {@link #reload()} 热刷新。相似度阈值仍来自 {@link TerminologyProperties}。</p>
 *
 * <p>匹配行为与原实现保持一致：</p>
 * <ul>
 *   <li>用重叠系数（intersection / min(|A|,|B|)）而非 Jaccard，避免中文术语展开后 token 数不对等被稀释</li>
 *   <li>小集合（&lt;3 token）必须完全包含，避免单 token 共享误匹配</li>
 *   <li>词典 key 按长度降序，最长优先，避免"主账户"被"账户"抢先消费</li>
 * </ul>
 */
@Slf4j
@Service
public class ConfigurableTerminologyService implements TerminologyService {

    private final TerminologyProperties properties;
    private final TerminologyMappingRepository terminologyMappingRepository;

    /** 术语映射内存缓存（volatile 保证 reload 后其他线程可见）。 */
    private volatile Map<String, List<String>> termMappings = Collections.emptyMap();
    /** 词典 key 按长度降序，匹配时最长优先。 */
    private volatile List<String> termKeysDesc = Collections.emptyList();

    public ConfigurableTerminologyService(TerminologyProperties properties,
                                          TerminologyMappingRepository terminologyMappingRepository) {
        this.properties = properties;
        this.terminologyMappingRepository = terminologyMappingRepository;
    }

    /**
     * 从数据库加载术语映射并重建内存缓存。启动时自动执行，运行期可手动调用以热刷新。
     */
    @PostConstruct
    public void reload() {
        Map<String, List<String>> mappings = new LinkedHashMap<>();
        try {
            List<TerminologyMapping> rows = terminologyMappingRepository.selectList(null);
            if (rows != null) {
                rows.stream()
                        .filter(r -> !"DISABLED".equalsIgnoreCase(r.getStatus()))
                        .sorted(Comparator.comparingInt(r -> r.getSortOrder() == null ? 0 : r.getSortOrder()))
                        .forEach(row -> {
                            if (row.getSourceTerm() != null && row.getTargetTerms() != null && !row.getTargetTerms().isEmpty()) {
                                mappings.put(row.getSourceTerm(), List.copyOf(row.getTargetTerms()));
                            }
                        });
            }
        } catch (Exception e) {
            // 加载失败时保留旧缓存（首次启动则为空），不阻断服务
            log.error("Failed to load terminology mappings from database, keeping previous cache", e);
            return;
        }
        List<String> keys = new ArrayList<>(mappings.keySet());
        keys.sort((x, y) -> Integer.compare(y.length(), x.length()));
        this.termMappings = Collections.unmodifiableMap(mappings);
        this.termKeysDesc = Collections.unmodifiableList(keys);
        log.info("Loaded {} terminology mappings from database", mappings.size());
    }

    @Override
    public List<String> getTargetTerms(String sourceTerm) {
        return termMappings.getOrDefault(sourceTerm, Collections.emptyList());
    }

    @Override
    public double calculateSimilarity(String name1, String name2) {
        if (name1 == null || name2 == null || name1.isBlank() || name2.isBlank()) {
            return 0;
        }
        return similarityOfTokens(tokenize(name1), tokenize(name2), name1, name2);
    }

    @Override
    public double similarityOfTokens(Set<String> tokens1, Set<String> tokens2, String name1, String name2) {
        TerminologyProperties.SimilarityConfig config = properties.getSimilarity();
        if (tokens1 != null && tokens2 != null && !tokens1.isEmpty() && !tokens2.isEmpty()) {
            int inter = 0;
            for (String t : tokens1) {
                if (tokens2.contains(t)) {
                    inter++;
                }
            }
            if (inter == 0) {
                return 0;
            }
            int min = Math.min(tokens1.size(), tokens2.size());
            // 单 token 集合：仅当共享 token 足够长且被完全包含时才认，避免短 token 误匹配，
            // 同时让单字业务对象名（order/account/user）能与 orders/OrderService 等匹配上。
            if (min < 2) {
                if (inter == min) {
                    Set<String> smaller = tokens1.size() <= tokens2.size() ? tokens1 : tokens2;
                    String sole = smaller.iterator().next();
                    if (sole != null && sole.length() >= config.getMinTokenLength()) {
                        return 1.0;
                    }
                }
                return 0;
            }
            // 小集合必须完全包含，杜绝单 token 误匹配
            if (inter != min && min < config.getSmallSetThreshold()) {
                return 0;
            }
            return (double) inter / min;
        }
        // 兜底：无法分词或 token 无交集时用包含关系
        if (name1 != null && name2 != null
                && name1.length() >= config.getMinMatchLength() && name2.length() >= config.getMinMatchLength()
                && (name1.contains(name2) || name2.contains(name1))) {
            return config.getContainsSimilarity();
        }
        return name1 != null && name1.equalsIgnoreCase(name2) ? config.getExactMatchSimilarity() : 0;
    }

    @Override
    public Set<String> tokenize(String name) {
        Set<String> tokens = new HashSet<>();
        if (name == null || name.isBlank()) {
            return tokens;
        }
        // 1) 拆 camelCase（保留大小写先拆，再统一小写）
        String expanded = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        String lower = expanded.toLowerCase();
        // 2) 中文术语 → 英文子词（最长优先）。无中文字符时跳过词典扫描（英文名走 camelCase 拆分即可）
        if (hasCjk(lower)) {
            Map<String, List<String>> mappings = this.termMappings;
            for (String cn : this.termKeysDesc) {
                int idx = lower.indexOf(cn);
                while (idx >= 0) {
                    List<String> en = mappings.get(cn);
                    if (en != null) {
                        tokens.addAll(en);
                    }
                    lower = lower.substring(0, idx) + " " + lower.substring(idx + cn.length());
                    idx = lower.indexOf(cn);
                }
            }
        }
        // 3) 剩余英文片段
        for (String t : lower.split("[^a-z0-9]+")) {
            if (t.length() >= 2) {
                tokens.add(stem(t));
            }
        }
        return tokens;
    }

    /**
     * 粗粒度英文复数词干归一，让 order/orders、service/services 折叠为同一 token。
     * <p>仅对长度≥4 的 token 生效，避免误伤 is/as/us 等短词。</p>
     */
    private static String stem(String t) {
        if (t == null || t.length() < 4) {
            return t;
        }
        if (t.endsWith("ies")) {
            return t.substring(0, t.length() - 3) + "y";
        }
        if (t.endsWith("ses") || t.endsWith("xes")) {
            return t.substring(0, t.length() - 2);
        }
        if (t.endsWith("s") && !t.endsWith("ss")) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** 是否含 CJK 汉字（用于 tokenize 跳过英文名称的词典扫描）。 */
    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '一' && c <= '\u9fff') {
                return true;
            }
        }
        return false;
    }
}
