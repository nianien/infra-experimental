# syntax=docker/dockerfile:1

# --- Build stage ---
FROM maven:3.9.8-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Copy whole repo for -pl/-am build to resolve modules
COPY .. .

# Build only this module and its dependencies
RUN mvn -B -DskipTests package -pl demo/demo-web -am

# --- Runtime stage ---
FROM amazoncorretto:21-alpine3.19 AS runtime
WORKDIR /app

# Add non-root user
RUN addgroup -S app && adduser -S app -G app

# Copy fat jar
COPY --from=builder /workspace/demo/demo-web/target/demo-web-*.jar /app/app.jar

# Environment for JVM tuning in containers
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

EXPOSE 8080

USER app
ENTRYPOINT ["sh", "-lc", "exec java $JAVA_OPTS -jar /app/app.jar"]



