# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && \
    mvn clean package -DskipTests --no-transfer-progress

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /workspace/target/lex-transform-service-*.jar app.jar

# Artifact output directory (can be overridden by volume mount)
RUN mkdir -p /data/output && chown appuser:appgroup /data/output

USER appuser

ENV APP_OUTPUT_DIR=/data/output \
    APP_CONCURRENCY_POOL_SIZE=4 \
    SERVER_PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
