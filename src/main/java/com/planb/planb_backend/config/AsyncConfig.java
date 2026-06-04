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
 * - corePoolSize 7: FUNNEL_TOP_N(7)과 동일 — 7개 분석 task 전부 1배치로 동시 실행
 *   → 기존 core=4 시 4+3 배치 구조(순차 2배치)에서 1배치로 개선, 분석 시간 약 50% 단축
 *   → 분석 스레드가 외부 API(Naver/Insta/OpenAI) 호출 중에는 DB 커넥션을 점유하지 않으므로
 *      HikariCP max-pool-size(5) 초과 위험 없음
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
     * - AbortPolicy: 풀/큐 초과 시 RejectedExecutionException 발생
     *   → doStreamAsync()에서 즉시 잡아 클라이언트에 error 이벤트 전송 후 emitter.complete()
     *   → CallerRunsPolicy(이전) 대비: 서블릿 스레드를 90초간 점유하지 않으므로
     *     Tomcat 스레드 고갈 → 전체 API 500 현상 방지
     */
    @Bean(name = "streamingExecutor")
    public Executor streamingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("sse-streaming-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // HTTP 요청 스레드의 traceId를 스트리밍 스레드로 전파
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
