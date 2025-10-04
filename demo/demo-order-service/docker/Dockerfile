# runtime
FROM amazoncorretto:21-alpine3.19

WORKDIR /app

# non-root
RUN addgroup -S app && adduser -S app -G app

# 精确指定要复制的可运行 JAR
ARG JAR_FILE=target/*.jar
COPY --chown=app:app ${JAR_FILE} /app/app.jar

# JDK21 已容器感知，无需 UseContainerSupport
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# 核对端口
EXPOSE 9091

USER app
ENTRYPOINT ["java","-jar","/app/app.jar"]