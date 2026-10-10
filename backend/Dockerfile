# Backend Dockerfile - Wave Parser API
# Build: docker build -t wave-parser-backend .
# Run: docker run -p 8080:8080 wave-parser-backend

# ---------------------------------------------------------------------------
# Build whisper.cpp: the embedded, queue-free transcription engine.
# The binary and a quantized tiny model are baked in so nothing is downloaded
# at runtime (Render's free filesystem is ephemeral).
# ---------------------------------------------------------------------------
FROM alpine:3.20 AS whisperbuild

RUN apk add --no-cache git cmake build-base curl

ARG WHISPER_CPP_TAG=v1.8.1
RUN git clone --depth 1 --branch ${WHISPER_CPP_TAG} https://github.com/ggml-org/whisper.cpp /whisper.cpp

WORKDIR /whisper.cpp
# GGML_NATIVE=OFF keeps the binary runnable on any x86_64 host (a build-host AVX512
# would otherwise SIGILL on the runtime host). AVX2/FMA/F16C are safe on cloud CPUs.
# Static build so the single whisper-cli binary carries its own ggml/whisper code
# (no shared libraries to copy into the slim runtime image).
RUN cmake -B build \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DWHISPER_BUILD_TESTS=OFF \
        -DWHISPER_BUILD_EXAMPLES=ON \
        -DGGML_NATIVE=OFF \
        -DGGML_OPENMP=OFF \
        -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON \
    && cmake --build build --target whisper-cli -j "$(nproc)"

# Multilingual tiny model, 5-bit quantized (~32 MB) for the smallest footprint.
RUN mkdir -p /models \
    && curl -L -o /models/ggml-tiny-q5_1.bin \
       https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin

# ---------------------------------------------------------------------------
# Backend build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /build

# Copy pom.xml first for better caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -DskipTests=true -Dmaven.test.skip=true -B

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# FFmpeg normalizes input to 16 kHz mono WAV; libstdc++/libgomp back the
# whisper.cpp binary.
RUN apk add --no-cache ffmpeg libstdc++ libgomp

# Create non-root user for security
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

# Copy built JAR
COPY --from=build /build/target/*.jar app.jar

# Copy the embedded transcription engine and its model
COPY --from=whisperbuild /whisper.cpp/build/bin/whisper-cli /opt/whisper/whisper-cli
COPY --from=whisperbuild /models/ggml-tiny-q5_1.bin /opt/models/ggml-tiny-q5_1.bin
RUN chmod +x /opt/whisper/whisper-cli

ENV APP_WHISPER_BINARY=/opt/whisper/whisper-cli \
    APP_WHISPER_MODEL=/opt/models/ggml-tiny-q5_1.bin

# Create data directory for audio files
RUN mkdir -p /data && chown -R appuser:appgroup /data

# Switch to non-root user
USER appuser

# Expose port - Render assigns PORT env var, default to 8080
EXPOSE 8080
ENV PORT=8080

# Health check
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# Run the application
# Render sets PORT env var; Spring Boot picks it up automatically.
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
