package com.ddm.argus.grpc;

import com.ddm.argus.ecs.EcsInstanceProperties;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;

import java.net.URI;

/**
 * 名称解析器提供者：支持 "dns://service.namespace[:port]" 形式，
 * 优先使用 Cloud Map 解析，失败时回退系统 DNS。
 */
public class HybridDnsNameResolverProvider extends NameResolverProvider {

    private final GrpcProperties grpcProperties;
    private final EcsInstanceProperties ecsProps;

    public HybridDnsNameResolverProvider(GrpcProperties grpcProperties,
                                         EcsInstanceProperties ecsProps) {
        this.grpcProperties = grpcProperties;
        this.ecsProps = ecsProps;
    }

    @Override
    public String getDefaultScheme() {
        return "cloud";
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
        if (!"dns".equalsIgnoreCase(targetUri.getScheme())) {
            return null;
        }
        String path = targetUri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return null;
        }
        String hostPort = path.startsWith("/") ? path.substring(1) : path;
        return new HybridDnsNameResolver(hostPort, grpcProperties, ecsProps, args);
    }
}