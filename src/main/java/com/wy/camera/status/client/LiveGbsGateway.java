package com.wy.camera.status.client;

import java.util.List;

/**
 * LiveGBS 网关：把"怎么调接口"和"探测逻辑"隔开。
 *
 * <p>有两个实现：真实环境用 {@link ForestLiveGbsGateway}，
 * 演示环境（profile=mock）用内存模拟实现，这样没有平台也能把 600 路跑起来。</p>
 *
 * @author baijin-chao
 */
public interface LiveGbsGateway {

    /**
     * 登录并返回 token。
     *
     * @throws LiveGbsException 网络异常、鉴权失败或响应异常
     */
    String login();

    /**
     * 拉取全部通道。
     *
     * @throws LiveGbsException 调用失败
     */
    List<LiveChannel> listChannels(String token);

    /**
     * 发起一次真实取流。
     *
     * @throws LiveGbsException HTTP 失败；业务失败通过返回值的 streamId 为空表达
     */
    StreamStartResult startStream(String token, LiveChannel channel);

    /**
     * 停止取流。实现方必须吞掉异常：这是收尾动作，不能覆盖主流程的结果。
     */
    void stopStream(String token, LiveChannel channel);
}
