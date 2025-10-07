package com.ddm.argus.grpc;

import com.ddm.argus.ecs.EcsInstanceProperties;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * 名称解析器提供者：支持 "cloud:///service.namespace[:port]" 形式，
 * 优先使用 Cloud Map 解析，失败时回退系统 DNS。
 */
public class CloudMapNameResolverProvider extends NameResolverProvider {
    private static final Logger log = LoggerFactory.getLogger(CloudMapNameResolverProvider.class);
    private final static String scheme = "cloud";
    private final GrpcProperties grpcProperties;
    private final EcsInstanceProperties ecsProps;

    public CloudMapNameResolverProvider(GrpcProperties grpcProperties,
                                        EcsInstanceProperties ecsProps) {
        this.grpcProperties = grpcProperties;
        this.ecsProps = ecsProps;
    }

    @Override
    public String getDefaultScheme() {
        return scheme;
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        // 高于内置 dns(5)
        return 10;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, Args args) {
        log.info("==>[argus] CloudMapNameResolverProvider.newNameResolver targetUri={}", targetUri);
        // 1. scheme 必须匹配 cloud
        if (!scheme.equalsIgnoreCase(targetUri.getScheme())) {
            log.warn("==>[argus] skip: scheme mismatch (expected={}, actual={})", scheme, targetUri.getScheme());
            return null;
        }
        // 2. path 校验
        String authority = targetUri.getAuthority();
        String path = targetUri.getPath();
        // 支持 cloud://host:port 和 cloud:///host:port 两种写法
        String hostPort = null;
        if (authority != null && !authority.isBlank()) {
            hostPort = authority;
        } else if (path != null && path.length() > 1) {
            hostPort = path.startsWith("/") ? path.substring(1) : path;
        }
        if (hostPort == null || hostPort.isBlank()) {
            log.warn("==>[argus] skip: invalid URI: {}", targetUri);
            return null;
        }
        CloudMapNameResolver resolver = new CloudMapNameResolver(hostPort, grpcProperties, ecsProps, args);
        log.info("==>[argus] CloudMapNameResolver created successfully for {}", hostPort);
        return resolver;
    }
}