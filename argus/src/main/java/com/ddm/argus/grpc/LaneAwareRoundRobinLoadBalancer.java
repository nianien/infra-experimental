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
     * key=地址+lane 的组合，避免 Attributes 抖动
     */
    private final Map<ScKey, Subchannel> subsByKey = new LinkedHashMap<>();
    /**
     * Subchannel -> lane
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
        log.debug("==>[argus] handleResolvedAddresses: {} EAG(s)", incoming.size());

        // 去重+保序
        final List<EquivalentAddressGroup> target = new ArrayList<>(new LinkedHashSet<>(incoming));

        // 1) 找出需要保留的 key
        final Set<ScKey> wantedKeys = new HashSet<>();
        for (EquivalentAddressGroup eag : target) {
            String lane = eag.getAttributes().get(ChannelAttributes.LANE);
            wantedKeys.add(new ScKey(eag.getAddresses(), normalize(lane)));
        }

        // 2) 移除过期 subchannel
        Iterator<Map.Entry<ScKey, Subchannel>> it = subsByKey.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ScKey, Subchannel> e = it.next();
            if (!wantedKeys.contains(e.getKey())) {
                Subchannel sc = e.getValue();
                log.debug("==>[argus] remove Subchannel {} lane={}", sc.getAllAddresses(), e.getKey().lane);
                sc.shutdown();
                laneOf.remove(sc);
                removeFromAllReady(sc);
                it.remove();
            }
        }

        // 3) 新增或复用
        for (EquivalentAddressGroup eag : target) {
            String lane = eag.getAttributes().get(ChannelAttributes.LANE);
            String normLane = normalize(lane);
            ScKey key = new ScKey(eag.getAddresses(), normLane);

            Subchannel sc = subsByKey.get(key);
            if (sc == null) {
                Attributes scAttrs = Attributes.newBuilder().set(ChannelAttributes.LANE, lane).build();
                sc = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                        .setAddresses(eag)
                        .setAttributes(scAttrs)
                        .build());
                subsByKey.put(key, sc);
                laneOf.put(sc, lane);
                log.debug("==>[argus] create Subchannel {} lane={}", sc.getAllAddresses(), lane);
                sc.start(new LaneSubChannelListener(this, sc));
                sc.requestConnection();
            } else {
                // lane变更后重建逻辑（理论上不会触发，因为key已包含lane）
                laneOf.put(sc, lane);
                log.debug("==>[argus] reuse Subchannel {} lane={}", sc.getAllAddresses(), lane);
            }
        }

        // 4) 状态汇总
        if (subsByKey.isEmpty()) {
            log.info("==>[argus] no subchannels after resolution -> TRANSIENT_FAILURE");
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
        log.info("==>[argus] shutdown");
        for (Subchannel sc : subsByKey.values()) sc.shutdown();
        subsByKey.clear();
        laneOf.clear();
        readyByLane.clear();
        cursors.clear();
    }

    /* ==================== 状态监听 ==================== */

    private void onStateChange(Subchannel sc, ConnectivityStateInfo stateInfo) {
        String laneKey = normalize(laneOf.get(sc));
        List<Subchannel> readyList = readyByLane.computeIfAbsent(laneKey, k -> new ArrayList<>());
        log.debug("==>[argus] onStateChange {} -> {} lane={}",
                sc.getAllAddresses(), stateInfo.getState(), laneOf.get(sc));

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
            case SHUTDOWN -> readyList.remove(sc);
        }
        dumpReadyBuckets();
    }

    private void removeFromAllReady(Subchannel sc) {
        for (List<Subchannel> lst : readyByLane.values()) lst.remove(sc);
    }

    private void dumpReadyBuckets() {
        if (log.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("==>[argus] READY buckets: ");
            if (readyByLane.isEmpty()) sb.append("<empty>");
            else readyByLane.forEach((k, v) -> sb.append(k).append('=').append(v.size()).append(' '));
            log.debug(sb.toString());
        }
    }

    /* ==================== 选路器 ==================== */

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

            log.info("==>[argus] pick: wantedLane={} useWanted={} laneSize={} defaultSize={}",
                    wanted, useWanted, laneList.size(), defaultList.size());

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

            log.info("==>[argus] pick -> lane={} idx={}/{} sc={}",
                    keyUsed, idx, candidates.size(), chosen.getAllAddresses());
            return PickResult.withSubchannel(chosen);
        }
    }

    /* ==================== 工具 & 内部类 ==================== */

    static final class ScKey {
        final List<SocketAddress> addrs; // 保序，不可变
        final String lane;               // "" 表示默认泳道

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

    // 将可能为 null 的 lane 归一化为非 null
    private static String normalize(String lane) {
        return (lane == null || lane.isBlank()) ? "" : lane;
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
            if (lb != null) lb.onStateChange(sc, stateInfo);
        }
    }
}