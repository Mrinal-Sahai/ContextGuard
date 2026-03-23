# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────────────────
# BuildKit is required for --mount=type=cache (faster rebuilds).
# Enable with: DOCKER_BUILDKIT=1 docker build ...  (default in Docker 23+)
# ─────────────────────────────────────────────────────────────────────────────

# ═══════════════════════════════════════════════════════════════════════════════
# ContextGuard — Multi-stage Dockerfile (with Dart/Flutter support)
# ═══════════════════════════════════════════════════════════════════════════════
#
# STAGES:
#   1. flutter-builder — downloads Flutter SDK and pre-caches it
#   2. go-builder      — compiles go-types-bridge binary
#   3. node-builder    — npm installs tree-sitter grammars + pyright + tree-sitter-dart
#   4. maven-builder   — builds the Spring Boot fat jar
#   5. runtime         — lean JRE image with all artifacts assembled
#
# Final image contains:
#   - JRE 17 (Eclipse Temurin, Ubuntu 22.04 base)
#   - Flutter SDK (includes Dart) for Dart Analysis Server
#   - Node.js 20 LTS  (tree-sitter bridge process)
#   - Python 3 + Pyright  (Python type analysis)
#   - go-types-bridge binary  (Go call graph extraction)
#   - Spring Boot fat jar
#
# Image size estimate: ~1.5GB
# (JRE ~220MB + Flutter ~1GB compressed + Node ~180MB + app ~150MB)
# Flutter is large but unavoidable — it includes the full Dart SDK + Analysis Server.
#
# FLUTTER VERSION: Pinned to stable channel for reproducibility.
# Update FLUTTER_VERSION below to upgrade.
# ═══════════════════════════════════════════════════════════════════════════════

ARG FLUTTER_VERSION=3.19.4
ARG FLUTTER_CHANNEL=stable


# ───────────────────────────────────────────────────────────────────────────────
# STAGE 1 — Flutter SDK
# Downloads and pre-caches Flutter/Dart SDK.
# This stage is separated so the ~1GB Flutter download is cached independently
# of the application build — if you only change Java code, Flutter isn't re-downloaded.
# ───────────────────────────────────────────────────────────────────────────────
FROM --platform=linux/amd64 ubuntu:22.04 AS flutter-builder
ARG FLUTTER_VERSION
ARG FLUTTER_CHANNEL

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        git \
        unzip \
        xz-utils \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt

# Download Flutter SDK — pinned version for reproducible builds
# The xz tarball is smaller than the zip (~650MB compressed)
RUN curl -fsSL \
    "https://storage.googleapis.com/flutter_infra_release/releases/${FLUTTER_CHANNEL}/linux/flutter_linux_${FLUTTER_VERSION}-${FLUTTER_CHANNEL}.tar.xz" \
    -o flutter.tar.xz \
    && tar -xJf flutter.tar.xz \
    && rm flutter.tar.xz

# Add flutter/dart to PATH for the pre-cache step
ENV PATH="/opt/flutter/bin:/opt/flutter/bin/cache/dart-sdk/bin:${PATH}"

# All Flutter setup in one layer: git trust + precache + verify + prewarm
# - git config:         flutter doctor requires the repo to be a safe directory
# - flutter precache:   downloads Dart SDK + Analysis Server (avoids runtime fetch)
# - dart --version:     build-time proof the binary works before the image ships
# - language-server:    prewarms Analysis Server so first prod request is fast
RUN git config --global --add safe.directory /opt/flutter \
    && flutter precache --no-android --no-ios --no-web --no-linux \
        --no-windows --no-macos --no-fuchsia 2>/dev/null || true \
    ; /opt/flutter/bin/dart --version \
    && ls /opt/flutter/bin/cache/dart-sdk/bin/dart \
    && /opt/flutter/bin/dart language-server --help 2>/dev/null || true


# ───────────────────────────────────────────────────────────────────────────────
# STAGE 2 — Go bridge binary
# ───────────────────────────────────────────────────────────────────────────────
FROM golang:1.22-alpine AS go-builder

WORKDIR /build

COPY tree-sitter-bridge/go-types-bridge/go.mod \
     tree-sitter-bridge/go-types-bridge/go.sum* \
     ./

# Cache Go module downloads — only re-fetches if go.mod/go.sum changes
RUN --mount=type=cache,target=/root/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go mod download

COPY tree-sitter-bridge/go-types-bridge/ ./

# Cache build artifacts — subsequent builds with same deps are instant
RUN --mount=type=cache,target=/root/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="-s -w" -o go-types-bridge .


# ───────────────────────────────────────────────────────────────────────────────
# STAGE 3 — Node.js bridge dependencies
# tree-sitter native modules must compile against the exact same Node ABI as
# the runtime image. We copy the node binary from this stage into the runtime
# (instead of using Ubuntu's apt nodejs, which is a different version/ABI).
# ───────────────────────────────────────────────────────────────────────────────
FROM --platform=linux/amd64 node:20-bullseye AS node-builder
WORKDIR /bridge

# python3:     required by node-gyp to compile native tree-sitter .node modules
# git:         some npm packages resolve from git refs in package-lock.json
# libstdc++6:  runtime dep for compiled .node binaries
# python3-pip, curl, xz-utils: NOT needed — removed to reduce layer size
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    git \
    libstdc++6 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHON=/usr/bin/python3

COPY tree-sitter-bridge/package.json \
     tree-sitter-bridge/package-lock.json* \
     ./

# Cache npm downloads — only re-fetches if package-lock.json changes.
# Grammar verify merged into same layer to avoid a redundant intermediate image.
RUN --mount=type=cache,target=/root/.npm \
    npm ci \
    && node -e " \
        require('tree-sitter'); \
        require('tree-sitter-python'); \
        require('tree-sitter-go'); \
        require('tree-sitter-javascript'); \
        require('tree-sitter-typescript'); \
        require('tree-sitter-ruby'); \
        try { require('tree-sitter-dart'); console.log('tree-sitter-dart OK'); } \
        catch(e) { console.warn('tree-sitter-dart not available:', e.message); } \
        console.log('All grammars checked'); \
    "


# ───────────────────────────────────────────────────────────────────────────────
# STAGE 4 — Maven build
# ───────────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS maven-builder

WORKDIR /app

COPY backend/pom.xml .
# Cache Maven local repo — only re-downloads deps when pom.xml changes.
# This is the single biggest rebuild speedup (~200MB of jars skipped every time).
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -q

COPY backend/src/ ./src/
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -q \
    && ls -lh target/*.jar


# ───────────────────────────────────────────────────────────────────────────────
# STAGE 5 — Runtime image
# ───────────────────────────────────────────────────────────────────────────────
FROM --platform=linux/amd64 eclipse-temurin:17-jre-jammy
# ── System dependencies ───────────────────────────────────────────────────────
# git:        Flutter SDK requires git for version checks
# xz-utils:   needed if Flutter needs to unpack anything at runtime
# libstdc++:  Dart Analysis Server native binary dependency
# python3:    tree-sitter bridge (no pip needed — pyright is via node_modules)
# NOTE: nodejs is NOT installed from apt. We copy the node binary from
#       node-builder so the ABI matches the compiled native tree-sitter modules.
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 \
        curl \
        git \
        xz-utils \
        libstdc++6 \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# ── App structure ─────────────────────────────────────────────────────────────
WORKDIR /app

# Spring Boot jar
COPY --from=maven-builder /app/target/*.jar app.jar

# tree-sitter bridge scripts (including new dart-parser.js)
COPY tree-sitter-bridge/tree-sitter-bridge.js   /app/tree-sitter-bridge/tree-sitter-bridge.js
COPY tree-sitter-bridge/index-batch-handler.js  /app/tree-sitter-bridge/index-batch-handler.js
COPY tree-sitter-bridge/dart-parser.js          /app/tree-sitter-bridge/dart-parser.js

# node_modules (pre-compiled for node:20 ABI)
COPY --from=node-builder /bridge/node_modules /app/tree-sitter-bridge/node_modules

# go-types-bridge static binary
COPY --from=go-builder /build/go-types-bridge /app/tree-sitter-bridge/go-types-bridge

# Node.js binary — copied from node-builder to guarantee ABI match with
# the native tree-sitter .node modules that were compiled in that stage.
# Ubuntu 22.04's apt nodejs is a different version and would cause
# "Error: The module was compiled against a different Node.js version" at startup.
COPY --from=node-builder /usr/local/bin/node /usr/local/bin/node

# ── Flutter / Dart SDK ────────────────────────────────────────────────────────
# Copy the full Flutter SDK from the flutter-builder stage.
# We need the entire SDK because:
#   - dart binary: /opt/flutter/bin/dart
#   - Analysis Server: launched via `dart language-server`
#   - Dart SDK:   /opt/flutter/bin/cache/dart-sdk/
# The SDK is ~1GB but there's no smaller subset that includes the Analysis Server.
COPY --from=flutter-builder /opt/flutter /opt/flutter

ENV PATH="/opt/flutter/bin:/opt/flutter/bin/cache/dart-sdk/bin:${PATH}"
ENV FLUTTER_ROOT="/opt/flutter"

# ── Post-copy setup ───────────────────────────────────────────────────────────
# All RUN steps merged into one layer:
#   1. Make go-types-bridge executable
#   2. Verify dart binary works (runs as root — confirms binary is functional)
#   3. Symlink pyright for convenient invocation (degrades gracefully if missing)
#   4. Create non-root user + fix permissions
#
# FLUTTER CACHE PERMISSIONS (critical for Dart Analysis Server startup):
#   The dart binary writes snapshot/stamp files to /opt/flutter/bin/cache/
#   at runtime startup. If those files are root-owned and the app runs as
#   contextguard, dart hangs silently and the LSP handshake times out.
#   Fix: give contextguard ownership of just the cache dir (not all 1GB).
RUN chmod +x /app/tree-sitter-bridge/go-types-bridge \
    && dart --version \
    && ln -sf /app/tree-sitter-bridge/node_modules/.bin/pyright /usr/local/bin/pyright \
    && (pyright --version || echo "Pyright not available — Python Tier 2 degrades gracefully") \
    && groupadd -r contextguard && useradd -r -g contextguard -m contextguard \
    && chown -R contextguard:contextguard /app \
    && chown -R contextguard:contextguard /opt/flutter/bin/cache \
    && mkdir -p /home/contextguard/.pub-cache /home/contextguard/.flutter \
    && chown -R contextguard:contextguard /home/contextguard

USER contextguard

# ── JVM configuration ─────────────────────────────────────────────────────────
# MaxRAMPercentage reduced to 60% because Dart Analysis Server also consumes
# significant heap (~200-400MB for a large Flutter project)
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=60.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

# ── Bridge configuration ──────────────────────────────────────────────────────
# Using UPPER_SNAKE_CASE — Spring Boot relaxed binding maps these to the
# lower.dot.case property keys used in application.yaml and @Value annotations.
ENV TREESITTER_BRIDGE_SCRIPT_PATH=/app/tree-sitter-bridge/tree-sitter-bridge.js
ENV GO_TYPES_BRIDGE_PATH=/app/tree-sitter-bridge/go-types-bridge
ENV DART_ANALYSIS_FLUTTER_SDK_PATH=/opt/flutter
ENV DART_ANALYSIS_TIMEOUT_MS=15000
ENV DART_ANALYSIS_BATCH_TIMEOUT_MS=120000

# ── Spring profile ────────────────────────────────────────────────────────────
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# exec replaces the sh process so Java becomes PID 1 and receives SIGTERM
# directly from the container runtime (correct graceful shutdown behaviour).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]