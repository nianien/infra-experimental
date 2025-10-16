#FROM amazoncorretto:21-alpine3.19
# 官方镜像在 ECR Public 的镜像加速
FROM public.ecr.aws/docker/library/amazoncorretto:21-alpine3.19

WORKDIR /app

# non-root
RUN addgroup -S app && adduser -S app -G app

# 精确指定要复制的可运行 JAR
ARG JAR_FILE=target/*.jar

COPY --chown=app:app ${JAR_FILE} /app/app.jar

# JDK21 已容器感知，无需 UseContainerSupport
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# 动态端口：可在 build 时通过 --build-arg APP_PORT=xxxx 覆盖
ARG APP_PORT=8080
ENV APP_PORT=$APP_PORT
EXPOSE $APP_PORT

USER app
ENTRYPOINT ["java","-jar","/app/app.jar"]