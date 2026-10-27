package com.wy.camera.status.service.impl;

import com.wy.camera.status.client.LiveChannel;
import com.wy.camera.status.client.LiveGbsException;
import com.wy.camera.status.client.LiveGbsGateway;
import com.wy.camera.status.client.StreamStartResult;
import com.wy.camera.status.config.CameraStatusProperties;
import com.wy.camera.status.model.CameraSnapshot;
import com.wy.camera.status.model.CameraStatus;
import com.wy.camera.status.model.ProbeReport;
import com.wy.camera.status.model.ProbeStatus;
import com.wy.camera.status.service.CameraStatusService;
import com.wy.camera.status.service.TokenProvider;
import com.wy.commons.concurrent.BatchTaskTemplate;
import com.wy.commons.concurrent.BatchTaskTemplate.BatchReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 探测服务实现。
 *
 * <p>一轮探测 = 登录 → 拉通道 → 并发逐路真实取流 → 立即关流 → 整体替换快照。</p>
 *
 * <p>并发交给 {@link BatchTaskTemplate}，本类只负责：任务生命周期（防重入）、
 * 单路失败的语义归类、快照发布与统计。这四件事本来就不该和线程编排写在一起。</p>
 *
 * @author baijin-chao
 */
@Slf4j
@Service
public class StatusServiceImpl implements CameraStatusService {

    private static final String KEY_SEPARATOR = "_";

    private final LiveGbsGateway gateway;

    private final TokenProvider tokenProvider;

    private final BatchTaskTemplate<LiveChannel, CameraStatus> template;

    /** 异步触发的编排线程：只用来跑一轮探测，不参与逐路任务 */
    private final ExecutorService orchestrator;

    /** 同一时刻只允许一轮探测 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 快照：整体替换，读方永远看到完整数据 */
    private final AtomicReference<Map<String, CameraStatus>> snapshot =
            new AtomicReference<>(Collections.emptyMap());

    /** 最近一轮报告 */
    private final AtomicReference<ProbeReport> lastReport = new AtomicReference<>();

    public StatusServiceImpl(LiveGbsGateway gateway,
                             TokenProvider tokenProvider,
                             CameraStatusProperties properties,
                             ThreadPoolExecutor probeExecutor) {
        this.gateway = gateway;
        this.tokenProvider = tokenProvider;
        this.template = new BatchTaskTemplate<>(
                "camera-probe",
                probeExecutor,
                properties.getMaxConcurrency(),
                properties.getPermitWait().toMillis(),
                properties.getBatchTimeout().toMillis());
        this.orchestrator = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "camera-probe-orchestrator");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 定时探测。cron 置为 "-" 即可关闭（Spring 原生支持）。
     */
    @Scheduled(cron = "${camera-status.cron}", zone = "${camera-status.zone}")
    public void scheduledProbe() {
        log.info("定时探测触发");
        ProbeReport report = probeNow();
        log.info("定时探测结束：{}", report);
    }

    @Override
    public ProbeReport probeNow() {
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮探测尚未结束，本次触发被忽略");
            return busyReport();
        }
        try {
            return doProbe();
        } finally {
            running.set(false);
        }
    }

    @Override
    public boolean triggerAsync() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            orchestrator.execute(() -> {
                try {
                    doProbe();
                } catch (Throwable ex) {
                    log.error("异步探测出现未捕获异常", ex);
                } finally {
                    running.set(false);
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            running.set(false);
            log.error("异步探测任务提交失败", ex);
            return false;
        }
    }

    @Override
    public CameraSnapshot snapshot() {
        Map<String, CameraStatus> current = snapshot.get();
        int online = 0;
        int offline = 0;
        int error = 0;
        int falseOnline = 0;
        Instant updatedAt = null;
        for (CameraStatus status : current.values()) {
            if (status.getProbeStatus() == ProbeStatus.ONLINE) {
                online++;
            } else if (status.getProbeStatus() == ProbeStatus.OFFLINE) {
                offline++;
            } else {
                error++;
            }
            if (status.isFalseOnline()) {
                falseOnline++;
            }
            if (status.getSyncedAt() != null && (updatedAt == null || status.getSyncedAt().isAfter(updatedAt))) {
                updatedAt = status.getSyncedAt();
            }
        }
        return CameraSnapshot.builder()
                .total(current.size())
                .online(online)
                .offline(offline)
                .error(error)
                .falseOnline(falseOnline)
                .updatedAt(updatedAt)
                .cameras(current)
                .build();
    }

    @Override
    public Optional<ProbeReport> lastReport() {
        return Optional.ofNullable(lastReport.get());
    }

    @Override
    public Optional<CameraStatus> find(String deviceId, String channelId) {
        if (deviceId == null || channelId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get().get(deviceId + KEY_SEPARATOR + channelId));
    }

    @Override
    public void clear() {
        snapshot.set(Collections.emptyMap());
        log.warn("快照已清空");
    }

    @Override
    public boolean ping() {
        try {
            // 健康检查要真的打一次平台，不能用缓存 token 糊弄过去
            tokenProvider.getToken(true);
            return true;
        } catch (Exception ex) {
            log.warn("LiveGBS 连通性检查失败：{}", ex.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        orchestrator.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private ProbeReport doProbe() {
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant startedAt = Instant.now();
        log.info("[{}] 探测开始", runId);
        try {
            String token = tokenProvider.getToken();
            List<LiveChannel> channels = gateway.listChannels(token);
            if (channels.isEmpty()) {
                snapshot.set(Collections.emptyMap());
                ProbeReport report = buildReport(runId, startedAt, 0, 0, 0, 0, 0, 0,
                        ProbeReport.RunStatus.EMPTY, "平台未返回任何通道");
                lastReport.set(report);
                log.warn("[{}] 平台未返回任何通道", runId);
                return report;
            }

            BatchReport<LiveChannel, CameraStatus> batch = template.run(channels, LiveChannel::key, this::probeOne);
            if (batch.isRejected()) {
                // 模板的兜底防重入；此时绝不能覆盖旧快照
                log.warn("[{}] 批次被判定为重复执行，保留上一轮快照", runId);
                return busyReport();
            }

            Map<String, CameraStatus> latest = Collections.unmodifiableMap(batch.toValueMap());
            snapshot.set(latest);

            ProbeReport report = summarize(runId, startedAt, batch, latest.values());
            lastReport.set(report);
            log.info("[{}] 探测结束：总数 {}，取流在线 {}，取流离线 {}，探测异常 {}，耗时 {} ms",
                    runId, report.getTotal(), report.getOnline(), report.getOffline(),
                    report.getError(), report.getDurationMs());
            return report;
        } catch (Exception ex) {
            // 登录或通道列表失败：保留上一轮快照，并明确标记失败
            log.error("[{}] 探测失败，保留上一轮快照", runId, ex);
            ProbeReport report = buildReport(runId, startedAt, 0, 0, 0, 0, 0, 0,
                    ProbeReport.RunStatus.FAILED, rootMessage(ex));
            lastReport.set(report);
            return report;
        }
    }

    /**
     * 探测单路通道。这里不抛异常：任何失败都要变成一个带原因的结论，
     * 因为"某一路探测失败"本身就是业务数据，而不是需要中断整批的错误。
     */
    private CameraStatus probeOne(LiveChannel channel) {
        long begin = System.currentTimeMillis();
        CameraStatus.CameraStatusBuilder builder = CameraStatus.builder()
                .deviceId(channel.getDeviceId())
                .channelId(channel.getChannelId())
                .name(channel.getName())
                .platformStatus(channel.getPlatformStatus());

        String token = null;
        try {
            token = tokenProvider.getToken();
            StreamStartResult result;
            try {
                result = gateway.startStream(token, channel);
            } catch (LiveGbsException ex) {
                if (!ex.isAuthError()) {
                    throw ex;
                }
                // token 中途失效：刷新后重试一次，而不是把"鉴权过期"记成"摄像头离线"
                log.info("token 失效，刷新后重试：{}", channel.key());
                token = tokenProvider.getToken(true);
                result = gateway.startStream(token, channel);
            }

            if (result != null && result.isStarted()) {
                builder.probeStatus(ProbeStatus.ONLINE);
            } else {
                builder.probeStatus(ProbeStatus.OFFLINE)
                        .httpCode(200)
                        .errorMessage(result == null ? "取流接口无响应" : result.getErrorMessage());
            }
        } catch (LiveGbsException ex) {
            builder.probeStatus(ex.isPlatformError() ? ProbeStatus.ERROR : ProbeStatus.OFFLINE)
                    .httpCode(ex.getHttpStatus())
                    .errorMessage(ex.getMessage());
        } catch (Exception ex) {
            builder.probeStatus(ProbeStatus.ERROR)
                    .errorMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            // 无论成败都要关流：探测不能把平台的出流通道占着
            if (token != null) {
                gateway.stopStream(token, channel);
            }
            builder.probeCostMs(System.currentTimeMillis() - begin).syncedAt(Instant.now());
        }
        return builder.build();
    }

    private ProbeReport summarize(String runId,
                                  Instant startedAt,
                                  BatchReport<LiveChannel, CameraStatus> batch,
                                  Collection<CameraStatus> statuses) {
        int online = 0;
        int offline = 0;
        int error = 0;
        int platformOnline = 0;
        int platformOffline = 0;
        for (CameraStatus status : statuses) {
            if (status.getProbeStatus() == ProbeStatus.ONLINE) {
                online++;
            } else if (status.getProbeStatus() == ProbeStatus.OFFLINE) {
                offline++;
            } else {
                error++;
            }
            if ("ON".equalsIgnoreCase(status.getPlatformStatus())) {
                platformOnline++;
            } else {
                platformOffline++;
            }
        }
        ProbeReport.RunStatus runStatus = batch.getFailureCount() > 0
                ? ProbeReport.RunStatus.PARTIAL
                : ProbeReport.RunStatus.SUCCESS;
        String message = batch.getFailureCount() > 0
                ? batch.getFailureCount() + " 路任务异常，详见日志"
                : "OK";
        return buildReport(runId, startedAt, batch.getTotal(), online, offline, error,
                platformOnline, platformOffline, runStatus, message);
    }

    private ProbeReport buildReport(String runId, Instant startedAt, int total, int online, int offline,
                                    int error, int platformOnline, int platformOffline,
                                    ProbeReport.RunStatus status, String message) {
        Instant finishedAt = Instant.now();
        return ProbeReport.builder()
                .runId(runId)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .total(total)
                .online(online)
                .offline(offline)
                .error(error)
                .platformOnline(platformOnline)
                .platformOffline(platformOffline)
                .status(status)
                .message(message)
                .build();
    }

    private ProbeReport busyReport() {
        ProbeReport previous = lastReport.get();
        Instant now = Instant.now();
        return ProbeReport.builder()
                .runId(previous == null ? "-" : previous.getRunId())
                .startedAt(now)
                .finishedAt(now)
                .status(ProbeReport.RunStatus.FAILED)
                .message("上一轮探测尚未结束，本次触发被忽略")
                .build();
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
