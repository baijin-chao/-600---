package com.wy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * @author baijin-chao
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan("com.wy")
public class LiveGBSCameraStatusApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveGBSCameraStatusApplication.class, args);
    }
}
