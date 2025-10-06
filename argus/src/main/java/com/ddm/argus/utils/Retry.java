package com.ddm.argus.utils;

import java.util.function.Supplier;

/**
 * 轻量重试工具：按给定次数轮询，直到校验通过或用尽次数。
 * 仅用于启动期的短暂拉取/探测场景。
 */
public class Retry {


    public static <T> T get(Supplier<T> s, java.util.function.Predicate<T> ok, int attempts, long sleepMs) throws Exception {
        T v;
        for (int i = 1; i <= attempts; i++) {
            try {
                v = s.get();
            } catch (Exception ignore) {
                v = null;
            }
            if (ok.test(v)) return v;
            if (i < attempts) Thread.sleep(sleepMs);
        }
        return null;
    }


    private Retry() {
    }
}