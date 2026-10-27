package com.wy.camera.status.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 单路通道的探测明细。
 *
 * <p>{@code platformStatus} 是平台上报的信令状态，{@code probeStatus} 是真实取流的结果。
 * 两者不一致（平台 ON、实际 OFFLINE）就是所谓的"假在线"。</p>
 *
 * @author baijin-chao
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraStatus {

    /** 国标设备编号 */
    private String deviceId;

    /** 国标通道编号 */
    private String channelId;

    /** 通道名称 */
    private String name;

    /** 平台上报状态：ON / OFF */
    private String platformStatus;

    /** 真实取流结论 */
    private ProbeStatus probeStatus;

    /** 取流接口返回的 HTTP 状态码，未发生 HTTP 错误时为 200 */
    private Integer httpCode;

    /** 失败原因，成功时为空 */
    private String errorMessage;

    /** 单路耗时（毫秒），含取流与关流 */
    private long probeCostMs;

    /** 本次同步时间 */
    private Instant syncedAt;

    /** 平台说在线、实际却拉不到流 */
    public boolean isFalseOnline() {
        return "ON".equalsIgnoreCase(platformStatus) && probeStatus == ProbeStatus.OFFLINE;
    }
}
