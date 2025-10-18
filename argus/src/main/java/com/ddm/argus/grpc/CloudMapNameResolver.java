package com.ddm.argus.grpc;

import com.ddm.argus.utils.EcsUtils.HostPort;
import com.ddm.argus.utils.CommonUtils;
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
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.ddm.argus.ecs.EcsConstants.*;

/**
 * 解析 Cloud Map 实例，并在 EAG Attributes 标注 lane。
 * 支持 target 形式: "service.namespace[:port]"
 */
public final class CloudMapNameResolver extends NameResolver {

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

        HostPort hp = parseHostPort(hostPort);
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
                .map(i -> toEag(i.attributes()))
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
    private EquivalentAddressGroup toEag(Map<String, String> a) {
        String ip = CommonUtils.firstNonBlank(a.get(CM_ATTR_IPV4), a.get(CM_ATTR_IPV4_FALLBACK));
        if (CommonUtils.isBlank(ip)) return null;

        // 端口优先级：显式 host:port > GRPC_PORT > 默认 80
        int port = resolvePort(targetPort, a);

        String lane = CommonUtils.trimToNull(a.get(CM_ATTR_LANE));
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

    /**
     * host[:port] 解析，仅允许一个冒号；端口非法则忽略
     */
    private static HostPort parseHostPort(String raw) {
        String host = raw;
        Integer port = null;

        int idx = raw.lastIndexOf(':');
        if (idx > 0 && idx == raw.indexOf(':')) { // 仅一个冒号
            String part = raw.substring(idx + 1);
            try {
                port = Integer.parseInt(part);
                host = raw.substring(0, idx);
            } catch (NumberFormatException ignore) {
                // 非法端口则当无端口处理
            }
        }
        return new HostPort(host, port);
    }

    /**
     * 端口优先级：显式 host:port > CM_ATTR_GRPC_PORT > 默认 80；
     */
    private static int resolvePort(Integer explicit, Map<String, String> attrs) {
        if (explicit != null) return explicit; // 显式写了就别瞎猜
        String grpcPort = (attrs != null) ? attrs.get(CM_ATTR_GRPC_PORT) : null;
        if (grpcPort != null && !grpcPort.isBlank()) {
            try {
                return Integer.parseInt(grpcPort.trim());
            } catch (NumberFormatException ignore) { /* 非法就跳过 */ }
        }
        return 80; // 默认兜底
    }


    // --------- EAG 去重 + 排序（统一的 Key） ---------

    /**
     * 排序键即去重键：lane -> host -> port
     */
    private record EagKey(String lane, String host, int port) implements Comparable<EagKey> {
        EagKey {
            lane = (lane == null || lane.isBlank()) ? "" : lane.trim();
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