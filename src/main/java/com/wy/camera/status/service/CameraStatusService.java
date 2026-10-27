package com.wy.camera.status.service;

import com.wy.camera.status.model.CameraSnapshot;
import com.wy.camera.status.model.CameraStatus;
import com.wy.camera.status.model.ProbeReport;

import java.util.Optional;

/**
 * 摄像头状态探测服务。
 *
 * @author baijin-chao
 */
public interface CameraStatusService {

    /**
     * 同步执行一轮探测，阻塞直到结束或超时。同一时刻只允许一轮。
     */
    ProbeReport probeNow();

    /**
     * 异步触发一轮探测，立即返回是否成功排队。
     *
     * @return false 表示上一轮还没结束，本次未触发
     */
    boolean triggerAsync();

    /**
     * 当前快照。
     */
    CameraSnapshot snapshot();

    /**
     * 最近一轮探测报告，从未执行过时为空。
     */
    Optional<ProbeReport> lastReport();

    /**
     * 按设备号 + 通道号查询单路状态。
     */
    Optional<CameraStatus> find(String deviceId, String channelId);

    /**
     * 清空快照。
     */
    void clear();

    /**
     * 平台连通性检查。
     */
    boolean ping();
}
