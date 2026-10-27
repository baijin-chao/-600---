package com.wy.camera.status.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 探测任务配置。
 *
 * <p>把「线程数」和「下游并发数」拆成两个独立参数是有意的：
 * 线程数决定本机并行能力，并发数决定同时打到 LiveGBS 的请求量。
 * 两者相等时，信号量看起来多余，但一旦有人只调大线程数，
 * 并发闸门就会立刻变成保护下游的生效条件。</p>
 *
 * @author baijin-chao
 */
@Data
@ConfigurationProperties(prefix = "camera-status")
public class CameraStatusProperties {

    /** 定时表达式，"-" 表示关闭定时任务 */
    private String cron = "0 0/10 * * * ?";

    /** 定时任务时区 */
    private String zone = "Asia/Shanghai";

    /** 工作线程数 */
    private int threadCount = 64;

    /** 同时打到下游的最大并发数（舱壁） */
    private int maxConcurrency = 64;

    /** 有界队列容量 */
    private int queueCapacity = 4096;

    /** 等待并发许可的超时 */
    private Duration permitWait = Duration.ofSeconds(5);

    /** 整批任务的最长等待时间 */
    private Duration batchTimeout = Duration.ofMinutes(10);

    /** 写操作鉴权 token，留空表示不校验 */
    private String adminToken = "";

    public void validate() {
        if (threadCount < 1 || threadCount > 1024) {
            throw new IllegalStateException("camera-status.thread-count 必须在 1~1024 之间");
        }
        if (maxConcurrency < 1) {
            throw new IllegalStateException("camera-status.max-concurrency 必须大于 0");
        }
        if (queueCapacity < 1) {
            throw new IllegalStateException("camera-status.queue-capacity 必须大于 0");
        }
        if (permitWait == null || permitWait.isNegative() || permitWait.isZero()) {
            throw new IllegalStateException("camera-status.permit-wait 必须大于 0");
        }
        if (batchTimeout == null || batchTimeout.isNegative() || batchTimeout.isZero()) {
            throw new IllegalStateException("camera-status.batch-timeout 必须大于 0");
        }
        if (cron == null || cron.trim().isEmpty()) {
            cron = "-";
        }
    }
}
