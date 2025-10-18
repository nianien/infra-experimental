package com.ddm.argus.utils;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 轻量重试工具：按给定次数轮询，直到校验通过或用尽次数。
 * - 支持固定间隔或指数退避（可选抖动）
 * - 正确处理中断（恢复中断标志）
 * - 提供不抛异常与可抛异常的 Supplier 版本
 */
public final class Retry {

    private Retry() {}

    /* ======================= 基础固定间隔 ======================= */

    /** 不抛异常版本：返回 Optional，空表示失败 */
    public static <T> Optional<T> get(
            Supplier<T> supplier,
            Predicate<T> ok,
            int attempts,
            Duration interval
    ) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(ok, "ok");
        if (attempts <= 0) return Optional.empty();
        long sleepMs = Math.max(0, interval == null ? 0 : interval.toMillis());

        for (int i = 1; i <= attempts; i++) {
            T v;
            try {
                v = supplier.get();
            } catch (RuntimeException e) {
                // 保持"轻量探测"语义：把异常视作失败一次
                v = null;
            }
            if (ok.test(v)) return Optional.ofNullable(v);

            if (i < attempts && sleepMs > 0) {
                sleepQuietly(sleepMs);
            }
        }
        return Optional.empty();
    }

    /* ======================= 指数退避 + 抖动（可选） ======================= */

    /** 指数退避：base * 2^(i-1)，可加抖动百分比（0~1） */
    public static <T> Optional<T> getWithBackoff(
            Supplier<T> supplier,
            Predicate<T> ok,
            int attempts,
            Duration baseInterval,
            double jitterRatio   // 0：无抖动；0.2：±20% 抖动
    ) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(ok, "ok");
        if (attempts <= 0) return Optional.empty();

        long baseMs = Math.max(0, baseInterval == null ? 0 : baseInterval.toMillis());
        double jr = Math.max(0, Math.min(1, jitterRatio));

        for (int i = 1; i <= attempts; i++) {
            T v;
            try {
                v = supplier.get();
            } catch (RuntimeException e) {
                v = null;
            }
            if (ok.test(v)) return Optional.ofNullable(v);

            if (i < attempts && baseMs > 0) {
                long exp = baseMs << (i - 1);           // base * 2^(i-1)
                long sleep = applyJitter(exp, jr);
                sleepQuietly(sleep);
            }
        }
        return Optional.empty();
    }

    /* ======================= 可抛异常 Supplier 版本 ======================= */

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * 可抛异常版本：
     * - 所有尝试都失败时，返回 Optional.empty()，不会把最后一次异常抛出（保持"探测"定位）。
     * - 如果你想在最终失败时抛异常，可自行在外层判断 Optional 为空后抛。
     */
    public static <T> Optional<T> getThrowing(
            ThrowingSupplier<T> supplier,
            Predicate<T> ok,
            int attempts,
            Duration interval
    ) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(ok, "ok");
        if (attempts <= 0) return Optional.empty();
        long sleepMs = Math.max(0, interval == null ? 0 : interval.toMillis());

        for (int i = 1; i <= attempts; i++) {
            T v;
            try {
                v = supplier.get();
            } catch (Exception e) {
                v = null; // 视作失败一次
            }
            if (ok.test(v)) return Optional.ofNullable(v);

            if (i < attempts && sleepMs > 0) {
                sleepQuietly(sleepMs);
            }
        }
        return Optional.empty();
    }

    /* ======================= helpers ======================= */

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            // 恢复中断语义：把标志位重新设回去，并尽快返回
            Thread.currentThread().interrupt();
        }
    }

    private static long applyJitter(long baseMs, double jitterRatio) {
        if (baseMs <= 0 || jitterRatio <= 0) return baseMs;
        long delta = (long) (baseMs * jitterRatio);
        long min = Math.max(0, baseMs - delta);
        long max = baseMs + delta;
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /* ======================= 便捷重载 ======================= */

    public static <T> Optional<T> get(Supplier<T> s, Predicate<T> ok, int attempts) {
        return get(s, ok, attempts, Duration.ofMillis(200));
    }

    public static <T> Optional<T> get(Supplier<T> s, Predicate<T> ok) {
        return get(s, ok, 5, Duration.ofMillis(200));
    }
}