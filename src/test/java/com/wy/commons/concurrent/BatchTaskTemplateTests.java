package com.wy.commons.concurrent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板的行为约束。这些用例本身就是文档：
 * 它们规定了"异常必须隔离""重入必须拒绝""并发必须有上限""超时必须降级"。
 *
 * @author baijin-chao
 */
class BatchTaskTemplateTests {

    @Test
    void shouldRunAllTasksAndCollectResults() {
        ThreadPoolExecutor pool = BatchTaskTemplate.newFixedPool("tpl-all", 4, 64);
        try {
            BatchTaskTemplate<Integer, Integer> template =
                    new BatchTaskTemplate<>("all", pool, 4, 1000L, 5000L);

            BatchTaskTemplate.BatchReport<Integer, Integer> report =
                    template.run(range(100), String::valueOf, value -> value * 2);

            assertEquals(100, report.getTotal());
            assertEquals(100, report.getSuccessCount());
            assertEquals(0, report.getFailureCount());
            assertFalse(report.isRejected());
            // range(100) 的键是 "0".."99"，任务做 value*2
            assertEquals(198, report.toValueMap().get("99").intValue());
            assertTrue(report.getCostMillis() >= 0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldIsolateFailingTaskWithoutLosingOthers() {
        ThreadPoolExecutor pool = BatchTaskTemplate.newFixedPool("tpl-fail", 4, 64);
        try {
            BatchTaskTemplate<Integer, Integer> template =
                    new BatchTaskTemplate<>("fail", pool, 4, 1000L, 5000L);

            BatchTaskTemplate.BatchReport<Integer, Integer> report =
                    template.run(range(10), String::valueOf, value -> {
                        if (value == 5) {
                            throw new IllegalStateException("boom");
                        }
                        return value;
                    });

            assertEquals(10, report.getTotal());
            assertEquals(9, report.getSuccessCount());
            assertEquals(1, report.getFailureCount());
            assertEquals(9, report.toValueMap().size());
            assertTrue(report.toFailureMap().containsKey("5"));
            assertNotNull(report.toFailureMap().get("5"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldRejectReentrantRun() throws Exception {
        ThreadPoolExecutor pool = BatchTaskTemplate.newFixedPool("tpl-reentrant", 2, 16);
        try {
            BatchTaskTemplate<Integer, Integer> template =
                    new BatchTaskTemplate<>("reentrant", pool, 1, 2000L, 5000L);

            CountDownLatch started = new CountDownLatch(1);
            Thread first = new Thread(() -> template.run(Collections.singletonList(1), String::valueOf, value -> {
                started.countDown();
                sleep(1000L);
                return value;
            }));
            first.start();

            assertTrue(started.await(2, TimeUnit.SECONDS), "第一轮应已开始");
            BatchTaskTemplate.BatchReport<Integer, Integer> second =
                    template.run(Collections.singletonList(2), String::valueOf, value -> value);

            assertTrue(second.isRejected(), "上一轮未结束时必须拒绝重入");

            first.join(5000L);
            assertFalse(first.isAlive());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldRespectConcurrencyLimit() {
        ThreadPoolExecutor pool = BatchTaskTemplate.newFixedPool("tpl-limit", 8, 128);
        try {
            BatchTaskTemplate<Integer, Integer> template =
                    new BatchTaskTemplate<>("limit", pool, 3, 2000L, 5000L);

            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger max = new AtomicInteger();

            BatchTaskTemplate.BatchReport<Integer, Integer> report =
                    template.run(range(30), String::valueOf, value -> {
                        int current = concurrent.incrementAndGet();
                        max.accumulateAndGet(current, Math::max);
                        sleep(10L);
                        concurrent.decrementAndGet();
                        return value;
                    });

            assertEquals(30, report.getSuccessCount());
            assertTrue(max.get() <= 3, "并发数应被限制在 3，实际 " + max.get());
            assertTrue(max.get() >= 2, "并发数应能到 2 以上，实际 " + max.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldReturnPartialResultOnBatchTimeout() {
        ThreadPoolExecutor pool = BatchTaskTemplate.newFixedPool("tpl-timeout", 2, 16);
        try {
            BatchTaskTemplate<Integer, Integer> template =
                    new BatchTaskTemplate<>("timeout", pool, 2, 1000L, 200L);

            long begin = System.currentTimeMillis();
            BatchTaskTemplate.BatchReport<Integer, Integer> report =
                    template.run(range(8), String::valueOf, value -> {
                        sleep(2000L);
                        return value;
                    });
            long cost = System.currentTimeMillis() - begin;

            assertTrue(cost < 1500L, "不应等任务跑完，实际耗时 " + cost);
            assertTrue(report.getOutcomes().size() < 8, "超时只应返回已完成部分");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldHandleEmptyInput() {
        ThreadPoolExecutor pool = BatchTaskTemplate.newFixedPool("tpl-empty", 1, 4);
        try {
            BatchTaskTemplate<Integer, Integer> template =
                    new BatchTaskTemplate<>("empty", pool, 1, 1000L, 1000L);
            BatchTaskTemplate.BatchReport<Integer, Integer> report =
                    template.run(Collections.<Integer>emptyList(), String::valueOf, value -> value);

            assertEquals(0, report.getTotal());
            assertEquals(0, report.getSuccessCount());
            assertFalse(report.isRejected());
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> range(int size) {
        List<Integer> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
        return list;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
