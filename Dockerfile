# One image per service. Build the jars first (mvn -B package), then:
#   docker build --build-arg MODULE=web-server -t creditagent-web-server .
ARG BASE_IMAGE=eclipse-temurin:26-jre

FROM ${BASE_IMAGE}
ARG MODULE

LABEL \
      org.opencontainers.image.description="CreditAgent ${MODULE}" \
      org.opencontainers.image.licenses="UNLICENSED"

# Apply OS security fixes released after the base image was built (the CI image scan fails on
# fixable HIGH/CRITICAL findings), then create an unprivileged user; /data is pre-created with
# the right owner so a fresh named volume inherits it.
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y --no-install-recommends \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /app /data/credit-files \
 && chown -R app:app /app /data \
 && chmod 700 /data/credit-files

WORKDIR /app
COPY --chown=app:app ${MODULE}/target/*.jar /app/app.jar

USER app:app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080 8081 8082 8083 8085 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
