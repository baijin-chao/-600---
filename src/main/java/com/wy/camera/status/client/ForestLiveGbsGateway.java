package com.wy.camera.status.client;

import com.dtflys.forest.exceptions.ForestNetworkException;
import com.dtflys.forest.exceptions.ForestRuntimeException;
import com.wy.camera.status.config.LiveGbsProperties;
import com.wy.camera.status.service.StatusApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Forest 的真实网关实现。
 *
 * <p>这一层只做三件事：调接口、把异常翻译成 {@link LiveGbsException}、
 * 把平台返回的 Map 映射成领域对象。探测逻辑不关心 HTTP 细节。</p>
 *
 * @author baijin-chao
 */
@Slf4j
@Component
@Profile("!mock")
@RequiredArgsConstructor
public class ForestLiveGbsGateway implements LiveGbsGateway {

    private final StatusApiClient api;

    private final LiveGbsProperties properties;

    @Override
    public String login() {
        Map<String, Object> response;
        try {
            response = api.login(properties.getUsername(), properties.getPassword());
        } catch (ForestNetworkException ex) {
            String body = safeBody(ex);
            throw new LiveGbsException("登录失败：HTTP " + ex.getStatusCode() + " " + body,
                    ex.getStatusCode(), isAuthError(ex.getStatusCode(), body), ex);
        } catch (ForestRuntimeException ex) {
            throw new LiveGbsException("登录失败：" + ex.getMessage(), ex);
        }
        Object token = response == null ? null : response.get("URLToken");
        if (token == null || String.valueOf(token).trim().isEmpty()) {
            throw new LiveGbsException("登录响应缺少 URLToken，请检查账号密码（LiveGBS 要求 32 位大写 MD5）");
        }
        return String.valueOf(token).trim();
    }

    @Override
    public List<LiveChannel> listChannels(String token) {
        Map<String, Object> response;
        try {
            response = api.channelList(properties.getChannelLimit(), token);
        } catch (ForestNetworkException ex) {
            String body = safeBody(ex);
            throw new LiveGbsException("获取通道列表失败：HTTP " + ex.getStatusCode() + " " + body,
                    ex.getStatusCode(), isAuthError(ex.getStatusCode(), body), ex);
        } catch (ForestRuntimeException ex) {
            throw new LiveGbsException("获取通道列表失败：" + ex.getMessage(), ex);
        }

        Object list = response == null ? null : response.get("ChannelList");
        if (!(list instanceof List)) {
            throw new LiveGbsException("通道列表响应缺少 ChannelList 字段");
        }

        List<LiveChannel> channels = new ArrayList<>();
        for (Object element : (List<?>) list) {
            if (!(element instanceof Map)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) element;
            String deviceId = stringValue(map.get("DeviceID"));
            String channelId = stringValue(map.get("ID"));
            if (deviceId == null || channelId == null) {
                // 缺少主键的脏数据直接跳过，不让它污染整批任务
                continue;
            }
            String name = stringValue(map.get("Name"));
            channels.add(new LiveChannel(deviceId, channelId,
                    name == null ? channelId : name,
                    stringValue(map.get("Status"))));
        }
        return channels;
    }

    @Override
    public StreamStartResult startStream(String token, LiveChannel channel) {
        Map<String, Object> response;
        try {
            response = api.startStream(token, channel.getDeviceId(), channel.getChannelId());
        } catch (ForestNetworkException ex) {
            String body = safeBody(ex);
            throw new LiveGbsException("取流失败：HTTP " + ex.getStatusCode() + " " + body,
                    ex.getStatusCode(), isAuthError(ex.getStatusCode(), body), ex);
        } catch (ForestRuntimeException ex) {
            throw new LiveGbsException("取流失败：" + ex.getMessage(), ex);
        }
        Object streamId = response == null ? null : response.get("StreamID");
        if (streamId == null || String.valueOf(streamId).trim().isEmpty()) {
            return new StreamStartResult(null, "响应中无 StreamID");
        }
        return new StreamStartResult(String.valueOf(streamId).trim(), null);
    }

    @Override
    public void stopStream(String token, LiveChannel channel) {
        try {
            api.stopStream(token, channel.getDeviceId(), channel.getChannelId());
        } catch (Exception ex) {
            // 收尾动作失败不应影响本路探测结果，只留痕
            log.debug("停止取流失败，device={}，channel={}：{}", channel.getDeviceId(), channel.getChannelId(), ex.getMessage());
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String safeBody(ForestNetworkException ex) {
        try {
            return ex.getResponse() == null ? "" : String.valueOf(ex.getResponse().getContent());
        } catch (Exception ignore) {
            return "";
        }
    }

    /**
     * 判断是否属于 token 失效。除了 401/403，LiveGBS 有时会用 200 包一层业务错误码，
     * 所以再对响应体做一次关键词判断。
     */
    static boolean isAuthError(Integer status, String body) {
        if (status != null && (status == 401 || status == 403)) {
            return true;
        }
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase();
        boolean mentionsToken = lower.contains("token");
        return mentionsToken && (lower.contains("invalid") || lower.contains("expire") || lower.contains("失效"));
    }
}
