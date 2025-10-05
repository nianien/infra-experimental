package com.ddm.hermes.aws;

final class Retry {

    interface SupplierX<T> {
        T get() throws Exception;
    }

    interface RunnableX {
        void run() throws Exception;
    }

    static <T> T get(SupplierX<T> s, java.util.function.Predicate<T> ok,
                     int attempts, long sleepMs) throws Exception {
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