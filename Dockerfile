# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Resolve dependencies first so they are cached between builds when only sources change
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/econet-leads-backend-*.jar /workspace/app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre

# Unprivileged runtime user
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=America/Montreal \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=America/Montreal"

USER app
EXPOSE 8080

# /actuator/health is public (see SecurityConfig). Azure Container Apps uses its own probes; point
# them at the same path.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
