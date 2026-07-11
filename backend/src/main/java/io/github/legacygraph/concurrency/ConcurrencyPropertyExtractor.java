package io.github.legacygraph.concurrency;

import io.github.legacygraph.extractors.JavaStructureExtractor.JavaMethodInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L-10: 从方法注解和修饰符中提取并发属性。
 * <p>
 * 识别的注解：
 * <ul>
 *   <li>{@code @Transactional} → transactional=true</li>
 *   <li>{@code @Async} → async=true</li>
 *   <li>{@code @Cacheable} → cacheable=true</li>
 *   <li>{@code @Lock} → lockType=注解参数值（如 READ/WRITE/OPTIMISTIC）</li>
 * </ul>
 * 识别的修饰符：
 * <ul>
 *   <li>{@code synchronized} → synchronized=true</li>
 * </ul>
 */
public final class ConcurrencyPropertyExtractor {

    private static final Set<String> TRANSACTIONAL_ANNOTATIONS = Set.of("Transactional");
    private static final Set<String> ASYNC_ANNOTATIONS = Set.of("Async", "AsyncFor");
    private static final Set<String> CACHEABLE_ANNOTATIONS = Set.of("Cacheable", "CachePut", "CacheEvict");
    private static final Set<String> LOCK_ANNOTATIONS = Set.of("Lock");

    private ConcurrencyPropertyExtractor() {}

    /**
     * 从 JavaMethodInfo 提取并发属性。
     *
     * @param methodInfo 方法信息（含注解和修饰符）
     * @return 属性 Map（可能为空），包含 transactional/async/cacheable/lockType/synchronized 键
     */
    public static Map<String, Object> extract(JavaMethodInfo methodInfo) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (methodInfo == null) {
            return props;
        }

        // synchronized 修饰符
        if (methodInfo.isSynchronizedModifier()) {
            props.put("synchronized", true);
        }

        List<String> annotations = methodInfo.getAnnotations();
        if (annotations == null || annotations.isEmpty()) {
            return props;
        }

        // 遍历注解，提取并发属性
        for (String annotation : annotations) {
            String normalized = normalizeAnnotationName(annotation);

            if (TRANSACTIONAL_ANNOTATIONS.contains(normalized)) {
                props.put("transactional", true);
            } else if (ASYNC_ANNOTATIONS.contains(normalized)) {
                props.put("async", true);
            } else if (CACHEABLE_ANNOTATIONS.contains(normalized)) {
                props.put("cacheable", true);
            } else if (LOCK_ANNOTATIONS.contains(normalized)) {
                // 尝试提取锁类型：@Lock(READ) / @Lock(LockType.READ) 等
                String lockType = extractLockType(annotation);
                props.put("lockType", lockType != null ? lockType : "UNKNOWN");
            }
        }

        return props;
    }

    /** 去掉注解的包名前缀和参数，只保留简单名 */
    private static String normalizeAnnotationName(String annotation) {
        // 去掉参数部分：@Transactional(readOnly=true) → Transactional
        int parenIdx = annotation.indexOf('(');
        String name = parenIdx > 0 ? annotation.substring(0, parenIdx) : annotation;
        // 去掉包名前缀：org.springframework.transaction.annotation.Transactional → Transactional
        int dotIdx = name.lastIndexOf('.');
        return dotIdx > 0 ? name.substring(dotIdx + 1) : name;
    }

    /** 从 @Lock(READ) 或 @Lock(LockType.READ) 中提取锁类型 */
    private static String extractLockType(String annotation) {
        int parenIdx = annotation.indexOf('(');
        if (parenIdx < 0) return null;
        String args = annotation.substring(parenIdx + 1);
        // 去掉结尾的 )
        int closeIdx = args.lastIndexOf(')');
        if (closeIdx > 0) {
            args = args.substring(0, closeIdx);
        }
        args = args.trim();
        // 处理 LockType.READ 形式
        int dotIdx = args.lastIndexOf('.');
        if (dotIdx >= 0) {
            args = args.substring(dotIdx + 1);
        }
        return args.isEmpty() ? null : args.toUpperCase();
    }
}
