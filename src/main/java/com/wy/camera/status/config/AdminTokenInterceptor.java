package com.wy.camera.status.config;

import com.wy.commons.exception.CustomException;
import com.wy.commons.response.enums.CommonResultCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 写操作鉴权拦截器。
 *
 * <p>读接口（GET）公开，写接口（触发探测、清空快照）需要 {@code X-Admin-Token} 头。
 * 未配置 token 时退化为不校验，但启动时打 WARN，避免生产环境裸奔而不自知。</p>
 *
 * @author baijin-chao
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";

    private final CameraStatusProperties properties;

    @PostConstruct
    void warnIfDisabled() {
        if (!StringUtils.hasText(properties.getAdminToken())) {
            log.warn("未配置 camera-status.admin-token，写操作接口（POST/DELETE）将不校验身份，请勿直接暴露到公网");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String expected = properties.getAdminToken();
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String actual = request.getHeader(HEADER);
        if (actual != null && MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        throw new CustomException(CommonResultCodeEnum.CODE_4010);
    }
}
