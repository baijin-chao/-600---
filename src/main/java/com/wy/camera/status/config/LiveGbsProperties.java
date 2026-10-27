package com.wy.camera.status.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.annotation.PostConstruct;
import java.time.Duration;

/**
 * LiveGBS 平台连接配置。
 *
 * <p>所有敏感项都从环境变量注入，默认值只用于本地跑通，<b>不要</b>把真实密码写进 yml。</p>
 *
 * @author baijin-chao
 */
@Data
@ConfigurationProperties(prefix = "livegbs")
public class LiveGbsProperties {

    /** 平台地址，例如 http://192.168.1.10:10000 */
    private String baseUrl;

    /** 平台账号 */
    private String username;

    /**
     * 平台密码。LiveGBS 的登录接口要求传 <b>32 位大写 MD5</b>，
     * 即 md5(明文密码) 后转大写，而不是明文。
     */
    private String password;

    /** token 缓存时长，应略小于平台侧真实有效期 */
    private Duration tokenTtl = Duration.ofMinutes(20);

    /** 单次拉取通道列表的上限 */
    private int channelLimit = 100000;

    @PostConstruct
    void validate() {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalStateException("缺少配置 livegbs.base-url（或用环境变量 LIVEGBS_BASE_URL 指定）");
        }
        if (baseUrl.endsWith("/")) {
            // Forest 的 {livegbs_base_url} 直接在 yml 里引用 livegbs.base-url，
            // 这里改字段不会影响它，所以只能显式报错，避免拼出 //api/v1/...
            throw new IllegalStateException("livegbs.base-url 结尾不要带斜杠：" + baseUrl);
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalStateException("缺少配置 livegbs.username（或用环境变量 LIVEGBS_USERNAME 指定）");
        }
        if (channelLimit < 1) {
            throw new IllegalStateException("livegbs.channel-limit 必须大于 0");
        }
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            tokenTtl = Duration.ofMinutes(20);
        }
    }
}
