package com.ddm.argus.grpc;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.DiscoverInstancesRequest;
import software.amazon.awssdk.services.servicediscovery.model.DiscoverInstancesResponse;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.ddm.argus.ecs.EcsConstants.*;

/**
 * 解析所有 CloudMap 实例，并在 EAG Attributes 上标注 lane<p/>
 * 地址协议支持 "cloud:///service.namespace[:port]" 形式
 */
public final class CloudMapNameResolver extends NameResolver {
    private final String hostPortRaw;
    private final String namespace;
    private final String service;
    private final Integer targetPort;
    // 必填
    private final String region;
    private final Duration refreshInterval;
    private Listener2 listener;
    private volatile boolean shutdown;
    private volatile ServiceDiscoveryClient sd;
    private final NameResolver.Args args;

    public CloudMapNameResolver(String hostPort,
                                String region,
                                Duration refreshInterval,
                                Args args) {
        this.hostPortRaw = hostPort;
        this.region = region;
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("ecs.instance.region-id is required");
        }
        this.refreshInterval = refreshInterval;
        this.args = args;

        String host = hostPort;
        Integer p = null;
        int idx = hostPort.lastIndexOf(':');
        if (idx > 0 && idx == hostPort.indexOf(':')) {
            try {
                p = Integer.parseInt(hostPort.substring(idx + 1));
                host = hostPort.substring(0, idx);
            } catch (NumberFormatException ignore) {
            }
        }
        this.targetPort = p;

        int dot = host.indexOf('.');
        if (dot <= 0 || dot == host.length() - 1) {
            throw new IllegalArgumentException("dns target must be service.namespace, got: " + hostPort);
        }
        this.service = host.substring(0, dot);
        this.namespace = host.substring(dot + 1);
    }

    @Override
    public String getServiceAuthority() {
        return hostPortRaw;
    }

    @Override
    public void start(Listener2 listener) {
        this.listener = listener;
        resolveOnce();
        args.getScheduledExecutorService().scheduleAtFixedRate(() -> {
            try {
                resolveOnce();
            } catch (Throwable ignore) {
            }
        }, refreshInterval.toMillis(), refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void refresh() {
        resolveOnce();
    }

    @Override
    public void shutdown() {
        shutdown = true;
        if (sd != null) {
            sd.close();
            sd = null;
        }
    }

    private void resolveOnce() {
        if (shutdown) return;
        try {
            ensureSd();

            // 拿全量实例；lane 过滤交给 LB 按请求头做
            DiscoverInstancesRequest req = DiscoverInstancesRequest.builder()
                    .namespaceName(namespace)
                    .serviceName(service)
                    .maxResults(100)
                    .build();

            DiscoverInstancesResponse resp = sd.discoverInstances(req);

            List<EquivalentAddressGroup> eags = resp.instances().stream()
                    .map(i -> {
                        Map<String, String> a = i.attributes();
                        String ip = a.getOrDefault(CM_ATTR_IPV4, a.get(CM_ATTR_IPV4_FALLBACK));
                        if (ip == null || ip.isBlank()) return null;
                        int port = choosePort(targetPort, a.get(CM_ATTR_GRPC_PORT), a.get(CM_ATTR_PORT));
                        String lane = a.get(CM_ATTR_LANE);

                        Attributes attrs = Attributes.newBuilder()
                                .set(ChannelAttributes.LANE, lane) // 关键：把实例的 lane 标注到地址上
                                .build();

                        return new EquivalentAddressGroup(new InetSocketAddress(ip, port), attrs);
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (eags.isEmpty()) {
                listener.onError(Status.UNAVAILABLE.withDescription(
                        "No CloudMap instances for " + service + "." + namespace));
                return;
            }

            listener.onResult(NameResolver.ResolutionResult.newBuilder()
                    .setAddresses(eags)
                    .setAttributes(Attributes.EMPTY)
                    .build());
        } catch (Exception e) {
            listener.onError(Status.UNAVAILABLE
                    .withDescription("CloudMap discover failed: " + service + "." + namespace + " - " + e.getMessage())
                    .withCause(e));
        }
    }

    private void ensureSd() {
        if (sd != null) return;
        sd = ServiceDiscoveryClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    private static int choosePort(Integer targetPort, String grpcPort, String awsPort) {
        if (targetPort != null) return targetPort;
        Integer gp = toInt(grpcPort);
        if (gp != null) return gp;
        Integer ap = toInt(awsPort);
        return (ap != null) ? ap : 80;
    }

    private static Integer toInt(String s) {
        try {
            return (s == null || s.isBlank()) ? null : Integer.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }


}