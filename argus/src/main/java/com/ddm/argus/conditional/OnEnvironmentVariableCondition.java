package com.ddm.argus.conditional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;

/**
 * 条件实现：判断指定环境变量是否存在、是否允许为空，
 * 以及是否命中 includes 白名单（忽略大小写与空白）。
 */
public class OnEnvironmentVariableCondition implements Condition {
    private static final Logger log = LoggerFactory.getLogger(OnEnvironmentVariableCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        AnnotationAttributes attrs = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(ConditionalOnEnvironmentVariable.class.getName())
        );
        if (attrs == null) return false;

        String name = attrs.getString("name");
        String[] includes = attrs.getStringArray("includes");
        boolean matchIfEmpty = attrs.getBoolean("matchIfEmpty");

        if (name == null || name.isBlank()) return false;

        Environment env = context.getEnvironment();
        // Boot 默认包含 SystemEnvironmentPropertySource，这里优先从 Environment 取，
        // 再兜底 System.getenv，确保在早期阶段也可用。
        String actual = env.getProperty(name);
        if (actual == null) actual = System.getenv(name);

        // 变量不存在
        if (actual == null) {
            log.debug("EnvCondition: {} not found -> false", name);
            return false;
        }

        String normalized = actual.trim();

        // 变量存在但为空，且不允许空
        if (normalized.isEmpty() && !matchIfEmpty) {
            log.debug("EnvCondition: {} is blank -> false", name);
            return false;
        }

        // 未提供 includes：仅校验“存在性/是否允许空”
        if (includes == null || includes.length == 0) {
            log.debug("EnvCondition: {}='{}' (no includes) -> true", name, actual);
            return true;
        }

        // includes 匹配（忽略大小写、trim）
        boolean ok = Arrays.stream(includes)
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .anyMatch(v -> v.equalsIgnoreCase(normalized));

        log.debug("EnvCondition: {}='{}', includes={} -> {}", name, actual, Arrays.toString(includes), ok);
        return ok;
    }
}