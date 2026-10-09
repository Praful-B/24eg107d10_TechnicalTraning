# Backend Dockerfile - Wave Parser API
# Build: docker build -t wave-parser-backend .
# Run: docker run -p 8080:8080 wave-parser-backend

FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /build

# Copy pom.xml first for better caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Install FFmpeg (required for audio chunking)
RUN apk add --no-cache ffmpeg

# Create non-root user for security
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

# Copy built JAR
COPY --from=build /build/target/*.jar app.jar

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
# Render sets PORT env var; Spring Boot picks it up automatically
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
