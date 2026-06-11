package com.planb.planb_backend.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Redisson 분산 락 설정
 * - spring-boot-starter-data-redis(Lettuce)와 별도로 Redisson 클라이언트만 등록
 * - 동일한 Redis 서버를 바라보되 빈 충돌 없이 독립적으로 동작
 *
 * [프로필 분기]
 *  dev / local → Single Server (t4g.micro 단일 노드, TLS 활성화)
 *  prod        → Cluster Server (ElastiCache Serverless)
 */
@Slf4j
@Configuration
public class RedissonConfig {

    // @Value로 주입할 수 없으므로 생성자 주입 — 활성 프로필 판별에 사용
    private final Environment environment;

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    // prod: "host:port" 형태 (콤마 구분 다중 노드 가능) / dev: "" (빈 문자열)
    @Value("${spring.data.redis.cluster.nodes:}")
    private String clusterNodes;

    // true → rediss:// / false → redis://
    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    public RedissonConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String scheme = sslEnabled ? "rediss" : "redis";

        boolean isDevOrLocal = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("dev") || p.equalsIgnoreCase("local"));

        if (isDevOrLocal) {
            return createSingleServerClient(config, scheme);
        } else {
            return createClusterClient(config, scheme);
        }
    }

    /**
     * dev / local — Single Server 모드
     * t4g.micro 단일 노드 (전송 중 암호화 활성화 상태)
     */
    private RedissonClient createSingleServerClient(Config config, String scheme) {
        String address = scheme + "://" + host + ":" + port;
        log.info("[RedissonConfig] Single Server 모드 — address={}", address);
        try {
            config.useSingleServer()
                    .setAddress(address);
            return Redisson.create(config);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[RedissonConfig] Single Server 클라이언트 생성 실패 — address=" + address +
                    " / 원인: " + e.getMessage(), e);
        }
    }

    /**
     * prod — Cluster Server 모드
     * ElastiCache Serverless (클러스터 구조, TLS 활성화)
     * cluster.nodes: "host:port" 또는 "host1:port1,host2:port2,..."
     */
    private RedissonClient createClusterClient(Config config, String scheme) {
        if (clusterNodes == null || clusterNodes.isBlank()) {
            throw new IllegalStateException(
                    "[RedissonConfig] 운영 환경에서 spring.data.redis.cluster.nodes 가 비어 있습니다. " +
                    "환경변수 SPRING_DATA_REDIS_HOST 를 확인하세요.");
        }

        String[] addresses = Arrays.stream(clusterNodes.split(","))
                .map(String::trim)
                .filter(node -> !node.isBlank())
                .map(node -> {
                    // 이미 접두사가 붙은 경우 중복 방지
                    if (node.startsWith("redis://") || node.startsWith("rediss://")) {
                        return node;
                    }
                    return scheme + "://" + node;
                })
                .toArray(String[]::new);

        log.info("[RedissonConfig] Cluster Server 모드 — nodes={}", Arrays.toString(addresses));

        if (addresses.length == 0) {
            throw new IllegalStateException(
                    "[RedissonConfig] 파싱된 클러스터 노드 주소가 없습니다. " +
                    "cluster.nodes 값을 확인하세요: " + clusterNodes);
        }

        try {
            config.useClusterServers()
                    .addNodeAddress(addresses);
            return Redisson.create(config);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[RedissonConfig] Cluster Server 클라이언트 생성 실패 — nodes=" +
                    Arrays.toString(addresses) + " / 원인: " + e.getMessage(), e);
        }
    }
}
