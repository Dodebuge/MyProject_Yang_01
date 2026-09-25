package kb.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/** 정리된 응답 값(Long, Double, String, null)을 다루는 도우미. Python의 `int(x or 0)`, `float(x or 0)`에 해당합니다. */
final class Values {

    private Values() {
    }

    static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 숫자 또는 숫자 문자열("1,073,000" 포함). 비었거나 숫자가 아니면 0. */
    static double num(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        String s = str(v).replace(",", "").trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 숫자로 읽을 수 없으면 null (깨진 값 거르기용). */
    static Double numOrNull(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        String s = str(v).trim();
        if (s.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static long lng(Object v) {
        return Math.round(num(v));
    }

    /** 병렬 실행. fn 안에서 예외를 처리해야 합니다(처리하지 않은 예외는 RuntimeException으로 다시 던짐). */
    static <T, R> List<R> parallel(List<T> items, int workers, Function<T, R> fn) {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<R>> futures = new ArrayList<>();
            for (T item : items) {
                futures.add(pool.submit(() -> fn.apply(item)));
            }
            List<R> results = new ArrayList<>();
            for (Future<R> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new RuntimeException(e.getCause());
        } finally {
            pool.shutdown();
        }
    }
}
