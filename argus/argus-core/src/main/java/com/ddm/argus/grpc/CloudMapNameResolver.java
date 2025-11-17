package com.ddm.argus.grpc;

import com.ddm.argus.utils.CommonUtils;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.DiscoverInstancesRequest;
import software.amazon.awssdk.services.servicediscovery.model.DiscoverInstancesResponse;
import software.amazon.awssdk.services.servicediscovery.model.HttpInstanceSummary;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.ddm.argus.ecs.EcsConstants.*;
import static com.ddm.argus.utils.CommonUtils.isBlank;
import static com.ddm.argus.utils.CommonUtils.notBlank;

/**
 * 解析 Cloud Map 实例，并在 EAG Attributes 标注 lane。
 * 支持 target 形式: "service.namespace[:port]"
 */
public final class CloudMapNameResolver extends NameResolver {
    private static final Logger log = LoggerFactory.getLogger(CloudMapNameResolver.class);
    // --------- ctor args ---------
    private final String hostPortRaw;
    private final String namespace;
    private final String service;
    private final Integer targetPort;                 // 显式 host:port 覆盖
    private final String region;
    private final Duration refreshInterval;
    private final Args args;

    // --------- runtime ---------
    private Listener2 listener;
    private volatile boolean shutdown;
    private volatile ServiceDiscoveryClient sd;

    // 缓存最后一次成功结果（空结果时兜底；减少无意义 onResult）
    private volatile List<EquivalentAddressGroup> lastGood = List.of();
    private volatile int lastHash = 0;

    // 周期刷新（防并发）
    private ScheduledFuture<?> refreshTask;
    private final AtomicBoolean resolving = new AtomicBoolean(false);

    public CloudMapNameResolver(String hostPort, String region, Duration refreshInterval, Args args) {
        this.hostPortRaw = Objects.requireNonNull(hostPort, "hostPort");
        this.region = Objects.requireNonNull(region, "ecs.instance.region-id is required");
        this.refreshInterval = Objects.requireNonNull(refreshInterval, "refreshInterval required");
        this.args = Objects.requireNonNull(args, "args required");

        HostPort hp = new HostPort(hostPort);
        this.targetPort = hp.port();
        String host = hp.host();

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
        this.listener = Objects.requireNonNull(listener, "listener");
        ensureSd();

        // 先来一次同步解析（允许报错），随后进入仅靠周期的刷新（固定延迟，避免重入堆积）
        resolveOnce(true);
        this.refreshTask = args.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
            try {
                resolveOnce(false);
            } catch (Throwable ignore) {
            }
        }, refreshInterval.toMillis(), refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void refresh() {
        // 只在未刷新中时触发，避免与周期并发
        if (!resolving.compareAndSet(false, true)) return;
        try {
            resolveCore(true);
        } finally {
            resolving.set(false);
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        if (refreshTask != null) {
            refreshTask.cancel(true);
            refreshTask = null;
        }
        if (sd != null) {
            sd.close();
            sd = null;
        }
    }

    // ---------------- core ----------------

    private void resolveOnce(boolean allowError) {
        if (shutdown) return;
        if (!resolving.compareAndSet(false, true)) return; // 周期内防重入
        try {
            resolveCore(allowError);
        } finally {
            resolving.set(false);
        }
    }

    private void resolveCore(boolean allowError) {
        ensureSd();

        DiscoverInstancesResponse resp = sd.discoverInstances(
                DiscoverInstancesRequest.builder()
                        .namespaceName(namespace)
                        .serviceName(service)
                        .maxResults(100)
                        .build()
        );

        List<EquivalentAddressGroup> raw = resp.instances().stream()
                .map(this::toEag)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 用同一把 Key（lane, host, port）完成 去重+排序
        List<EquivalentAddressGroup> eags = dedupAndSortEags(raw);

        if (eags.isEmpty()) {
            // 空结果：不要把 channel 打进 backoff；有 lastGood 就继续用
            if (!lastGood.isEmpty()) {
                postOnResult(lastGood);
            } else if (allowError) {
                postOnError(Status.UNAVAILABLE.withDescription(
                        "No CloudMap instances for " + service + "." + namespace));
            }
            return;
        }

        int curHash = hashEags(eags);
        if (curHash != lastHash) {        // 仅在变更时下发，减少抖动
            lastGood = eags;
            lastHash = curHash;
            postOnResult(eags);
        }
    }

    // instance attributes -> EAG
    private EquivalentAddressGroup toEag(HttpInstanceSummary instance) {
        log.debug("==>[argus] Discover Instance, id={}, attributes={}", instance.instanceId(), instance.attributes());
        Map<String, String> attr = instance.attributes();
        String ip = CommonUtils.firstNonBlank(attr.get(CM_ATTR_IPV4), attr.get(CM_ATTR_IPV4_FALLBACK));
        if (CommonUtils.isBlank(ip)) return null;
        int port = targetPort;
        // 端口优先级：GRPC_PORT> host:port
        String grpcPort = (attr != null) ? attr.get(CM_ATTR_PORT) : null;
        if (notBlank(grpcPort)) {
            try {
                port = Integer.parseInt(grpcPort.trim());
            } catch (NumberFormatException ignore) { /* 非法就跳过 */ }
        }
        String lane = CommonUtils.trimToNull(attr.get(CM_ATTR_LANE));
        Attributes attrs = Attributes.newBuilder()
                .set(ChannelAttributes.LANE, lane)
                .build();
        return new EquivalentAddressGroup(new InetSocketAddress(ip, port), attrs);
    }

    // ---------------- callbacks ----------------
    private void postOnResult(List<EquivalentAddressGroup> eags) {
        args.getSynchronizationContext().execute(() -> {
            if (shutdown) return;
            listener.onResult(ResolutionResult.newBuilder()
                    .setAddresses(eags)
                    .setAttributes(Attributes.EMPTY)
                    .build());
        });
    }

    private void postOnError(Status status) {
        args.getSynchronizationContext().execute(() -> {
            if (shutdown) return;
            listener.onError(status);
        });
    }

    private void ensureSd() {
        if (sd != null) return;
        sd = ServiceDiscoveryClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    // ---------------- helpers ----------------
    public record HostPort(String host, Integer port) {
        public HostPort {
            if (host != null && host.contains(":")) {
                int idx = host.indexOf(':');
                if (idx == host.lastIndexOf(':')) {
                    String part = host.substring(idx + 1);
                    try {
                        int parsedPort = Integer.parseInt(part);
                        host = host.substring(0, idx);
                        port = parsedPort;
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            if (port == null) port = -1;
        }

        public HostPort(String raw) {
            this(raw, null);
        }
    }


    // --------- EAG 去重 + 排序（统一的 Key） ---------

    /**
     * 排序键即去重键：lane -> host -> port
     */
    private record EagKey(String lane, String host, int port) implements Comparable<EagKey> {
        EagKey {
            lane = isBlank(lane) ? "default" : lane.trim();
        }

        static EagKey from(EquivalentAddressGroup e) {
            InetSocketAddress isa = (InetSocketAddress) e.getAddresses().get(0);
            String ln = e.getAttributes().get(ChannelAttributes.LANE);
            return new EagKey(ln, isa.getHostString(), isa.getPort());
        }

        @Override
        public int compareTo(EagKey o) {
            int c1 = lane.compareTo(o.lane);
            if (c1 != 0) return c1;
            int c2 = host.compareTo(o.host);
            if (c2 != 0) return c2;
            return Integer.compare(port, o.port);
        }
    }

    private static List<EquivalentAddressGroup> dedupAndSortEags(List<EquivalentAddressGroup> in) {
        if (in == null || in.isEmpty()) return List.of();
        // TreeMap: 排序 + 去重（保留第一条）
        Map<EagKey, EquivalentAddressGroup> map = new TreeMap<>();
        for (EquivalentAddressGroup e : in) {
            map.putIfAbsent(EagKey.from(e), e);
        }
        return new ArrayList<>(map.values());
    }

    private static int hashEags(List<EquivalentAddressGroup> eags) {
        int h = 1;
        for (EquivalentAddressGroup e : eags) {
            InetSocketAddress isa = (InetSocketAddress) e.getAddresses().get(0);
            String ln = e.getAttributes().get(ChannelAttributes.LANE);
            h = 31 * h + Objects.hash(ln == null ? "" : ln, isa.getHostString(), isa.getPort());
        }
        return h;
    }


}