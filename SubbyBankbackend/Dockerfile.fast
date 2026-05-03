# Fast Dockerfile — wraps a pre-built jar so we don't pay for Maven twice on
# Docker Desktop (where containerized Maven on Windows filesystems is glacial).
# Build with: mvn -DskipTests package   # outside docker
# Then:      docker build -f Dockerfile.fast -t subby-bank:local .
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
RUN apk add --no-cache wget
COPY target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep UP || exit 1
ENTRYPOINT ["java","--enable-preview","-jar","app.jar"]
