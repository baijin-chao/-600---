package com.wy.camera;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 启动冒烟：用 mock profile，确保容器能正常装配。
 *
 * @author baijin-chao
 */
@SpringBootTest(properties = {"camera-status.cron=-"})
@ActiveProfiles("mock")
class LiveGBSCameraStatusApplicationTests {

    @Test
    void contextLoads() {
        // 容器起不来，这里就会失败
    }
}
