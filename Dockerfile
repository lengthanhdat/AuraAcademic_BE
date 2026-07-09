# ════════════════════════════════════════════════════════
# Stage 1: Build JAR
# ════════════════════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

# ════════════════════════════════════════════════════════
# Stage 2: Runtime (JRE Alpine — nhẹ hơn ~300MB)
# ════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/target/backend-0.0.1-SNAPSHOT.jar app.jar

# Expose standard port for the application
EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
