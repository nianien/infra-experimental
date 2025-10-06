package com.ddm.argus.grpc;

import com.ddm.argus.grpc.TraceContext.TraceInfo;
import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LaneAwareRoundRobinLoadBalancer
 * <p>
 * 规则：
 * - 如果本次调用的 TraceInfo 中没有 lane，则只在“无 lane”的 READY 子通道中轮询；
 * - 如果有 lane，则优先在同 lane 的 READY 子通道中轮询；
 * 若该 lane 没有 READY，则回退到“无 lane”的 READY 子通道；
 * - 两个桶都空则返回 UNAVAILABLE。
 * <p>
 * 日志：
 * - 解析地址、子通道状态变更、选路决策均有 DEBUG 日志；关键异常路径有 INFO/ERROR。
 */
final class LaneAwareRoundRobinLoadBalancer extends LoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(LaneAwareRoundRobinLoadBalancer.class);


    private final Helper helper;

    /**
     * 以「地址列表」为 key 的 Subchannel 索引，避免 EAG Attributes 变化造成 equals 不一致
     */
    private final Map<List<SocketAddress>, Subchannel> subsByAddr = new LinkedHashMap<>();

    /**
     * Subchannel -> lane 标签（null 表示“无 lane”）
     */
    private final Map<Subchannel, String> laneOf = new ConcurrentHashMap<>();

    /**
     * 各泳道下 READY 子通道列表（key 为 lane 或 "<default>"）
     */
    private final Map<String, List<Subchannel>> readyByLane = new HashMap<>();

    /**
     * 各泳道的轮询游标
     */
    private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();

    LaneAwareRoundRobinLoadBalancer(Helper helper) {
        this.helper = Objects.requireNonNull(helper, "helper");
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolved) {
        final List<EquivalentAddressGroup> incoming = resolved.getAddresses();
        if (log.isDebugEnabled()) {
            log.debug("[LB] handleResolvedAddresses: {} EAG(s) received", incoming.size());
        }

        // 目标地址去重且保持顺序
        final List<EquivalentAddressGroup> target = new ArrayList<>(new LinkedHashSet<>(incoming));

        // 1) 关闭已不存在的 subchannel
        final Set<List<SocketAddress>> wantedKeys = new HashSet<>();
        for (EquivalentAddressGroup eag : target) {
            wantedKeys.add(eag.getAddresses());
        }
        final Iterator<Map.Entry<List<SocketAddress>, Subchannel>> it = subsByAddr.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<List<SocketAddress>, Subchannel> e = it.next();
            if (!wantedKeys.contains(e.getKey())) {
                Subchannel sc = e.getValue();
                if (log.isDebugEnabled()) {
                    log.debug("[LB] remove Subchannel {} (addresses removed)", sc.getAllAddresses());
                }
                sc.shutdown();
                laneOf.remove(sc);
                removeFromAllReady(sc);
                it.remove();
            }
        }

        // 2) 新增或复用 subchannel，并（重新）标注 lane
        for (EquivalentAddressGroup eag : target) {
            final List<SocketAddress> key = eag.getAddresses();
            Subchannel sc = subsByAddr.get(key);

            final String lane = eag.getAttributes().get(ChannelAttributes.LANE); // 关键：读取 Resolver 标注的 lane
            if (sc == null) {
                Attributes scAttrs = Attributes.newBuilder().set(ChannelAttributes.LANE, lane).build();
                sc = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                        .setAddresses(eag)
                        .setAttributes(scAttrs)
                        .build());
                subsByAddr.put(key, sc);
                laneOf.put(sc, lane);
                if (log.isDebugEnabled()) {
                    log.debug("[LB] create Subchannel {} lane={}", sc.getAllAddresses(), toLaneKey(lane));
                }
                sc.start(new LaneSubChannelListener(this, sc)); // 独立类，避免闭包 final 限制
                sc.requestConnection();
            } else {
                // 防御：刷新 lane 标签（Resolver 侧 lane 变化时）
                laneOf.put(sc, lane);
                if (log.isDebugEnabled()) {
                    log.debug("[LB] reuse Subchannel {} lane={}", sc.getAllAddresses(), toLaneKey(lane));
                }
            }
        }

        // 3) 推送总状态
        if (subsByAddr.isEmpty()) {
            if (log.isInfoEnabled()) {
                log.info("[LB] no subchannels after resolution -> TRANSIENT_FAILURE");
            }
            helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE,
                    new Picker(Status.UNAVAILABLE.withDescription("no subchannels")));
        } else {
            helper.updateBalancingState(ConnectivityState.CONNECTING, new Picker(null));
        }
    }

    @Override
    public void handleNameResolutionError(Status error) {
        if (log.isWarnEnabled()) {
            log.warn("[LB] name resolution error: {}", error);
        }
        helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new Picker(error));
    }

    @Override
    public void shutdown() {
        if (log.isInfoEnabled()) log.info("[LB] shutdown");
        for (Subchannel sc : subsByAddr.values()) {
            sc.shutdown();
        }
        subsByAddr.clear();
        laneOf.clear();
        readyByLane.clear();
        cursors.clear();
    }

    /* ==================== 内部：监听器与状态维护 ==================== */

    private void onStateChange(Subchannel sc, ConnectivityStateInfo stateInfo) {
        final String laneKey = toLaneKey(laneOf.get(sc));
        final List<Subchannel> readyList = readyByLane.computeIfAbsent(laneKey, k -> new ArrayList<>());

        if (log.isDebugEnabled()) {
            log.debug("[LB] onStateChange {} -> {} lane={}",
                    sc.getAllAddresses(), stateInfo.getState(), toLaneKey(laneOf.get(sc)));
        }

        switch (stateInfo.getState()) {
            case READY -> {
                if (!readyList.contains(sc)) readyList.add(sc);
                helper.updateBalancingState(ConnectivityState.READY, new Picker(null));
            }
            case CONNECTING, IDLE -> {
                readyList.remove(sc);
                helper.updateBalancingState(ConnectivityState.CONNECTING, new Picker(null));
                if (stateInfo.getState() == ConnectivityState.IDLE) {
                    sc.requestConnection();
                }
            }
            case TRANSIENT_FAILURE -> {
                readyList.remove(sc);
                helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new Picker(stateInfo.getStatus()));
            }
            case SHUTDOWN -> {
                readyList.remove(sc);
            }
        }
        if (log.isDebugEnabled()) dumpReadyBuckets();
    }

    private void removeFromAllReady(Subchannel sc) {
        for (List<Subchannel> lst : readyByLane.values()) {
            lst.remove(sc);
        }
    }

    private static String toLaneKey(String lane) {
        return (lane == null || lane.isBlank()) ? "<default>" : lane;
    }

    private void dumpReadyBuckets() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("[LB] READY buckets: ");
        if (readyByLane.isEmpty()) {
            sb.append("<empty>");
        } else {
            boolean first = true;
            for (Map.Entry<String, List<Subchannel>> e : readyByLane.entrySet()) {
                if (!first) sb.append(" | ");
                first = false;
                sb.append(e.getKey()).append("=").append(e.getValue().size());
            }
        }
        log.debug(sb.toString());
    }

    /* ==================== 选路器：按 lane 精确匹配，桶内 round-robin ==================== */

    private final class Picker extends SubchannelPicker {
        private final Status errorIfAny;

        Picker(Status errorIfAny) {
            this.errorIfAny = errorIfAny;
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            // 关键：从 CallOptions 拿 TraceInfo（由客户端拦截器注入）
            final TraceInfo info = args.getCallOptions().getOption(TraceContext.CALL_OPT_TRACE_INFO);
            final String wanted = (info != null && info.lane() != null && !info.lane().isBlank())
                    ? info.lane().trim() : null;

            final String keyDefault = toLaneKey(null);      // 无 lane 桶
            final String keyWanted = toLaneKey(wanted);    // 目标 lane 桶

            final List<Subchannel> defaultList = readyByLane.getOrDefault(keyDefault, Collections.emptyList());
            final List<Subchannel> laneList = (wanted != null)
                    ? readyByLane.getOrDefault(keyWanted, Collections.emptyList())
                    : defaultList;

            // 有 lane 且该桶非空 → 用 lane 桶；否则回退 default
            final boolean useWanted = (wanted != null) && !laneList.isEmpty();
            final List<Subchannel> candidates = useWanted ? laneList : defaultList;

            log.info("==>[argus] pick: wantedLane={} useWanted={} laneSize={} defaultSize={}",
                    toLaneKey(wanted), useWanted, laneList.size(), defaultList.size());

            if (candidates.isEmpty()) {
                final Status err = (errorIfAny != null) ? errorIfAny
                        : Status.UNAVAILABLE.withDescription(
                        (wanted != null)
                                ? "no READY subchannel for lane=" + wanted + " (and no fallback)"
                                : "no READY subchannel for default (no-lane)");
                log.warn("==>[argus] pick error: {}", err);
                return PickResult.withError(err);
            }

            final String keyUsed = useWanted ? keyWanted : keyDefault;
            final AtomicInteger cursor = cursors.computeIfAbsent(keyUsed, k -> new AtomicInteger(0));
            final int idx = Math.floorMod(cursor.getAndIncrement(), candidates.size());
            final Subchannel chosen = candidates.get(idx);

            if (log.isDebugEnabled()) {
                log.debug("[LB] pick -> lane={} idx={}/{} sc={}",
                        keyUsed, idx, candidates.size(), chosen.getAllAddresses());
            }
            return PickResult.withSubchannel(chosen);
        }
    }

    /**
     * 独立监听器，避免匿名类捕获非 final 变量的问题
     */
    private static final class LaneSubChannelListener implements LoadBalancer.SubchannelStateListener {
        private final WeakReference<LaneAwareRoundRobinLoadBalancer> ref;
        private final Subchannel sc;

        LaneSubChannelListener(LaneAwareRoundRobinLoadBalancer owner, Subchannel sc) {
            this.ref = new WeakReference<>(owner);
            this.sc = sc;
        }

        @Override
        public void onSubchannelState(ConnectivityStateInfo stateInfo) {
            LaneAwareRoundRobinLoadBalancer lb = ref.get();
            if (lb != null) {
                lb.onStateChange(sc, stateInfo);
            }
        }
    }
}