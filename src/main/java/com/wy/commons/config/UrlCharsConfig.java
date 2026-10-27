package com.wy.commons.config;

import com.wy.camera.status.config.AdminTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：URL 特殊字符放行 + 鉴权拦截器注册。
 *
 * <p>默认情况下 Tomcat 会拒绝 URL 里带 {@code []|{}^} 的请求并直接返回 400，
 * 而很多平台把 JSON 放在 query 里传参，所以这里放开限制。
 * 若部署在外部 Tomcat，需要在 server.xml 的 Connector 上加同样的属性。</p>
 *
 * @author baijin-chao
 */
@Configuration
@RequiredArgsConstructor
public class UrlCharsConfig implements WebMvcConfigurer {

    private final AdminTokenInterceptor adminTokenInterceptor;

    @Bean
    public ConfigurableServletWebServerFactory webServerFactory() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.addConnectorCustomizers(connector -> {
            connector.setProperty("relaxedQueryChars", "[]|{}\\");
            connector.setProperty("relaxedPathChars", "[]|{}^<>'\"\\");
        });
        return factory;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminTokenInterceptor).addPathPatterns("/api/**");
    }
}
