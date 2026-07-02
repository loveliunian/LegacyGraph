package io.github.legacygraph.extractors.adapter;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExtractionAdapter 接口测试。
 * 验证接口契约方法存在且签名正确。
 */
class ExtractionAdapterTest {

    /**
     * 验证接口定义了支持性检查方法。
     */
    @Test
    void interfaceHasSupportsMethod() throws NoSuchMethodException {
        Method supports = ExtractionAdapter.class.getMethod(
                "supports", ScanContext.class, SourceAsset.class);

        assertEquals(boolean.class, supports.getReturnType());
    }

    /**
     * 验证接口定义了抽取方法。
     */
    @Test
    void interfaceHasExtractMethod() throws NoSuchMethodException {
        Method extract = ExtractionAdapter.class.getMethod(
                "extract", ScanContext.class, SourceAsset.class);

        assertEquals(ExtractionResult.class, extract.getReturnType());
    }

    /**
     * 验证接口定义了能力描述方法。
     */
    @Test
    void interfaceHasCapabilityMethod() throws NoSuchMethodException {
        Method capability = ExtractionAdapter.class.getMethod("capability");

        assertEquals(AdapterCapability.class, capability.getReturnType());
    }

    /**
     * 验证接口只有三个方法（不支持 isApplicable 等冗余方法）。
     */
    @Test
    void interfaceHasExactlyThreeMethods() {
        Method[] methods = ExtractionAdapter.class.getDeclaredMethods();

        assertEquals(3, methods.length, "接口方法数应为3个");
    }
}
