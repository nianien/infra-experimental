#FROM amazoncorretto:21-alpine3.19
FROM public.ecr.aws/docker/library/amazoncorretto:21-alpine3.19

WORKDIR /app

# 非 root 运行
RUN addgroup -S app && adduser -S app -G app

# 通过构建参数精确指定要复制的 JAR
# 构建时可传 --build-arg JAR_FILE=target/demo-web-*.jar
ARG JAR_FILE=target/*.jar
COPY --chown=app:app ${JAR_FILE} /app/app.jar

# JDK21 已默认容器感知，无需 UseContainerSupport
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

USER app
ENTRYPOINT ["java","-jar","/app/app.jar"]