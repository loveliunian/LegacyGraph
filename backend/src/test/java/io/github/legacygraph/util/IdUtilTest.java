package io.github.legacygraph.util;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IdUtil} 单元测试 — 验证 fastUUID 的格式（version/variant）、唯一性（串行+并发），
 * 以及 normalizeId 的归一化逻辑。
 */
class IdUtilTest {

    // ==================== fastUUID 格式 ====================

    @Test
    void fastUUID_长度为32() {
        assertEquals(32, IdUtil.fastUUID().length());
    }

    @Test
    void fastUUID_只含十六进制字符() {
        String uuid = IdUtil.fastUUID();

        assertTrue(uuid.matches("^[0-9a-f]{32}$"), "UUID 应只含 0-9a-f: " + uuid);
    }

    @RepeatedTest(20)
    void fastUUID_version为4() {
        String uuid = IdUtil.fastUUID();

        // 第 13 位（index 12）应为 '4'，对应 UUID version=4
        assertEquals('4', uuid.charAt(12), "第13位应为 '4' (version=4): " + uuid);
    }

    @RepeatedTest(20)
    void fastUUID_variant为2() {
        String uuid = IdUtil.fastUUID();
        char c = uuid.charAt(16);

        // 第 17 位（index 16）应为 8/9/a/b，对应 RFC 4122 variant=2
        assertTrue(c == '8' || c == '9' || c == 'a' || c == 'b',
                "第17位应为 8/9/a/b (variant=2)，实际为 '" + c + "': " + uuid);
    }

    // ==================== fastUUID 唯一性 ====================

    @Test
    void fastUUID_大量调用不重复() {
        int count = 100_000;
        Set<String> seen = new HashSet<>(count);

        for (int i = 0; i < count; i++) {
            String uuid = IdUtil.fastUUID();
            assertTrue(seen.add(uuid), "第 " + i + " 次 UUID 出现重复: " + uuid);
        }

        assertEquals(count, seen.size());
    }

    @Test
    void fastUUID_并发调用不重复() throws InterruptedException {
        int threads = 10;
        int perThread = 1_000;
        int total = threads * perThread;
        Set<String> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    seen.add(IdUtil.fastUUID());
                    done.countDown();
                }
            });
        }

        // 等所有线程就绪后同时开跑，最大化并发竞争
        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        assertEquals(total, seen.size(), "并发生成的 UUID 出现重复");
    }

    // ==================== normalizeId ====================

    @Test
    void normalizeId_标准UUID去连字符() {
        String input = "550e8400-e29b-41d4-a716-446655440000";

        assertEquals("550e8400e29b41d4a716446655440000", IdUtil.normalizeId(input));
    }

    @Test
    void normalizeId_null返回null() {
        assertNull(IdUtil.normalizeId(null));
    }

    @Test
    void normalizeId_已无连字符保持不变() {
        String input = "550e8400e29b41d4a716446655440000";

        assertEquals(input, IdUtil.normalizeId(input));
    }

    @Test
    void normalizeId_非UUID格式保持不变() {
        String input = "abc123xyz";

        assertEquals(input, IdUtil.normalizeId(input));
    }

    @Test
    void normalizeId_大写UUID匹配并去连字符() {
        String input = "550E8400-E29B-41D4-A716-446655440000";

        assertEquals("550E8400E29B41D4A716446655440000", IdUtil.normalizeId(input));
    }
}
