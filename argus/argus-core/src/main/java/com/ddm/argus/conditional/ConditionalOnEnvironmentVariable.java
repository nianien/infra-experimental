package com.ddm.argus.conditional;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.*;

/**
 * 条件注解：当系统环境变量满足要求时启用对应的配置/Bean。
 * <p>
 * 示例：
 * {@code @ConditionalOnEnvironmentVariable(name = "ECS_CONTAINER_METADATA_URI_V4")}
 * {@code @ConditionalOnEnvironmentVariable(name = "DEPLOY_ENV", includes = {"ecs","prod"})}
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnEnvironmentVariableCondition.class)
public @interface ConditionalOnEnvironmentVariable {

    /**
     * 要检测的环境变量名（例如 "ECS_CONTAINER_METADATA_URI_V4"）
     */
    String name();

    /**
     * 值匹配白名单：变量值（忽略大小写、trim 后）在此列表中任意一个即为满足。
     * 为空表示仅判断“变量存在”（且非空，除非 matchIfEmpty=true）。
     */
    String[] includes() default {};

    /**
     * 变量存在但为空字符串时是否算匹配（默认 false）
     */
    boolean matchIfEmpty() default false;
}