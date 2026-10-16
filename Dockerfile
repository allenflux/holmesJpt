# 先在宿主机使用 JDK 27 执行 mvn package；镜像只接收打包产物和公开配置。
FROM eclipse-temurin:27_35-jre-resolute

WORKDIR /app
RUN groupadd --gid 10001 holmes \
    && useradd --uid 10001 --gid holmes --no-create-home --home-dir /app --shell /usr/sbin/nologin holmes \
    && mkdir -p /app/config /app/runbooks /app/tasks /app/data/evidence /app/data/holmes-log \
    && chown -R holmes:holmes /app/data \
    && chmod 0750 /app/data /app/data/evidence /app/data/holmes-log

COPY target/holmes-java-0.1.0.jar /app/app.jar
COPY config/tools-web.json /app/config/tools-web.json
COPY runbooks/ /app/runbooks/

ENV HOLMES_BIND_HOST=0.0.0.0 \
    HOLMES_PORT=8080 \
    HOLMES_TOOLS_FILE=config/tools-web.json \
    HOLMES_RUNBOOKS_DIR=runbooks \
    HOLMES_LOG_DATA_DIR=/app/data/holmes-log

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/app.jar"]
CMD ["web"]
