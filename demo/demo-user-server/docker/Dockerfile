# 运行阶段 - 用 Amazon Corretto JDK21 (ARM/Alpine)
FROM amazoncorretto:21-alpine3.19

WORKDIR /app

# 添加非 root 用户，符合安全最佳实践
RUN addgroup -S app && adduser -S app -G app

# 拷贝本地构建好的 fat jar
COPY target/demo-user-server-*.jar app.jar

# JVM 优化参数（容器环境友好）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

# 暴露 gRPC 服务端口
EXPOSE 9092

USER app
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]