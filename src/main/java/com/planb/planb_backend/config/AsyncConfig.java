package com.planb.planb_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 장소 병렬 분석용 전용 ThreadPool
 * - corePoolSize 7: 1차 퍼널에서 선발된 7개 장소를 동시에 처리
 * - 작업 종료 대기: graceful shutdown 보장
 */
@EnableAsync
@EnableScheduling
@Configuration
public class AsyncConfig {

    @Bean(name = "analysisExecutor")
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(7);
        executor.setMaxPoolSize(14);
        executor.setQueueCapacity(30);
        executor.setThreadNamePrefix("place-analysis-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
