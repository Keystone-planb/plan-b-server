package com.planb.planb_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 장소 병렬 분석용 전용 ThreadPool
 * - corePoolSize 4: HikariCP max-pool-size(5) - 1(다른 요청 여유분) = 4
 *   → 분석 스레드 수 > DB 커넥션 수이면 커넥션 타임아웃 발생하므로 풀 크기 기준으로 제한
 * - 작업 종료 대기: graceful shutdown 보장
 */
@EnableAsync
@EnableScheduling
@Configuration
public class AsyncConfig {

    @Bean(name = "analysisExecutor")
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(30);
        executor.setThreadNamePrefix("place-analysis-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // 풀/큐 초과 시 AbortPolicy(기본) 대신 조용히 폐기 — 호출 측 트랜잭션 롤백 방지
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        // HTTP 요청 스레드의 traceId를 분석 스레드로 전파
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * SSE 스트리밍 파이프라인 전용 executor.
     * doStreamAsync() 외부 실행에만 사용 — analysisExecutor 포화 시 DiscardPolicy로
     * 스트리밍 task 자체가 폐기되는 문제를 방지한다.
     * - CallerRunsPolicy: 풀/큐 초과 시 호출 스레드(서블릿 스레드)에서 직접 실행 → 절대 폐기 안 됨
     */
    @Bean(name = "streamingExecutor")
    public Executor streamingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("sse-streaming-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // HTTP 요청 스레드의 traceId를 스트리밍 스레드로 전파
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
