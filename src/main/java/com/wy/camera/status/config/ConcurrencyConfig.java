package com.wy.camera.status.config;

import com.wy.commons.concurrent.BatchTaskTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置。
 *
 * <p>这里只允许存在<b>一个</b>业务线程池，并且它跟随容器生命周期。
 * 早期版本的实现是每次任务执行时 {@code new ThreadPoolExecutor(100, 100, ...)}，
 * 每 10 分钟创建又销毁 100 个线程，既浪费又掩盖了队列无界的问题。</p>
 *
 * @author baijin-chao
 */
@Slf4j
@Configuration
public class ConcurrencyConfig {

    private ThreadPoolExecutor probeExecutor;

    @Bean(destroyMethod = "")
    public ThreadPoolExecutor probeExecutor(CameraStatusProperties properties) {
        properties.validate();
        this.probeExecutor = BatchTaskTemplate.newFixedPool(
                "camera-probe", properties.getThreadCount(), properties.getQueueCapacity());
        log.info("探测线程池已创建：core={}，队列容量={}", properties.getThreadCount(), properties.getQueueCapacity());
        return this.probeExecutor;
    }

    @PreDestroy
    public void shutdown() {
        log.info("准备关闭探测线程池");
        BatchTaskTemplate.shutdownGracefully(probeExecutor, 10000L);
    }
}
