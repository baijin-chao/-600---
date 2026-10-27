package com.wy.camera.status.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一路国标通道。
 *
 * @author baijin-chao
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveChannel {

    /** 国标设备编号 */
    private String deviceId;

    /** 国标通道编号 */
    private String channelId;

    /** 通道名称 */
    private String name;

    /** 平台上报的状态：ON / OFF */
    private String platformStatus;

    /** 结果集合里的唯一键：设备号_通道号 */
    public String key() {
        return deviceId + "_" + channelId;
    }
}
