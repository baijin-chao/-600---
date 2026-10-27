package com.wy.camera.status.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 对外返回的全量快照。
 *
 * <p>整体替换而不是原地更新：调用方拿到的永远是一份完整、自洽的数据，
 * 不会看到"清空到一半"的中间态。</p>
 *
 * @author baijin-chao
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraSnapshot {

    private int total;

    private int online;

    private int offline;

    private int error;

    /** 平台说在线、实际拉不到流的通道数 */
    private int falseOnline;

    /** 快照里最新的一次同步时间 */
    private Instant updatedAt;

    /** 键：设备编号_通道编号 */
    private Map<String, CameraStatus> cameras;
}
