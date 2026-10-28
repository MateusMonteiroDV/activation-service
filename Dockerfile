# =========================================================================
# Multi-stage Dockerfile para activation-service (Java 21 LTS)
# =========================================================================

# --- Estágio 1: Build da aplicação ---
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# Copia metadados e wrapper primeiro para aproveitar cache de camadas
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B -q || true

# Copia o código fonte e compila o pacote (sem rodar testes durante o build da imagem)
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B -q

# Extrai os layers do Spring Boot para otimizar cache de container
RUN java -Djarmode=tools -jar target/activation-service-0.0.1-SNAPSHOT.jar extract --layers --destination extracted/ || \
    cp target/activation-service-0.0.1-SNAPSHOT.jar application.jar

# --- Estágio 2: Imagem final enxuta para execução (distroless/alpine JRE) ---
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="junio@deroyque.com" \
      service="activation-service"

# Cria grupo e usuário não-root (segurança em produção)
RUN addgroup -S appgroup -g 10001 && \
    adduser -S appuser -u 10001 -G appgroup

WORKDIR /app

# Copia o artefato construído
COPY --from=builder --chown=appuser:appgroup /workspace/target/activation-service-0.0.1-SNAPSHOT.jar app.jar

USER appuser:appgroup

# Ajustes de JVM para contêiner: usa memória limitada pelo cgroup, desliga JVM sharing
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8080

EXPOSE 8080

# Health check usando curl/wget integrado no Alpine contra o probe do Spring Boot Actuator
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
