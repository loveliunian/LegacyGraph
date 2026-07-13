package io.github.legacygraph.service.normalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务域节点归一化器（graph-merge-optimization-plan.md 改进②）。
 * <p>
 * 业务域节点（BusinessDomain / BusinessProcess / BusinessObject / BusinessRule / Role）
 * 的命名常为中文自然语言且存在多种叫法（"用户中心" vs "会员管理" vs "账号域"）。
 * 本类为这类节点派生别名集合，供 {@code EvidenceGraphWriter} 写入 {@code aliasNames} 字段，
 * 供 {@code NodeBlockingService} 做 Blocking 召回。
 * </p>
 *
 * <h3>派生策略（4 路）</h3>
 * <ol>
 *   <li>原文 — 保留原始名称</li>
 *   <li>拼音首字母 — "用户中心" → "yhzx"</li>
 *   <li>后缀剥离 — 去掉"中心/管理/服务/域/系统/模块/平台"等业务域常用后缀</li>
 *   <li>同义词替换 — 账号↔用户↔会员、订单↔单据 等</li>
 * </ol>
 */
@Slf4j
@Component
public class BusinessDomainNormalizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 业务域常用后缀（剥离后得到核心词），按长度降序剥离 */
    private static final List<String> DOMAIN_SUFFIXES = Arrays.asList(
            "管理系统", "管理平台", "管理中心", "服务平台", "子系统",
            "中心", "管理", "服务", "系统", "模块", "平台", "域", "子域", "部分", "区域", "组", "区"
    );

    /** 同义词词典：核心词 → 同义变体列表（账号↔用户↔会员 等） */
    private static final Map<String, List<String>> SYNONYM_DICT = Map.ofEntries(
            Map.entry("用户", List.of("会员", "账号", "客户", "member", "user", "account")),
            Map.entry("账号", List.of("用户", "会员", "客户", "user", "member", "account")),
            Map.entry("会员", List.of("用户", "账号", "客户", "user", "account", "member")),
            Map.entry("订单", List.of("单据", "工单", "order", "ticket")),
            Map.entry("单据", List.of("订单", "工单", "order", "ticket")),
            Map.entry("商品", List.of("货品", "产品", "物料", "product", "item", "goods")),
            Map.entry("产品", List.of("商品", "货品", "物料", "product", "item", "goods")),
            Map.entry("支付", List.of("交易", "结算", "payment", "transaction")),
            Map.entry("交易", List.of("支付", "结算", "payment", "transaction")),
            Map.entry("权限", List.of("授权", "访问控制", "permission", "auth", "access")),
            Map.entry("授权", List.of("权限", "访问控制", "permission", "auth")),
            Map.entry("库存", List.of("仓储", "存货", "inventory", "stock")),
            Map.entry("仓储", List.of("库存", "存货", "inventory", "stock")),
            Map.entry("消息", List.of("通知", "公告", "message", "notification", "notice")),
            Map.entry("通知", List.of("消息", "公告", "message", "notification")),
            Map.entry("报表", List.of("统计", "分析", "report", "statistics", "analytics")),
            Map.entry("统计", List.of("报表", "分析", "report", "statistics", "analytics")),
            Map.entry("配置", List.of("设置", "参数", "config", "setting")),
            Map.entry("设置", List.of("配置", "参数", "config", "setting")),
            Map.entry("日志", List.of("审计", "记录", "log", "audit")),
            Map.entry("审计", List.of("日志", "记录", "log", "audit")),
            Map.entry("任务", List.of("作业", "调度", "task", "job")),
            Map.entry("调度", List.of("任务", "作业", "task", "job", "schedule")),
            Map.entry("文件", List.of("文档", "附件", "file", "document")),
            Map.entry("文档", List.of("文件", "附件", "file", "document")),
            Map.entry("组织", List.of("部门", "机构", "org", "department")),
            Map.entry("部门", List.of("组织", "机构", "org", "department")),
            Map.entry("角色", List.of("职位", "岗位", "role", "position"))
    );

    /** 业务域相关节点类型（需走本归一化器） */
    private static final Set<String> BUSINESS_DOMAIN_TYPES = Set.of(
            "BusinessDomain", "BusinessProcess", "BusinessObject", "BusinessRule", "Role"
    );

    private final HanyuPinyinOutputFormat pinyinFormat;

    public BusinessDomainNormalizer() {
        this.pinyinFormat = new HanyuPinyinOutputFormat();
        this.pinyinFormat.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        this.pinyinFormat.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    /**
     * 判断节点类型是否为业务域相关（需走业务域归一化通道）。
     */
    public boolean isBusinessDomainType(String nodeType) {
        return nodeType != null && BUSINESS_DOMAIN_TYPES.contains(nodeType);
    }

    /**
     * 为业务域节点名称派生别名集合。
     * <p>示例："用户中心" → ["用户中心", "yhzx", "用户", "会员", "账号", "客户", "user", "member", "account"]</p>
     *
     * @param name 节点名称（中文/英文均支持）
     * @return 去重后的别名列表（至少包含原文，不为空）
     */
    public List<String> deriveAliases(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String trimmed = name.trim();
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(trimmed);

        // 1. 拼音首字母："用户中心" → "yhzx"
        String pinyinInitials = toPinyinInitials(trimmed);
        if (pinyinInitials != null && !pinyinInitials.isBlank()) {
            aliases.add(pinyinInitials);
        }

        // 2. 后缀剥离："用户中心" → "用户"
        String core = stripSuffix(trimmed);
        if (!core.isBlank() && !core.equals(trimmed)) {
            aliases.add(core);
        }

        // 3. 同义词替换：核心词 → 同义变体
        if (!core.isBlank()) {
            List<String> synonyms = SYNONYM_DICT.get(core);
            if (synonyms != null) {
                aliases.addAll(synonyms);
            }
            // 也对原文做一次同义词匹配（原文可能是同义词本身）
            List<String> synonymsOfOriginal = SYNONYM_DICT.get(trimmed);
            if (synonymsOfOriginal != null) {
                aliases.addAll(synonymsOfOriginal);
            }
        }

        return new ArrayList<>(aliases);
    }

    /**
     * 将别名列表序列化为 JSON 数组字符串（供 aliasNames 字段存储）。
     */
    public String serializeAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(aliases);
        } catch (Exception e) {
            log.warn("Failed to serialize aliases {}: {}", aliases, e.getMessage());
            return null;
        }
    }

    /**
     * 派生别名并直接序列化为 JSON 字符串。
     * <p>便捷方法：deriveAliases + serializeAliases 的组合。</p>
     */
    public String deriveAliasesAsJson(String name) {
        List<String> aliases = deriveAliases(name);
        return aliases.isEmpty() ? null : serializeAliases(aliases);
    }

    /**
     * 中文 → 拼音首字母（小写）。非中文字符保留原样（小写）。
     * <p>"用户中心" → "yhzx"；"用户User中心" → "yhzx"（非中文字符跳过避免冗余）</p>
     */
    public String toPinyinInitials(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch > 127) {
                // 中文字符：取拼音首字母
                try {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(ch, pinyinFormat);
                    if (pinyinArray != null && pinyinArray.length > 0) {
                        sb.append(Character.toLowerCase(pinyinArray[0].charAt(0)));
                    }
                } catch (BadHanyuPinyinOutputFormatCombination e) {
                    // 部分生僻字无拼音，跳过
                }
            }
            // 非中文字符不拼入首字母串，避免 "user管理" → "user" 这种冗余
        }
        return sb.toString();
    }

    /**
     * 剥离业务域常用后缀，返回核心词。
     * <p>"用户管理中心" → "用户"；"订单系统" → "订单"</p>
     */
    public String stripSuffix(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String result = name.trim();
        // 按长度降序剥离，确保 "管理中心" 先于 "中心" / "管理" 剥离
        List<String> sortedSuffixes = new ArrayList<>(DOMAIN_SUFFIXES);
        sortedSuffixes.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String suffix : sortedSuffixes) {
            if (result.endsWith(suffix) && result.length() > suffix.length()) {
                result = result.substring(0, result.length() - suffix.length());
                break; // 只剥离一次最长后缀
            }
        }
        return result.trim();
    }
}
