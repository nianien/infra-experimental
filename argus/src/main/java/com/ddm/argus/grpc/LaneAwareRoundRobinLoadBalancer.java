package com.ddm.argus.grpc;

import com.ddm.argus.grpc.TraceContext.TraceInfo;
import io.grpc.*;

import java.lang.ref.WeakReference;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * 按请求头 x-lane 精确匹配子通道，再在同泳道内做 round-robin。
 * 规则：
 * - Header 有 lane → 只在同 lane 的 READY 子通道内选
 * - Header 无 lane → 只在“无 lane”的 READY 子通道内选
 * - 无匹配 → UNAVAILABLE（不跨泳道）
 */
final class LaneAwareRoundRobinLoadBalancer extends LoadBalancer {

    private final Helper helper;

    /**
     * 以地址列表为 key，管理 Subchannel 生命周期，避免 EAG 属性变化导致的 equals 不一致
     */
    private final Map<List<SocketAddress>, Subchannel> subsByAddr = new LinkedHashMap<>();

    /**
     * Subchannel -> lane 标签（null 表示默认泳道）
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
        this.helper = helper;
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolved) {
        // 目标地址（去重 & 保序）
        final List<EquivalentAddressGroup> target = new ArrayList<>(new LinkedHashSet<>(resolved.getAddresses()));

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
            final String lane = eag.getAttributes().get(ChannelAttributes.LANE);

            if (sc == null) {
                Attributes scAttrs = Attributes.newBuilder().set(ChannelAttributes.LANE, lane).build();
                sc = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                        .setAddresses(eag)
                        .setAttributes(scAttrs)
                        .build());
                subsByAddr.put(key, sc);
                laneOf.put(sc, lane);
                sc.start(new LaneSubChannelListener(this, sc)); // 避免闭包问题
                sc.requestConnection();
            } else {
                // 防御性：刷新 lane 标签（解析结果 lane 变化时）
                laneOf.put(sc, lane);
            }
        }

        // 3) 更新整体状态（有 subchannel 则 CONNECTING，否则 TF）
        if (subsByAddr.isEmpty()) {
            helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE,
                    new Picker(Status.UNAVAILABLE.withDescription("no subchannels")));
        } else {
            helper.updateBalancingState(ConnectivityState.CONNECTING, new Picker(null));
        }
    }

    @Override
    public void handleNameResolutionError(Status error) {
        helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new Picker(error));
    }

    @Override
    public void shutdown() {
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
        readyByLane.computeIfAbsent(laneKey, k -> new ArrayList<>());
        final List<Subchannel> readyList = readyByLane.get(laneKey);

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
    }

    private void removeFromAllReady(Subchannel sc) {
        for (List<Subchannel> lst : readyByLane.values()) {
            lst.remove(sc);
        }
    }

    private static String toLaneKey(String lane) {
        return (lane == null || lane.isBlank()) ? "<default>" : lane;
    }

    /* ==================== 选路器：按 x-lane 精确匹配，lane 内轮询 ==================== */

    private final class Picker extends SubchannelPicker {
        private final Status errorIfAny;

        Picker(Status errorIfAny) {
            this.errorIfAny = errorIfAny;
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            final TraceInfo info = TraceContext.CTX_TRACE_INFO.get();
            final String wanted = (info != null && info.lane() != null && !info.lane().isBlank())
                    ? info.lane().trim() : null;                // 只判一次
            final boolean hasLane = (wanted != null);

            final String keyDefault = toLaneKey(null);          // “无 lane”桶
            final String keyWanted = toLaneKey(wanted);        // 目标 lane 桶

            final List<Subchannel> defaultList = readyByLane.getOrDefault(keyDefault, Collections.emptyList());
            final List<Subchannel> laneList = hasLane
                    ? readyByLane.getOrDefault(keyWanted, Collections.emptyList())
                    : defaultList;

            // 有 lane 且该桶非空 → 用 lane 桶；否则回退 default
            final boolean useWanted = hasLane && !laneList.isEmpty();
            final List<Subchannel> candidates = useWanted ? laneList : defaultList;

            if (candidates.isEmpty()) {
                final Status err = (errorIfAny != null) ? errorIfAny
                        : Status.UNAVAILABLE.withDescription(
                        hasLane ? ("no READY subchannel for lane=" + wanted + " (and no fallback)")
                                : "no READY subchannel for default (no-lane)");
                return PickResult.withError(err);
            }

            final String keyUsed = useWanted ? keyWanted : keyDefault;
            final AtomicInteger cursor = cursors.computeIfAbsent(keyUsed, k -> new AtomicInteger(0));
            final int idx = Math.floorMod(cursor.getAndIncrement(), candidates.size());
            return PickResult.withSubchannel(candidates.get(idx));
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