package com.wy.camera.status.client;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 取流结果。
 *
 * <p>有 {@code streamId} 说明真的拉起了流；没有则说明平台拒绝或设备不在线，
 * 具体原因在 {@code errorMessage} 里。</p>
 *
 * @author baijin-chao
 */
@Data
@AllArgsConstructor
public class StreamStartResult {

    private String streamId;

    private String errorMessage;

    public boolean isStarted() {
        return streamId != null && !streamId.trim().isEmpty();
    }
}
