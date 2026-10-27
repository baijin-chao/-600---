package com.wy.camera.status.service;

import com.dtflys.forest.annotation.Get;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LiveGBS 平台接口声明。
 *
 * <p>用 Forest 把 REST 接口直接写成 Java 方法，业务代码里不再出现 URL 拼接。模板说明：</p>
 * <ul>
 *   <li>{@code {livegbs_base_url}}：来自 {@code forest.variables}，在 application.yml 里引用 {@code livegbs.base-url}；</li>
 *   <li>{@code {0}}、{@code {1}}：按方法参数顺序取值。</li>
 * </ul>
 *
 * @author baijin-chao
 */
@Component
public interface StatusApiClient {

    /**
     * 登录换取 token。
     *
     * <p>注意：password 必须是 32 位大写 MD5，不是明文。</p>
     */
    @Get("{livegbs_base_url}/api/v1/login?username={0}&password={1}&url_token_only=true")
    Map<String, Object> login(String username, String password);

    /**
     * 拉取全部通道。
     */
    @Get("{livegbs_base_url}/api/v1/device/channellist?channel_type=device&limit={0}&token={1}")
    Map<String, Object> channelList(int limit, String token);

    /**
     * 实时直播取流，返回体里有 StreamID 说明取流成功。
     */
    @Get("{livegbs_base_url}/api/v1/stream/start?token={0}&serial={1}&code={2}")
    Map<String, Object> startStream(String token, String serial, String code);

    /**
     * 停止直播流。
     *
     * <p>{@code check_outputs=true} 表示只在没有其他输出时才真正关流，
     * 避免把正在被业务观看的流关掉。</p>
     */
    @Get("{livegbs_base_url}/api/v1/stream/stop?check_outputs=true&token={0}&serial={1}&code={2}")
    String stopStream(String token, String serial, String code);
}
