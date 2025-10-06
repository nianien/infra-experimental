#FROM amazoncorretto:21-alpine3.19
# 官方镜像在 ECR Public 的镜像加速
#FROM public.ecr.aws/docker/library/amazoncorretto:21-alpine3.19
# Amazon Corretto 官方命名空间
FROM public.ecr.aws/amazoncorretto/amazoncorretto:21-alpine3.19

WORKDIR /app

# non-root
RUN addgroup -S app && adduser -S app -G app

# 精确指定要复制的可运行 JAR
ARG JAR_FILE=target/*.jar
COPY --chown=app:app ${JAR_FILE} /app/app.jar

# JDK21 已容器感知，无需 UseContainerSupport
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# 核对端口：Order 常用 9091（如你已改，请同步这里）
EXPOSE 9091

USER app
ENTRYPOINT ["java","-jar","/app/app.jar"]