package com.wy.camera.status.mock;

import com.wy.camera.status.client.LiveChannel;
import com.wy.camera.status.client.LiveGbsException;
import com.wy.camera.status.client.LiveGbsGateway;
import com.wy.camera.status.client.StreamStartResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 演示用网关：不依赖真实 LiveGBS，内存里造 N 路摄像头。
 *
 * <p>目的只有一个——让别人 clone 下来就能看到并发效果，而不必先装一套国标平台。
 * 生产环境不会加载本类（profile 不匹配）。</p>
 *
 * @author baijin-chao
 */
@Slf4j
@Component
@Profile("mock")
public class MockLiveGbsGateway implements LiveGbsGateway {

    private static final String DEVICE_PREFIX = "34020000001320000";

    private static final String CHANNEL_PREFIX = "34020000001310000";

    private final int channelCount;

    private final int latencyMs;

    private final int failEvery;

    /** 每隔 N 路制造一次"平台说在线、实际拉不到流"的假在线；0 表示关闭 */
    private final int falseOnlineEvery;

    private final List<LiveChannel> channels = new ArrayList<>();

    private final Map<String, Integer> indexByKey = new HashMap<>();

    private final AtomicInteger startCount = new AtomicInteger();

    private final AtomicInteger stopCount = new AtomicInteger();

    public MockLiveGbsGateway(@Value("${camera-status.mock.channels:600}") int channelCount,
                              @Value("${camera-status.mock.latency-ms:20}") int latencyMs,
                              @Value("${camera-status.mock.fail-every:0}") int failEvery,
                              @Value("${camera-status.mock.false-online-every:0}") int falseOnlineEvery) {
        this.channelCount = Math.max(1, channelCount);
        this.latencyMs = Math.max(0, latencyMs);
        this.failEvery = Math.max(0, failEvery);
        this.falseOnlineEvery = Math.max(0, falseOnlineEvery);
    }

    @PostConstruct
    void init() {
        for (int i = 0; i < channelCount; i++) {
            String deviceId = DEVICE_PREFIX + String.format("%03d", i);
            String channelId = CHANNEL_PREFIX + String.format("%03d", i);
            // 每 7 路里有 1 路在平台侧就是离线的，用来制造"平台离线"的样本
            String platformStatus = (i % 7 == 0) ? "OFF" : "ON";
            LiveChannel channel = new LiveChannel(deviceId, channelId, "模拟通道-" + i, platformStatus);
            channels.add(channel);
            indexByKey.put(channel.key(), i);
        }
        log.info("Mock 模式已启用：{} 路模拟通道，单次调用模拟耗时 {} ms，failEvery={}",
                channels.size(), latencyMs, failEvery);
    }

    @Override
    public String login() {
        sleep();
        return "mock-token";
    }

    @Override
    public List<LiveChannel> listChannels(String token) {
        sleep();
        return Collections.unmodifiableList(channels);
    }

    @Override
    public StreamStartResult startStream(String token, LiveChannel channel) {
        sleep();
        startCount.incrementAndGet();
        Integer index = indexByKey.get(channel.key());
        int position = index == null ? 0 : index;
        if (failEvery > 0 && position % failEvery == 0) {
            // 模拟上游 500：用来验证"平台故障不会被记成摄像头离线"
            throw new LiveGbsException("模拟平台内部错误", 500, false, null);
        }
        if ("OFF".equalsIgnoreCase(channel.getPlatformStatus())) {
            return new StreamStartResult(null, "stream start error, device offline");
        }
        if (falseOnlineEvery > 0 && position % falseOnlineEvery == 0) {
            // 平台说在线，实际拉不到流：这正是本服务要暴露的"假在线"
            return new StreamStartResult(null,
                    "stream start error, invite failed, res[400] rtp server is busy");
        }
        return new StreamStartResult("mock-stream-" + channel.getChannelId(), null);
    }

    @Override
    public void stopStream(String token, LiveChannel channel) {
        sleep();
        stopCount.incrementAndGet();
    }

    /** 取流次数，测试里用来断言"每路都探测过" */
    public int getStartCount() {
        return startCount.get();
    }

    /** 关流次数，测试里用来断言"失败路径也关了流" */
    public int getStopCount() {
        return stopCount.get();
    }

    private void sleep() {
        if (latencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
