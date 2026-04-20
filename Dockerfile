# ══════════════════════════════════════════
# 1단계: 빌드
# BE 팀 확인: Gradle, Java 17, gradlew 있음
# ══════════════════════════════════════════
FROM gradle:8.5-jdk17-alpine AS builder

WORKDIR /app

# 의존성만 먼저 받아서 캐싱 (소스코드 변경 시 재다운로드 방지)
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

# 전체 소스코드 복사 후 빌드
COPY . .
RUN gradle build -x test --no-daemon

# ══════════════════════════════════════════
# 2단계: 실행 (JRE만 포함한 가벼운 이미지)
# BE 팀 확인: JAR명 planb_backend-0.0.1-SNAPSHOT.jar
#             포트 8080
# ══════════════════════════════════════════
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 보안: root 대신 전용 사용자로 실행
RUN addgroup -S planb && adduser -S planb -G planb

# 1단계에서 빌드된 JAR만 복사
COPY --from=builder /app/build/libs/planb_backend-0.0.1-SNAPSHOT.jar app.jar

RUN chown planb:planb app.jar

USER planb

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Xms256m", \
  "-Xmx512m", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=50.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
