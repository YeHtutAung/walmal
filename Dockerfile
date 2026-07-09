# ─────────────────────────────────────────────────────────────────
# Stage 1: Build
# Uses full JDK image to compile; output is a layered JAR.
# ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy wrapper and all pom.xml files first (layer-cached by Maven)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY walmal-common/pom.xml        walmal-common/pom.xml
COPY walmal-infrastructure/pom.xml walmal-infrastructure/pom.xml
COPY walmal-auth/pom.xml          walmal-auth/pom.xml
COPY walmal-product/pom.xml       walmal-product/pom.xml
COPY walmal-inventory/pom.xml     walmal-inventory/pom.xml
COPY walmal-order/pom.xml         walmal-order/pom.xml
COPY walmal-pos/pom.xml           walmal-pos/pom.xml
COPY walmal-warehouse/pom.xml     walmal-warehouse/pom.xml
COPY walmal-notification/pom.xml  walmal-notification/pom.xml
COPY walmal-app/pom.xml           walmal-app/pom.xml

RUN chmod +x mvnw && sed -i 's/\r//' mvnw && ./mvnw dependency:go-offline -B

COPY . .
RUN chmod +x mvnw && sed -i 's/\r//' mvnw && \
    ./mvnw package -DskipTests -B && \
    # Extract layered JAR for faster layer caching on subsequent builds
    java -Djarmode=layertools -jar walmal-app/target/walmal-app-*.jar extract --destination /app/extracted

# ─────────────────────────────────────────────────────────────────
# Stage 2: Runtime
# Minimal JRE image; non-root user; health check included.
# ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create a non-root user to run the application
RUN groupadd --system --gid 1001 walmal && \
    useradd  --system --uid  1001 --gid walmal --no-create-home walmal

# Install curl for the HEALTHCHECK probe (image is minimal JRE only)
RUN apt-get update -qq && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Copy layered JAR artifacts in dependency-order for optimal cache reuse
COPY --from=build --chown=walmal:walmal /app/extracted/dependencies/          ./
COPY --from=build --chown=walmal:walmal /app/extracted/spring-boot-loader/    ./
COPY --from=build --chown=walmal:walmal /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=walmal:walmal /app/extracted/application/           ./

# Create log directory writeable by non-root user
RUN mkdir -p /app/logs && chown walmal:walmal /app/logs

USER walmal

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]
