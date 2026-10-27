package com.wy.camera.status.web;

import com.wy.camera.status.model.CameraSnapshot;
import com.wy.camera.status.model.CameraStatus;
import com.wy.camera.status.model.ProbeReport;
import com.wy.camera.status.service.CameraStatusService;
import com.wy.commons.exception.CustomException;
import com.wy.commons.response.BaseResult;
import com.wy.commons.response.enums.CommonResultCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外接口。
 *
 * <p>注意 {@code POST /api/v1/probe}：原始实现用 GET 触发全量拉流，
 * 会被浏览器预取、监控探活、爬虫误触发；这里改成 POST，并且立即返回 202，
 * 让"触发"和"等待结果"彻底解耦。</p>
 *
 * @author baijin-chao
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StatusController {

    private final CameraStatusService cameraStatusService;

    /** 全量快照 */
    @GetMapping("/cameras")
    public BaseResult<CameraSnapshot> cameras() {
        return BaseResult.ok(cameraStatusService.snapshot());
    }

    /** 单路状态 */
    @GetMapping("/cameras/{deviceId}/{channelId}")
    public BaseResult<CameraStatus> camera(@PathVariable String deviceId,
                                           @PathVariable String channelId) {
        return cameraStatusService.find(deviceId, channelId)
                .map(BaseResult::ok)
                .orElseThrow(() -> new CustomException(CommonResultCodeEnum.CODE_4040));
    }

    /**
     * 触发一轮探测：立即返回 202，结果通过 /cameras 或 /probe/latest 获取。
     *
     * <p>上一轮未结束时返回 409，而不是排队堆积。</p>
     */
    @PostMapping("/probe")
    public ResponseEntity<BaseResult<String>> probe() {
        if (cameraStatusService.triggerAsync()) {
            return ResponseEntity.accepted().body(BaseResult.ok("探测任务已提交"));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(BaseResult.fail(CommonResultCodeEnum.CODE_4090));
    }

    /** 最近一轮探测报告 */
    @GetMapping("/probe/latest")
    public BaseResult<ProbeReport> latestProbe() {
        return cameraStatusService.lastReport()
                .map(BaseResult::ok)
                .orElseGet(() -> {
                    BaseResult<ProbeReport> result = BaseResult.ok();
                    result.setMessage("尚未执行过探测");
                    return result;
                });
    }

    /** 清空快照 */
    @DeleteMapping("/cameras")
    public BaseResult<Boolean> clear() {
        cameraStatusService.clear();
        return BaseResult.ok(true);
    }

    /** 平台连通性检查（会真实登录一次） */
    @GetMapping("/health/livegbs")
    public BaseResult<Boolean> health() {
        return BaseResult.ok(cameraStatusService.ping());
    }
}
