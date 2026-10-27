package com.wy.camera.status.service;

import com.wy.camera.status.client.LiveGbsException;
import com.wy.camera.status.client.LiveGbsGateway;
import com.wy.camera.status.config.LiveGbsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * token 管理：缓存 + 单飞登录 + 主动刷新。
 *
 * <p>一个批次可能持续几十秒，token 中途过期并不罕见。若把这种情况当成"设备离线"，
 * 就会得到一批看似正常、实则全错的业务数据。所以这里做两件事：</p>
 * <ol>
 *   <li>缓存 token 并提前 10% 过期，减少过期边界；</li>
 *   <li>提供 {@link #invalidate()} 与 {@link #getToken(boolean)}，
 *       让调用方在收到鉴权错误时能刷新并重试一次。</li>
 * </ol>
 *
 * <p>{@code login()} 用 synchronized 保证单飞：并发失败时只有一个线程去登录，
 * 其他线程复用结果，避免 token 失效瞬间把平台打出一串登录请求。</p>
 *
 * @author baijin-chao
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenProvider {

    private final LiveGbsGateway gateway;

    private final LiveGbsProperties properties;

    private volatile CachedToken cached;

    public String getToken() {
        return getToken(false);
    }

    /**
     * @param forceRefresh 为 true 时丢弃缓存并重新登录
     */
    public String getToken(boolean forceRefresh) {
        if (forceRefresh) {
            cached = null;
        }
        CachedToken current = cached;
        if (current != null && current.isValid()) {
            return current.token;
        }
        return login();
    }

    /** 标记 token 失效，下次取值时重新登录 */
    public void invalidate() {
        cached = null;
    }

    private synchronized String login() {
        // 双重检查：排队等锁期间可能已经有别的线程登录成功
        CachedToken current = cached;
        if (current != null && current.isValid()) {
            return current.token;
        }
        String token = gateway.login();
        if (token == null || token.trim().isEmpty()) {
            throw new LiveGbsException("登录成功但未返回 token");
        }
        long ttlMillis = properties.getTokenTtl().toMillis();
        // 提前 10% 失效，躲开"边用边过期"的时间窗
        long effectiveMillis = Math.max(1000L, ttlMillis - ttlMillis / 10);
        cached = new CachedToken(token, System.currentTimeMillis() + effectiveMillis);
        log.info("LiveGBS 登录成功，token 缓存 {} 秒", effectiveMillis / 1000);
        return token;
    }

    private static final class CachedToken {

        private final String token;

        private final long expireAt;

        private CachedToken(String token, long expireAt) {
            this.token = token;
            this.expireAt = expireAt;
        }

        private boolean isValid() {
            return System.currentTimeMillis() < expireAt;
        }
    }
}
