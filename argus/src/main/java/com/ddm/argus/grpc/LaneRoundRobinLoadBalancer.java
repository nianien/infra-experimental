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
 * - 若 TraceInfo 无 lane → 只在“无 lane”的 READY 子通道中轮询；
 * - 若有 lane → 优先同 lane，若无 READY 则回退 default；
 * - 两个桶都空 → UNAVAILABLE。
 */
final class LaneRoundRobinLoadBalancer extends LoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(LaneRoundRobinLoadBalancer.class);

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

    LaneRoundRobinLoadBalancer(Helper helper) {
        this.helper = Objects.requireNonNull(helper, "helper");
    }

    /**
     * 地址解析
     *
     * @param resolved
     */
    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolved) {
        List<EquivalentAddressGroup> incoming = resolved.getAddresses();
        if (log.isDebugEnabled()) {
            log.debug("==>[argus] handleResolvedAddresses: {} EAG(s)", incoming.size());
        }

        // 去重 + 保序
        List<EquivalentAddressGroup> target = new ArrayList<>(new LinkedHashSet<>(incoming));

        //需要保留的 key
        Set<ScKey> wantedKeys = new HashSet<>();
        for (EquivalentAddressGroup eag : target) {
            String lane = eag.getAttributes().get(ChannelAttributes.LANE);
            wantedKeys.add(new ScKey(eag.getAddresses(), normalize(lane)));
        }

        //移除过期 subchannel
        Iterator<Map.Entry<ScKey, Subchannel>> it = subsByKey.entrySet().iterator();
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

        //新增或复用 subchannel，并标注 lane
        for (EquivalentAddressGroup eag : target) {
            String lane = eag.getAttributes().get(ChannelAttributes.LANE);
            String normLane = normalize(lane);
            ScKey key = new ScKey(eag.getAddresses(), normLane);
            Subchannel sc = subsByKey.get(key);
            if (sc == null) {
                Attributes scAttrs = Attributes.newBuilder()
                        .set(ChannelAttributes.LANE, lane)
                        .build();
                sc = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                        .setAddresses(eag)
                        .setAttributes(scAttrs)
                        .build());
                subsByKey.put(key, sc);
                laneOf.put(sc, normLane);
                if (log.isDebugEnabled()) {
                    log.debug("==>[argus] create Subchannel key={} lane={}", key, lane);
                }
                sc.start(new LaneSubChannelListener(this, sc));
                sc.requestConnection();
            } else {
                laneOf.put(sc, normLane);
                if (log.isDebugEnabled()) {
                    log.debug("==>[argus] reuse Subchannel key={} lane={}", key, lane);
                }
            }
        }

        //汇总状态
        if (subsByKey.isEmpty()) {
            log.warn("==>[argus] no subchannels -> TRANSIENT_FAILURE");
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
        subsByKey.values().forEach(Subchannel::shutdown);
        subsByKey.clear();
        laneOf.clear();
        readyByLane.clear();
        cursors.clear();
    }

    /**
     * 状态监听
     *
     * @param sc
     * @param stateInfo
     */
    private void onStateChange(Subchannel sc, ConnectivityStateInfo stateInfo) {
        String laneKey = normalize(laneOf.get(sc));
        List<Subchannel> readyList = readyByLane.computeIfAbsent(laneKey, k -> new ArrayList<>());

        if (log.isDebugEnabled()) {
            log.debug("==>[argus] onStateChange -> {} lane={}", stateInfo.getState(), laneKey);
        }

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
        readyByLane.values().forEach(lst -> lst.remove(sc));
    }

    private void dumpReadyBuckets() {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder(128);
        sb.append("==>[argus] READY buckets: ");
        if (readyByLane.isEmpty()) sb.append("<empty>");
        else readyByLane.forEach((k, v) -> sb.append(k).append('=').append(v.size()).append(' '));
        log.debug(sb.toString());
    }


    /**
     * 路由选择
     */
    private final class Picker extends SubchannelPicker {
        private final Status errorIfAny;

        Picker(Status errorIfAny) {
            this.errorIfAny = errorIfAny;
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            TraceInfo info = args.getCallOptions().getOption(TraceContext.CALL_OPT_TRACE_INFO);
            String wanted = (info != null && info.lane() != null && !info.lane().isBlank()) ? info.lane().trim() : "";
            String keyWanted = normalize(wanted);
            String keyDefault = normalize("");

            List<Subchannel> laneList = readyByLane.getOrDefault(keyWanted, Collections.emptyList());
            List<Subchannel> defaultList = readyByLane.getOrDefault(keyDefault, Collections.emptyList());

            boolean useWanted = !keyWanted.isEmpty() && !laneList.isEmpty();
            List<Subchannel> candidates = useWanted ? laneList : defaultList;
            if (log.isDebugEnabled()) {
                log.debug("==>[argus] pick: wantedLane={} useWanted={} laneSize={} defaultSize={}",
                        wanted, useWanted, laneList.size(), defaultList.size());
            }

            if (candidates.isEmpty()) {
                Status err = (errorIfAny != null) ? errorIfAny
                        : Status.UNAVAILABLE.withDescription("no READY subchannel for lane=" + wanted);
                log.warn("==>[argus] pick error: {}", err);
                return PickResult.withError(err);
            }

            String keyUsed = useWanted ? keyWanted : keyDefault;
            AtomicInteger cursor = cursors.computeIfAbsent(keyUsed, k -> new AtomicInteger(0));
            int idx = Math.floorMod(cursor.getAndIncrement(), candidates.size());
            Subchannel chosen = candidates.get(idx);
            if (log.isDebugEnabled()) {
                log.debug("==>[argus] pick -> lane={} idx={}/{}", keyUsed, idx, candidates.size());
            }
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
        private final WeakReference<LaneRoundRobinLoadBalancer> ref;
        private final Subchannel sc;

        LaneSubChannelListener(LaneRoundRobinLoadBalancer owner, Subchannel sc) {
            this.ref = new WeakReference<>(owner);
            this.sc = sc;
        }

        @Override
        public void onSubchannelState(ConnectivityStateInfo stateInfo) {
            LaneRoundRobinLoadBalancer lb = ref.get();
            if (lb != null) {
                lb.onStateChange(sc, stateInfo);
            }
        }
    }
}