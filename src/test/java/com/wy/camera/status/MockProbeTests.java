package com.wy.camera.status;

import com.wy.camera.status.mock.MockLiveGbsGateway;
import com.wy.camera.status.model.CameraSnapshot;
import com.wy.camera.status.model.CameraStatus;
import com.wy.camera.status.model.ProbeReport;
import com.wy.camera.status.model.ProbeStatus;
import com.wy.camera.status.service.CameraStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 mock 网关跑完整链路：登录 → 拉通道 → 并发取流 → 关流 → 聚合。
 *
 * <p>17 路通道的设定：每 7 路有 1 路平台侧离线（0/7/14），
 * 第 11 路平台说在线但拉不到流——用来验证"假在线"能被识别出来。</p>
 *
 * @author baijin-chao
 */
@SpringBootTest(properties = {
        "camera-status.cron=-",
        "camera-status.thread-count=4",
        "camera-status.max-concurrency=4",
        "camera-status.batch-timeout=30s",
        "camera-status.mock.channels=17",
        "camera-status.mock.latency-ms=1",
        "camera-status.mock.fail-every=0",
        "camera-status.mock.false-online-every=11"
})
@ActiveProfiles("mock")
class MockProbeTests {

    @Autowired
    private CameraStatusService cameraStatusService;

    @Autowired
    private MockLiveGbsGateway mockGateway;

    @Test
    void shouldProbeEveryChannelAndExposeFalseOnline() {
        int stopBefore = mockGateway.getStopCount();

        ProbeReport report = cameraStatusService.probeNow();

        assertEquals(ProbeReport.RunStatus.SUCCESS, report.getStatus());
        assertEquals(17, report.getTotal());
        assertEquals(13, report.getOnline());
        assertEquals(4, report.getOffline());
        assertEquals(0, report.getError());
        assertEquals(14, report.getPlatformOnline());
        assertEquals(3, report.getPlatformOffline());

        // 每一路探测都必须配对一次关流，否则平台的出流通道会被占满
        assertEquals(17, mockGateway.getStopCount() - stopBefore);

        CameraSnapshot snapshot = cameraStatusService.snapshot();
        assertEquals(17, snapshot.getTotal());
        assertEquals(1, snapshot.getFalseOnline(), "应识别出 1 路假在线");

        CameraStatus falseOnline = snapshot.getCameras().values().stream()
                .filter(CameraStatus::isFalseOnline)
                .findFirst()
                .orElse(null);
        assertNotNull(falseOnline);
        assertEquals("ON", falseOnline.getPlatformStatus());
        assertEquals(ProbeStatus.OFFLINE, falseOnline.getProbeStatus());
        assertNotNull(falseOnline.getErrorMessage());
        assertTrue(falseOnline.getProbeCostMs() >= 0);

        // 单路查询
        String key = snapshot.getCameras().keySet().iterator().next();
        String[] parts = key.split("_");
        assertEquals(2, parts.length);
        assertTrue(cameraStatusService.find(parts[0], parts[1]).isPresent());
        assertFalse(cameraStatusService.find("not-exist", "not-exist").isPresent());

        // 连通性与清空
        assertTrue(cameraStatusService.ping());
        cameraStatusService.clear();
        assertEquals(0, cameraStatusService.snapshot().getTotal());
    }
}
