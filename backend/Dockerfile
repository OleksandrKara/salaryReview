# syntax=docker/dockerfile:1.7

# --- Stage 1: build the fat jar with Maven --------------------------------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

# Cache deps: copy pom + mvn wrapper first; download deps; then copy sources.
COPY .mvn ./.mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN ./mvnw -B -q clean package -DskipTests \
    && cp target/*.jar /workspace/app.jar

# --- Stage 2: slim JRE runtime -------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as non-root for safety
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder --chown=spring:spring /workspace/app.jar /app/app.jar

EXPOSE 8080

# JVM in container: pick up cgroup mem limits, exit on OOM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
