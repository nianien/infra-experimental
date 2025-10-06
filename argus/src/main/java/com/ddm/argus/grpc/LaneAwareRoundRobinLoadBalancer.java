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
 * - 若 TraceInfo 中无 lane → 只在“无 lane”的 READY 子通道中轮询；
 * - 若有 lane → 优先同 lane，若无 READY 则回退 default；
 * - 两个桶都空 → UNAVAILABLE。
 */
final class LaneAwareRoundRobinLoadBalancer extends LoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(LaneAwareRoundRobinLoadBalancer.class);

    private final Helper helper;

    /**
     * 用「地址列表 + lane」做 key，避免 Attributes 抖动带来的等价性问题
     */
    private final Map<ScKey, Subchannel> subsByKey = new LinkedHashMap<>();
    /**
     * Subchannel -> 规范化后的 lane（"" 表示无 lane）
     */
    private final Map<Subchannel, String> laneOf = new ConcurrentHashMap<>();
    /**
     * READY 子通道桶：lane -> List<Subchannel>
     */
    private final Map<String, List<Subchannel>> readyByLane = new HashMap<>();
    /**
     * lane -> round-robin 游标
     */
    private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();

    LaneAwareRoundRobinLoadBalancer(Helper helper) {
        this.helper = Objects.requireNonNull(helper, "helper");
    }

    /* ==================== 名称解析处理 ==================== */
    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolved) {
        final List<EquivalentAddressGroup> incoming = resolved.getAddresses();
//        if (log.isDebugEnabled()) {
//            log.debug("==>[argus] handleResolvedAddresses: {} EAG(s)", incoming.size());
//        }

        // 去重 + 保序
        final List<EquivalentAddressGroup> target = new ArrayList<>(new LinkedHashSet<>(incoming));

        // 1) 需要保留的 key
        final Set<ScKey> wantedKeys = new HashSet<>();
        for (EquivalentAddressGroup eag : target) {
            final String lane = eag.getAttributes().get(ChannelAttributes.LANE);
            wantedKeys.add(new ScKey(eag.getAddresses(), normalize(lane)));
        }

        // 2) 移除过期 subchannel
        final Iterator<Map.Entry<ScKey, Subchannel>> it = subsByKey.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ScKey, Subchannel> e = it.next();
            if (!wantedKeys.contains(e.getKey())) {
                Subchannel sc = e.getValue();
                sc.shutdown();
                laneOf.remove(sc);
                removeFromAllReady(sc);
                it.remove();
            }
        }

        // 3) 新增或复用 subchannel，并标注 lane
        for (EquivalentAddressGroup eag : target) {
            final String lane = eag.getAttributes().get(ChannelAttributes.LANE);
            final String normLane = normalize(lane);
            final ScKey key = new ScKey(eag.getAddresses(), normLane);

            Subchannel sc = subsByKey.get(key);
            if (sc == null) {
                Attributes scAttrs = Attributes.newBuilder().set(ChannelAttributes.LANE, lane).build();
                sc = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                        .setAddresses(eag)
                        .setAttributes(scAttrs)
                        .build());
                subsByKey.put(key, sc);
                laneOf.put(sc, normLane);
                sc.start(new LaneSubChannelListener(this, sc));
                sc.requestConnection();
            } else {
                // 理论上 key 已包含 lane，不会走到 lane 变化复用；这里做防御
                laneOf.put(sc, normLane);
            }
        }
        // 4) 汇总状态
        if (subsByKey.isEmpty()) {
            log.warn("==>[argus] no subchannels after resolution -> TRANSIENT_FAILURE");
            helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE,
                    new Picker(Status.UNAVAILABLE.withDescription("no subchannels")));
        } else {
            helper.updateBalancingState(ConnectivityState.CONNECTING, new Picker(null));
        }
    }

    @Override
    public void handleNameResolutionError(Status error) {
        log.warn("==>[argus] name resolution error: {}", error);
        helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new Picker(error));
    }

    @Override
    public void shutdown() {
        for (Subchannel sc : subsByKey.values()) {
            sc.shutdown();
        }
        subsByKey.clear();
        laneOf.clear();
        readyByLane.clear();
        cursors.clear();
        log.info("==>[argus] LB shutdown");
    }

    /**
     * 状态监听
     *
     * @param sc
     * @param stateInfo
     */
    private void onStateChange(Subchannel sc, ConnectivityStateInfo stateInfo) {
        final String laneKey = normalize(laneOf.get(sc));
        final List<Subchannel> readyList = readyByLane.computeIfAbsent(laneKey, k -> new ArrayList<>());

        switch (stateInfo.getState()) {
            case READY -> {
                if (!readyList.contains(sc)) readyList.add(sc);
                helper.updateBalancingState(ConnectivityState.READY, new Picker(null));
            }
            case CONNECTING, IDLE -> {
                readyList.remove(sc);
                helper.updateBalancingState(ConnectivityState.CONNECTING, new Picker(null));
                if (stateInfo.getState() == ConnectivityState.IDLE) sc.requestConnection();
            }
            case TRANSIENT_FAILURE -> {
                readyList.remove(sc);
                helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new Picker(stateInfo.getStatus()));
            }
            case SHUTDOWN -> {
                readyList.remove(sc);
            }
        }
        dumpReadyBuckets();
    }

    private void removeFromAllReady(Subchannel sc) {
        for (List<Subchannel> lst : readyByLane.values()) {
            lst.remove(sc);
        }
    }

    private void dumpReadyBuckets() {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder(128);
        sb.append("==>[argus] READY buckets: ");
        if (readyByLane.isEmpty()) {
            sb.append("<empty>");
        } else {
            boolean first = true;
            for (Map.Entry<String, List<Subchannel>> e : readyByLane.entrySet()) {
                if (!first) sb.append(" | ");
                first = false;
                String lane = e.getKey().isEmpty() ? "<default>" : e.getKey();
                sb.append(lane).append('=').append(e.getValue().size());
            }
        }
        log.debug(sb.toString());
    }


    /**
     * 选路器
     */
    private final class Picker extends SubchannelPicker {
        private final Status errorIfAny;

        Picker(Status errorIfAny) {
            this.errorIfAny = errorIfAny;
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            TraceInfo info = args.getCallOptions().getOption(TraceContext.CALL_OPT_TRACE_INFO);
            final String wantedLane = (info != null && info.lane() != null && !info.lane().isBlank())
                    ? info.lane().trim() : "";
            final String keyWanted = normalize(wantedLane);
            final String keyDefault = normalize("");

            final List<Subchannel> laneList = readyByLane.getOrDefault(keyWanted, Collections.emptyList());
            final List<Subchannel> defaultList = readyByLane.getOrDefault(keyDefault, Collections.emptyList());

            final boolean useWanted = !keyWanted.isEmpty() && !laneList.isEmpty();
            final List<Subchannel> candidates = useWanted ? laneList : defaultList;

            if (log.isDebugEnabled()) {
                log.debug("==>[argus] pick: wantedLane={} useWanted={} laneSize={} defaultSize={}",
                        keyWanted.isEmpty() ? "<default>" : keyWanted,
                        useWanted, laneList.size(), defaultList.size());
            }

            if (candidates.isEmpty()) {
                Status err = (errorIfAny != null) ? errorIfAny
                        : Status.UNAVAILABLE.withDescription(
                        keyWanted.isEmpty()
                                ? "no READY subchannel for default (no-lane)"
                                : "no READY subchannel for lane=" + wantedLane + " (and no fallback)");
                log.warn("==>[argus] pick error: {}", err);
                return PickResult.withError(err);
            }

            final String keyUsed = useWanted ? keyWanted : keyDefault;
            final AtomicInteger cursor = cursors.computeIfAbsent(keyUsed, k -> new AtomicInteger(0));
            final int idx = Math.floorMod(cursor.getAndIncrement(), candidates.size());
            final Subchannel chosen = candidates.get(idx);
            return PickResult.withSubchannel(chosen);
        }
    }

    /* ==================== 工具 & 内部类 ==================== */

    /**
     * 统一把可能为 null/blank 的 lane 变成非 null；空串表示“无 lane”
     */
    private static String normalize(String lane) {
        return (lane == null || lane.isBlank()) ? "" : lane;
    }

    /**
     * 组合键：地址列表（保序，不可变） + 规范化 lane
     */
    static final class ScKey {
        // 保序，不可变
        final List<SocketAddress> addrs;
        // "" 表示默认泳道
        final String lane;

        ScKey(List<SocketAddress> addrs, String lane) {
            this.addrs = List.copyOf(addrs);
            this.lane = normalize(lane);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ScKey k)) return false;
            return addrs.equals(k.addrs) && lane.equals(k.lane);
        }

        @Override
        public int hashCode() {
            return 31 * addrs.hashCode() + lane.hashCode();
        }

        @Override
        public String toString() {
            return addrs + "@" + lane;
        }
    }

    /**
     * 独立监听器，避免闭包捕获
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