package com.wy.camera.status.model;

/**
 * 单路通道的探测结论。
 *
 * <p>{@link #ONLINE} 和 {@link #OFFLINE} 是业务结论，{@link #ERROR} 是"没测出来"——
 * 把这两类分开是本服务存在的意义之一：平台故障不应该被记成摄像头离线。</p>
 *
 * @author baijin-chao
 */
public enum ProbeStatus {

    /** 真实取流成功 */
    ONLINE,

    /** 平台明确拒绝取流，通常是设备离线或平台资源不足 */
    OFFLINE,

    /** 探测本身失败：超时、5xx、网络异常等，结果不可信 */
    ERROR
}
