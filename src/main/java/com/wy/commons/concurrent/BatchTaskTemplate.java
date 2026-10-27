package com.wy.commons.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 批量任务并发执行模板（扇出 - 汇聚 / fan-out &amp; fan-in）。
 *
 * <p>适用：N 个互相独立、可并行、无强顺序依赖、可重复执行的任务，需要
 * ① 跑得快 ② 别把下游打挂 ③ 单点失败不影响整批 ④ 拿到整批统计。</p>
 *
 * <p>三条正确性不变量，也是这个类存在的全部理由：</p>
 * <ol>
 *   <li><b>提交与放行严格配对</b>：每次提交（含提交失败）必然对应一次
 *       {@code latch.countDown()}，唯一出口在 {@code finally}，
 *       因此任何异常路径下 {@code await} 都不会永久阻塞。</li>
 *   <li><b>许可获取与释放严格配对</b>：{@code acquired} 标志确保只释放真正拿到的许可，
 *       避免信号量泄漏导致后续任务全部饿死。</li>
 *   <li><b>每一层都有上界</b>：{@code tryAcquire} 带超时、{@code await} 带超时、
 *       线程池用有界队列，任何一层卡住都降级为"部分失败"，而不是"进程挂死"。</li>
 * </ol>
 *
 * <p>线程池由调用方持有（通常注册为 Spring 单例），本类<b>不负责</b>关闭它。</p>
 *
 * @param <T> 输入任务类型
 * @param <R> 单任务返回类型
 * @author baijin-chao
 */
public final class BatchTaskTemplate<T, R> {

    private static final Logger log = LoggerFactory.getLogger(BatchTaskTemplate.class);

    /** 业务线程池，进程内复用 */
    private final ExecutorService pool;

    /** 下游并发许可：控制同时打到下游的请求数，与线程数解耦 */
    private final Semaphore permits;

    /** 等待并发许可的最长时间，超时则该任务记为失败 */
    private final long permitWaitMillis;

    /** 整批任务的最长等待时间，超时则记录告警并返回已完成部分 */
    private final long batchTimeoutMillis;

    /** 任务名，仅用于日志定位 */
    private final String taskName;

    /** 防重入开关：CAS 天然线程安全 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * @param taskName           任务名，出现在日志里
     * @param pool               复用的业务线程池
     * @param maxConcurrency     允许同时打到下游的最大并发数（大于等于 1）
     * @param permitWaitMillis   单任务等待并发许可的超时时间
     * @param batchTimeoutMillis 整批等待的超时时间
     */
    public BatchTaskTemplate(String taskName,
                             ExecutorService pool,
                             int maxConcurrency,
                             long permitWaitMillis,
                             long batchTimeoutMillis) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
        if (permitWaitMillis <= 0 || batchTimeoutMillis <= 0) {
            throw new IllegalArgumentException("timeout must be > 0");
        }
        this.taskName = Objects.requireNonNull(taskName, "taskName");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.permits = new Semaphore(maxConcurrency);
        this.permitWaitMillis = permitWaitMillis;
        this.batchTimeoutMillis = batchTimeoutMillis;
    }

    /** 不带业务键的版本，键退化为 item.toString() */
    public BatchReport<T, R> run(Collection<T> items, Function<T, R> task) {
        return run(items, null, task);
    }

    /**
     * 执行一批任务，阻塞直到整批结束或超时。同一实例不允许并发执行，重入直接跳过。
     *
     * @param items 任务列表，允许为空
     * @param keyFn 由任务生成业务唯一键（用于结果对账），可为 null
     * @param task  单任务逻辑，允许抛出任何异常（会被隔离为该任务失败）
     * @return 批次报告，永不为 null
     */
    public BatchReport<T, R> run(Collection<T> items,
                                 Function<T, String> keyFn,
                                 Function<T, R> task) {
        if (items == null || items.isEmpty()) {
            return BatchReport.empty(taskName);
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("[{}] 上一轮尚未结束，本轮跳过（禁止重入）", taskName);
            return BatchReport.rejected(taskName, items.size());
        }

        final long start = System.currentTimeMillis();
        final int total = items.size();
        final AtomicInteger okCount = new AtomicInteger();
        final AtomicInteger failCount = new AtomicInteger();
        final Queue<TaskOutcome<T, R>> outcomes = new ConcurrentLinkedQueue<>();
        final CountDownLatch latch = new CountDownLatch(total);

        try {
            log.info("[{}] 批次开始，任务总数 {}，可用并发许可 {}", taskName, total, permits.availablePermits());

            for (T item : items) {
                final String key = safeKey(keyFn, item);
                final T current = item;
                try {
                    pool.execute(() -> executeOne(key, current, task, latch, outcomes, okCount, failCount));
                } catch (RejectedExecutionException ex) {
                    // 队列满或池已关闭：提交失败也算一个任务，必须放行闭锁并计入失败
                    failCount.incrementAndGet();
                    outcomes.add(TaskOutcome.<T, R>failed(key, current, "任务提交被拒绝：" + ex.getMessage()));
                    latch.countDown();
                    log.error("[{}] 任务提交被拒绝，key={}", taskName, key, ex);
                }
            }

            if (!latch.await(batchTimeoutMillis, TimeUnit.MILLISECONDS)) {
                log.error("[{}] 批次等待超时（{} ms），仍有 {} 个任务未结束，返回已完成部分",
                        taskName, batchTimeoutMillis, latch.getCount());
            }

            long cost = System.currentTimeMillis() - start;
            log.info("[{}] 批次结束，总数 {}，成功 {}，失败 {}，耗时 {} ms",
                    taskName, total, okCount.get(), failCount.get(), cost);
            return new BatchReport<>(taskName, total, okCount.get(), failCount.get(), cost, false, outcomes);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("[{}] 等待批次结束时被中断，返回已完成部分", taskName, ex);
            return new BatchReport<>(taskName, total, okCount.get(), failCount.get(),
                    System.currentTimeMillis() - start, false, outcomes);
        } finally {
            // 无论成功、异常、中断都必须复位，否则后续所有轮次会被永久拒绝
            running.set(false);
        }
    }

    /** 单个工作线程的执行体，是三条不变量的落地处 */
    private void executeOne(String key, T item, Function<T, R> task,
                            CountDownLatch latch,
                            Queue<TaskOutcome<T, R>> outcomes,
                            AtomicInteger okCount, AtomicInteger failCount) {
        boolean acquired = false;
        final long begin = System.currentTimeMillis();
        try {
            // 带超时获取许可：拿不到就快速失败，绝不无限排队
            acquired = permits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                failCount.incrementAndGet();
                outcomes.add(TaskOutcome.<T, R>failed(key, item, "等待并发许可超时（" + permitWaitMillis + " ms）"));
                return;
            }
            R value = task.apply(item);
            okCount.incrementAndGet();
            outcomes.add(TaskOutcome.success(key, item, value, System.currentTimeMillis() - begin));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failCount.incrementAndGet();
            outcomes.add(TaskOutcome.<T, R>failed(key, item, ex));
        } catch (Throwable ex) {
            // 单任务异常隔离：异常一旦逃逸出线程，本批的统计与闭锁都会失真
            failCount.incrementAndGet();
            outcomes.add(TaskOutcome.<T, R>failed(key, item, ex));
            log.error("[{}] 任务执行异常，key={}", taskName, key, ex);
        } finally {
            if (acquired) {
                permits.release();
            }
            // 唯一出口：每次提交在这里恰好放行一次
            latch.countDown();
        }
    }

    /** 业务键生成失败不应拖垮整批，退化为 toString() */
    private static <T> String safeKey(Function<T, String> keyFn, T item) {
        if (keyFn == null) {
            return String.valueOf(item);
        }
        try {
            String key = keyFn.apply(item);
            return key == null ? String.valueOf(item) : key;
        } catch (Exception ex) {
            return String.valueOf(item);
        }
    }

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /** 单任务结果 */
    public static final class TaskOutcome<T, R> {

        private final String key;
        private final T item;
        private final R value;
        private final boolean success;
        private final String errorMessage;
        private final long costMillis;

        private TaskOutcome(String key, T item, R value, boolean success, String errorMessage, long costMillis) {
            this.key = key;
            this.item = item;
            this.value = value;
            this.success = success;
            this.errorMessage = errorMessage;
            this.costMillis = costMillis;
        }

        public static <T, R> TaskOutcome<T, R> success(String key, T item, R value, long costMillis) {
            return new TaskOutcome<>(key, item, value, true, null, costMillis);
        }

        public static <T, R> TaskOutcome<T, R> failed(String key, T item, Throwable ex) {
            String msg = ex == null ? "unknown" : ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return new TaskOutcome<>(key, item, null, false, msg, 0L);
        }

        public static <T, R> TaskOutcome<T, R> failed(String key, T item, String errorMessage) {
            return new TaskOutcome<>(key, item, null, false, errorMessage, 0L);
        }

        public String getKey() {
            return key;
        }

        public T getItem() {
            return item;
        }

        public R getValue() {
            return value;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public long getCostMillis() {
            return costMillis;
        }

        @Override
        public String toString() {
            return "TaskOutcome{key='" + key + "', success=" + success
                    + ", cost=" + costMillis + "ms"
                    + (success ? "" : ", error='" + errorMessage + "'") + '}';
        }
    }

    /** 批次报告 */
    public static final class BatchReport<T, R> {

        private final String taskName;
        private final int total;
        private final int successCount;
        private final int failureCount;
        private final long costMillis;
        private final boolean rejected;
        private final Collection<TaskOutcome<T, R>> outcomes;

        private BatchReport(String taskName, int total, int successCount, int failureCount,
                            long costMillis, boolean rejected, Collection<TaskOutcome<T, R>> outcomes) {
            this.taskName = taskName;
            this.total = total;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.costMillis = costMillis;
            this.rejected = rejected;
            this.outcomes = outcomes == null ? Collections.emptyList() : outcomes;
        }

        static <T, R> BatchReport<T, R> empty(String taskName) {
            return new BatchReport<>(taskName, 0, 0, 0, 0L, false, Collections.emptyList());
        }

        static <T, R> BatchReport<T, R> rejected(String taskName, int total) {
            return new BatchReport<>(taskName, total, 0, 0, 0L, true, Collections.emptyList());
        }

        public String getTaskName() {
            return taskName;
        }

        public int getTotal() {
            return total;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public long getCostMillis() {
            return costMillis;
        }

        /** 本轮是否因重入被跳过 */
        public boolean isRejected() {
            return rejected;
        }

        public Collection<TaskOutcome<T, R>> getOutcomes() {
            return outcomes;
        }

        /** 仅成功任务：key -> value，可直接整体替换缓存快照 */
        public Map<String, R> toValueMap() {
            Map<String, R> map = new LinkedHashMap<>(Math.max(16, successCount * 2));
            for (TaskOutcome<T, R> outcome : outcomes) {
                if (outcome.isSuccess()) {
                    map.put(outcome.getKey(), outcome.getValue());
                }
            }
            return map;
        }

        /** 全量任务：key -> outcome（含失败原因） */
        public Map<String, TaskOutcome<T, R>> toOutcomeMap() {
            Map<String, TaskOutcome<T, R>> map = new LinkedHashMap<>(Math.max(16, outcomes.size() * 2));
            for (TaskOutcome<T, R> outcome : outcomes) {
                map.put(outcome.getKey(), outcome);
            }
            return map;
        }

        /** 仅失败任务：key -> 错误信息，便于一次性排查 */
        public Map<String, String> toFailureMap() {
            Map<String, String> map = new LinkedHashMap<>();
            for (TaskOutcome<T, R> outcome : outcomes) {
                if (!outcome.isSuccess()) {
                    map.put(outcome.getKey(), outcome.getErrorMessage());
                }
            }
            return map;
        }

        @Override
        public String toString() {
            return "BatchReport{task='" + taskName + "', total=" + total
                    + ", success=" + successCount + ", failure=" + failureCount
                    + ", cost=" + costMillis + "ms" + (rejected ? ", rejected=true" : "") + '}';
        }
    }

    // ------------------------------------------------------------------
    // 线程池工厂
    // ------------------------------------------------------------------

    /**
     * 创建推荐的业务线程池：固定大小 + 有界队列 + 命名线程。
     *
     * <p>为什么必须有界：无界队列会让"下游变慢"表现为"内存上涨 + 永不拒绝"，
     * 最后以 OOM 收场；有界队列让压力在提交侧暴露。</p>
     *
     * <p>拒绝策略用 {@link BackpressurePolicy} 而不是 AbortPolicy：
     * 队列满时由提交线程自己执行，形成背压，<b>不会静默丢掉批量任务</b>。
     * 实测中 AbortPolicy 会让"批量数 &gt; 线程数 + 队列容量"的那部分任务直接消失，
     * 只在报告里留下几条失败记录，很容易被当成"下游偶发失败"忽略掉。</p>
     *
     * @param namePrefix    线程名前缀
     * @param threads       线程数
     * @param queueCapacity 队列容量（背压点）
     */
    public static ThreadPoolExecutor newFixedPool(String namePrefix, int threads, int queueCapacity) {
        Objects.requireNonNull(namePrefix, "namePrefix");
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("threads and queueCapacity must be >= 1");
        }
        final AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                factory,
                new BackpressurePolicy());
    }

    /**
     * 背压式拒绝策略：池还在跑就让提交线程自己执行，池已关闭才明确拒绝。
     *
     * <p>{@link ThreadPoolExecutor.CallerRunsPolicy} 在池关闭时会<b>静默丢弃</b>任务，
     * 而本模板依赖"每次提交必然 countDown"这条不变量，
     * 一旦丢弃，闭锁永远归不了零，整批要白等到超时。所以这里必须显式抛异常。</p>
     */
    private static final class BackpressurePolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("线程池已关闭，任务未执行");
            }
            // 提交线程替池分担一个任务，天然限速
            task.run();
        }
    }

    /** 优雅停机 */
    public static void shutdownGracefully(ExecutorService pool, long timeoutMillis) {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                log.warn("线程池未在 {} ms 内结束，强制关闭", timeoutMillis);
                pool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
