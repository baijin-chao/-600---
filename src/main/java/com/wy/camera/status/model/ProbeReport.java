package com.wy.camera.status.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 一轮探测的统计报告。
 *
 * @author baijin-chao
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProbeReport {

    /** 本轮运行 ID，用于日志串联 */
    private String runId;

    private Instant startedAt;

    private Instant finishedAt;

    private long durationMs;

    /** 平台返回的通道总数 */
    private int total;

    /** 真实取流成功 */
    private int online;

    /** 真实取流失败 */
    private int offline;

    /** 探测本身异常，结论不可信 */
    private int error;

    /** 平台上报在线 */
    private int platformOnline;

    /** 平台上报离线 */
    private int platformOffline;

    private RunStatus status;

    private String message;

    @Override
    public String toString() {
        return "ProbeReport{runId='" + runId + "', total=" + total + ", online=" + online
                + ", offline=" + offline + ", error=" + error + ", duration=" + durationMs
                + "ms, status=" + status + '}';
    }

    /** 一轮探测的整体结论 */
    public enum RunStatus {

        /** 全部通道都测出来了 */
        SUCCESS,

        /** 有部分通道探测异常，但大部分有结论 */
        PARTIAL,

        /** 平台没有返回通道 */
        EMPTY,

        /** 连平台都没调通（登录/通道列表失败） */
        FAILED
    }
}
