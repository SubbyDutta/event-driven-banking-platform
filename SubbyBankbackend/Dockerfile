# Multi-stage build — compiles from source inside Docker.
# Used by docker-compose.aws.yml for cloud deploys (no host Maven needed).
# For fast local rebuilds use Dockerfile.fast (requires `mvn package` first).

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Warm the local Maven cache first so iterative builds are fast.
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app

RUN apk add --no-cache wget

# Copy the fat jar produced by spring-boot-maven-plugin. Exactly one *.jar is expected.
COPY --from=build /src/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep UP || exit 1

ENTRYPOINT ["java","--enable-preview","-jar","app.jar"]
