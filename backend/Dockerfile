# ⚠️  WARNING — THIS IS THE LEGACY DEVELOPMENT-ONLY DOCKERFILE
# ============================================================
# This file builds ONLY the Spring Boot jar (Java only).
# It does NOT include:
#   - Node.js / tree-sitter bridge  (required for Python, Go, Ruby, JS, TS parsing)
#   - Flutter / Dart Analysis Server (required for Dart parsing)
#   - go-types-bridge binary         (required for Go type-resolved call edges)
#
# Container built from this file will start, but ALL non-Java language parsing
# will silently fail at runtime. Use this ONLY for rapid local Java-only iteration.
#
# ✅ FOR PRODUCTION (and full multi-language support), use the root Dockerfile:
#    docker build -f Dockerfile .          ← 5-stage build with all language bridges
#    docker compose up --build             ← uses root Dockerfile automatically
# ============================================================

# =============================
# Stage 1: Build with Maven
# =============================
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml ./
COPY src ./src
RUN mvn clean package -DskipTests -B

# =============================
# Stage 2: Runtime image
# =============================
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
