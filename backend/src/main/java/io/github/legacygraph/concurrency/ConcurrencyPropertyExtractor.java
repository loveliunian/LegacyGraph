package io.github.legacygraph.concurrency;

import io.github.legacygraph.extractors.JavaStructureExtractor.JavaMethodInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * L-10 / S2-T4: 从方法注解、修饰符和方法体中提取并发属性。
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
 * S2-T4 新增：方法体并发原语检测
 * <ul>
 *   <li>{@code volatile} 字段 → volatileField=true</li>
 *   <li>{@code AtomicXxx} 类使用 → atomicUsage=list</li>
 *   <li>{@code Lock/ReentrantLock} API → lockApi=true</li>
 *   <li>{@code CompletableFuture} → completableFuture=true</li>
 *   <li>{@code Thread/Runnable/Executor} → threadUsage=true</li>
 *   <li>{@code CountDownLatch/CyclicBarrier/Semaphore/Phaser} → syncUtility=true</li>
 * </ul>
 */
public final class ConcurrencyPropertyExtractor {

    private static final Set<String> TRANSACTIONAL_ANNOTATIONS = Set.of("Transactional");
    private static final Set<String> ASYNC_ANNOTATIONS = Set.of("Async", "AsyncFor");
    private static final Set<String> CACHEABLE_ANNOTATIONS = Set.of("Cacheable", "CachePut", "CacheEvict");
    private static final Set<String> LOCK_ANNOTATIONS = Set.of("Lock");

    // S2-T4: 并发原语正则模式
    private static final Pattern VOLATILE_PATTERN = Pattern.compile(
            "\\bvolatile\\s+\\w");
    private static final Pattern ATOMIC_PATTERN = Pattern.compile(
            "\\bAtomic(?:Integer|Long|Boolean|Reference|IntegerArray|LongArray|ReferenceArray|MarkableReference|StampedReference)\\b");
    private static final Pattern LOCK_API_PATTERN = Pattern.compile(
            "\\b(?:\\w+\\.)?(?:lock|tryLock|unlock|newCondition)\\s*\\(");
    private static final Pattern REENTRANT_LOCK_PATTERN = Pattern.compile(
            "\\bReentrant(?:ReadWrite)?Lock\\b");
    private static final Pattern COMPLETABLE_FUTURE_PATTERN = Pattern.compile(
            "\\bCompletableFuture\\b");
    private static final Pattern THREAD_PATTERN = Pattern.compile(
            "\\b(?:new\\s+Thread|extends\\s+Thread|implements\\s+Runnable|ExecutorService|ThreadPoolExecutor|Executors\\.)\\b");
    private static final Pattern SYNC_UTILITY_PATTERN = Pattern.compile(
            "\\b(?:CountDownLatch|CyclicBarrier|Semaphore|Phaser|Exchanger)\\b");

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
        if (annotations != null && !annotations.isEmpty()) {
            for (String annotation : annotations) {
                String normalized = normalizeAnnotationName(annotation);

                if (TRANSACTIONAL_ANNOTATIONS.contains(normalized)) {
                    props.put("transactional", true);
                } else if (ASYNC_ANNOTATIONS.contains(normalized)) {
                    props.put("async", true);
                } else if (CACHEABLE_ANNOTATIONS.contains(normalized)) {
                    props.put("cacheable", true);
                } else if (LOCK_ANNOTATIONS.contains(normalized)) {
                    String lockType = extractLockType(annotation);
                    props.put("lockType", lockType != null ? lockType : "UNKNOWN");
                }
            }
        }

        return props;
    }

    /**
     * S2-T4: 从方法体文本提取并发原语属性。
     * <p>
     * 检测方法体中的并发原语使用，补充注解级检测无法覆盖的场景。
     * 方法体文本可从 JavaParser MethodDeclaration.toString() 获取。
     * </p>
     *
     * @param methodBody 方法体文本（不含签名）
     * @return 并发原语属性 Map
     */
    public static Map<String, Object> extractFromBody(String methodBody) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (methodBody == null || methodBody.isBlank()) {
            return props;
        }

        // volatile 字段
        if (VOLATILE_PATTERN.matcher(methodBody).find()) {
            props.put("volatileField", true);
        }

        // AtomicXxx 类使用
        List<String> atomicTypes = new ArrayList<>();
        var atomicMatcher = ATOMIC_PATTERN.matcher(methodBody);
        while (atomicMatcher.find()) {
            String matched = atomicMatcher.group();
            if (!atomicTypes.contains(matched)) {
                atomicTypes.add(matched);
            }
        }
        if (!atomicTypes.isEmpty()) {
            props.put("atomicUsage", atomicTypes);
        }

        // Lock/ReentrantLock API
        if (REENTRANT_LOCK_PATTERN.matcher(methodBody).find() || LOCK_API_PATTERN.matcher(methodBody).find()) {
            props.put("lockApi", true);
        }

        // CompletableFuture
        if (COMPLETABLE_FUTURE_PATTERN.matcher(methodBody).find()) {
            props.put("completableFuture", true);
        }

        // Thread/Runnable/Executor
        if (THREAD_PATTERN.matcher(methodBody).find()) {
            props.put("threadUsage", true);
        }

        // 同步工具类
        if (SYNC_UTILITY_PATTERN.matcher(methodBody).find()) {
            props.put("syncUtility", true);
        }

        return props;
    }

    /**
     * S2-T4: 合并注解级和方法体级并发属性。
     *
     * @param methodInfo 方法信息
     * @param methodBody 方法体文本
     * @return 合并后的并发属性 Map
     */
    public static Map<String, Object> extractAll(JavaMethodInfo methodInfo, String methodBody) {
        Map<String, Object> props = extract(methodInfo);
        props.putAll(extractFromBody(methodBody));
        return props;
    }

    /**
     * S2-T4: 判断方法是否包含并发属性（用于决定是否创建 Concurrency 节点）。
     */
    public static boolean hasConcurrencyProperties(Map<String, Object> props) {
        return props != null && !props.isEmpty();
    }

    /** 去掉注解的包名前缀和参数，只保留简单名 */
    private static String normalizeAnnotationName(String annotation) {
        int parenIdx = annotation.indexOf('(');
        String name = parenIdx > 0 ? annotation.substring(0, parenIdx) : annotation;
        int dotIdx = name.lastIndexOf('.');
        return dotIdx > 0 ? name.substring(dotIdx + 1) : name;
    }

    /** 从 @Lock(READ) 或 @Lock(LockType.READ) 中提取锁类型 */
    private static String extractLockType(String annotation) {
        int parenIdx = annotation.indexOf('(');
        if (parenIdx < 0) return null;
        String args = annotation.substring(parenIdx + 1);
        int closeIdx = args.lastIndexOf(')');
        if (closeIdx > 0) {
            args = args.substring(0, closeIdx);
        }
        args = args.trim();
        int dotIdx = args.lastIndexOf('.');
        if (dotIdx >= 0) {
            args = args.substring(dotIdx + 1);
        }
        return args.isEmpty() ? null : args.toUpperCase();
    }
}
