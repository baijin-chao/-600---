FROM eclipse-temurin:8-jre-jammy

LABEL org.opencontainers.image.title="livegbs-camera-status" \
      org.opencontainers.image.description="LiveGBS 摄像头真实取流探测服务" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app

RUN groupadd --system app \
    && useradd --system --gid app --home /app app \
    && mkdir -p /app/logs \
    && chown -R app:app /app

# 先 mvn -DskipTests package 生成 target/livegbs-camera-status.jar
COPY target/livegbs-camera-status.jar /app/app.jar

USER app

EXPOSE 8080

ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseG1GC" \
    TZ=Asia/Shanghai

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
