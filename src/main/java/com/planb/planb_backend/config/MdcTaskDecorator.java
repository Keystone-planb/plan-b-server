package com.planb.planb_backend.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 비동기 스레드(analysisExecutor, streamingExecutor)에 MDC 컨텍스트를 전파하는 데코레이터.
 *
 * MDC는 ThreadLocal 기반이라 새 스레드에는 자동 복사되지 않는다.
 * 이 데코레이터가 부모 스레드의 traceId를 캡처해 자식 스레드에 주입한다.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 부모 스레드(HTTP 요청 스레드)의 MDC 컨텍스트 캡처
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear(); // 스레드 풀 재사용 시 컨텍스트 누수 방지
            }
        };
    }
}
