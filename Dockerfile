# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────────────────
# ContextGuard — Spring Boot Application Image
# ─────────────────────────────────────────────────────────────────────────────
#
# This image contains ONLY the Spring Boot application.
# The tree-sitter bridge and Dart Analysis Server run as separate containers:
#   - contextguard-tree-sitter  (Dockerfile.tree-sitter, port 3000)
#   - contextguard-dart-bridge  (Dockerfile.dart-bridge,  port 3001)
#
# Image size estimate: ~300MB  (JRE 17 + fat jar)
# Previously ~1.5GB (included Flutter SDK + Node.js).
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Maven build ──────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS maven-builder

WORKDIR /app

COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -q

COPY backend/src/ ./src/
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -q \
    && ls -lh target/*.jar


# ── Stage 2: Runtime image ────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates python3 python3-pip \
    && pip3 install --no-cache-dir semgrep \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=maven-builder /app/target/*.jar app.jar

RUN groupadd -r contextguard && useradd -r -g contextguard -m contextguard \
    && chown -R contextguard:contextguard /app

USER contextguard

ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
