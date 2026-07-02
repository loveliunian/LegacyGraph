package io.github.legacygraph.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FeatureIdentityNormalizer 单元测试。
 * 验证 Feature 身份归一化规则与 nodeKey 生成。
 */
@ExtendWith(MockitoExtension.class)
class FeatureIdentityNormalizerTest {

    private final FeatureIdentityNormalizer normalizer = new FeatureIdentityNormalizer();

    /**
     * 测试普通功能名转 nodeKey。
     */
    @Test
    void toFeatureKey_normalizesAndPrependsPrefix() {
        assertEquals("feature:订单创建", normalizer.toFeatureKey("订单创建"));
        assertEquals("feature:用户管理", normalizer.toFeatureKey("用户管理"));
    }

    /**
     * 测试带前缀名称的去重归一。
     */
    @Test
    void toFeatureKey_stripsSourcePrefixes() {
        assertEquals("feature:订单创建", normalizer.toFeatureKey("feature:订单创建"));
        assertEquals("feature:订单创建", normalizer.toFeatureKey("code-feature:订单创建"));
        assertEquals("feature:订单创建", normalizer.toFeatureKey("功能:订单创建"));
        assertEquals("feature:订单创建", normalizer.toFeatureKey("特性:订单创建"));
    }

    /**
     * 测试大小写归一化。
     */
    @Test
    void normalizeName_lowercases() {
        assertEquals("order creating", normalizer.normalizeName("ORDER CREATING"));
        assertEquals("ordercreating", normalizer.normalizeName("OrderCreating"));
    }

    /**
     * 测试空输入处理。
     */
    @Test
    void normalizeName_withNull_returnsEmpty() {
        assertEquals("", normalizer.normalizeName(null));
    }

    /**
     * 测试 null 输入返回 null。
     */
    @Test
    void toFeatureKey_withNull_returnsNull() {
        assertNull(normalizer.toFeatureKey(null));
    }

    /**
     * 测试中文/全角标点归一为空格。
     */
    @Test
    void normalizeName_replacesChinesePunctuationWithSpace() {
        assertEquals("订单 创建 提交", normalizer.normalizeName("订单、创建、提交"));
        assertEquals("订单 创建 提交", normalizer.normalizeName("订单，创建。提交"));
    }

    /**
     * 测试连续空白压缩。
     */
    @Test
    void normalizeName_collapsesWhitespace() {
        assertEquals("订单 创建", normalizer.normalizeName("订单   创建"));
        assertEquals("订单 创建", normalizer.normalizeName("  订单   创建  "));
    }
}
