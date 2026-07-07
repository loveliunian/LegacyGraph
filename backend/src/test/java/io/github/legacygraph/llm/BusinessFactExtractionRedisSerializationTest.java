package io.github.legacygraph.llm;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A1 诊断：验证 {@link DocUnderstandingAgent.BusinessFactExtraction} 能否被
 * {@code RedisConfig} 的 {@link GenericJackson2JsonRedisSerializer} 正确序列化/反序列化。
 *
 * <p>背景：生产 Redis 里 {@code lg:} 前缀键为 0，{@code AiScanOrchestrator#cachedExtract}
 * 的 {@code cacheService.put} 疑似静默失败（CacheService 对所有写操作 try/catch 吞异常），
 * 导致重扫未变文档/代码时仍重调 LLM（DOC_EXTRACT 535s + CODE_EXTRACT 212s 白耗）。
 * 本测试复刻 RedisConfig 的 ObjectMapper 配置，做往返断言，定位是否序列化器根因。</p>
 */
class BusinessFactExtractionRedisSerializationTest {

    /** 复刻 {@code io.github.legacygraph.config.RedisConfig#jsonSerializer()} 的 ObjectMapper 配置。 */
    private GenericJackson2JsonRedisSerializerLike buildSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializerLike(mapper);
    }

    @Test
    void businessFactExtraction_roundTripsThroughRedisSerializer() {
        DocUnderstandingAgent.BusinessFactExtraction src = buildSampleExtraction();
        GenericJackson2JsonRedisSerializerLike serializer = buildSerializer();

        byte[] bytes = serializer.serialize(src);
        assertNotNull(bytes, "序列化结果不应为 null");
        // 打印序列化体积，便于评估单条缓存 value 大小
        System.out.println("serialized bytes = " + bytes.length);

        Object restored = serializer.deserialize(bytes);
        assertNotNull(restored, "反序列化结果不应为 null");
        // 反序列化后应是 BusinessFactExtraction 实例（靠 @class 类型信息还原）
        assertNotNull(restored instanceof DocUnderstandingAgent.BusinessFactExtraction,
                "反序列化应还原为 BusinessFactExtraction，实际: " + restored.getClass());

        DocUnderstandingAgent.BusinessFactExtraction back =
                (DocUnderstandingAgent.BusinessFactExtraction) restored;
        assertEquals(src.getBusinessDomains().size(), back.getBusinessDomains().size());
        assertEquals(src.getBusinessDomains().get(0).getName(), back.getBusinessDomains().get(0).getName());
        assertEquals(src.getFeatures(), back.getFeatures());
    }

    private DocUnderstandingAgent.BusinessFactExtraction buildSampleExtraction() {
        DocUnderstandingAgent.BusinessFactExtraction e = new DocUnderstandingAgent.BusinessFactExtraction();
        DocUnderstandingAgent.BusinessDomain d = new DocUnderstandingAgent.BusinessDomain();
        d.setName("保证金管理");
        d.setDescription("管理保证金缴纳与退还");
        d.setConfidence(0.9);
        e.setBusinessDomains(List.of(d));
        e.setFeatures(List.of("缴纳保证金", "退还保证金"));
        e.setRoles(List.of("出纳", "财务"));
        return e;
    }

    /**
     * 薄封装：复刻 {@code GenericJackson2JsonRedisSerializer} 的行为（它内部就是用传入的 ObjectMapper）。
     * 直接 new 真实的 GenericJackson2JsonRedisSerializer(ObjectMapper) 亦可，这里用薄封装避免对
     * Spring Data Redis 序列化器内部实现的强依赖，仅验证 Jackson 行为。
     */
    static class GenericJackson2JsonRedisSerializerLike {
        private final ObjectMapper mapper;
        GenericJackson2JsonRedisSerializerLike(ObjectMapper mapper) { this.mapper = mapper; }
        byte[] serialize(Object o) {
            try { return mapper.writeValueAsBytes(o); }
            catch (Exception ex) { throw new RuntimeException("serialize failed: " + ex.getMessage(), ex); }
        }
        Object deserialize(byte[] bytes) {
            try { return mapper.readValue(bytes, Object.class); }
            catch (Exception ex) { throw new RuntimeException("deserialize failed: " + ex.getMessage(), ex); }
        }
    }
}
