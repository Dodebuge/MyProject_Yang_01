package kb.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기술적 시그널 (signals.js, signals.py와 같은 규칙·같은 결과). 서버가 시그널 알림(Alerts)을 계산할 때 씁니다.
 * 화면(차트·거래량)은 signals.js로 계산하고, 브라우저 없이 서버가 계산해야 하는 알림만 이 클래스를 씁니다.
 * 마지막 날 기준. 교차는 최근 SIG_DAYS 거래일 안에 일어난 것만. dir: 1 매수 쪽(상승), -1 매도 쪽
 */
final class Signals {

    private static final int[] MAS = {5, 20, 60, 120};
    private static final int BB_N = 20;
    private static final double BB_K = 2;
    private static final int RSI_N = 14;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int SIG_DAYS = 5;

    private Signals() {
    }

    /** n일 단순이동평균. 앞쪽 n-1일은 null */
    static Double[] sma(double[] xs, int n) {
        Double[] out = new Double[xs.length];
        double s = 0;
        for (int i = 0; i < xs.length; i++) {
            s += xs[i] - (i >= n ? xs[i - n] : 0);
            out[i] = i >= n - 1 ? s / n : null;
        }
        return out;
    }

    /** n일 지수이동평균. 앞쪽 null은 건너뛰고, 첫 값은 처음 n개의 단순평균으로 시작 */
    static Double[] ema(Double[] xs, int n) {
        Double[] out = new Double[xs.length];
        int start = 0;
        while (start < xs.length && xs[start] == null) {
            start++;
        }
        if (start >= xs.length || start + n > xs.length) {
            return out;
        }
        double k = 2.0 / (n + 1);
        double e = 0;
        for (int i = start; i < start + n; i++) {
            e += xs[i];
        }
        e /= n;
        out[start + n - 1] = e;
        for (int i = start + n; i < xs.length; i++) {
            e = xs[i] * k + e * (1 - k);
            out[i] = e;
        }
        return out;
    }

    /** RSI(n): 상승폭 평균 / (상승폭 평균 + 하락폭 평균) × 100. 와일더 방식 평균 */
    static Double[] rsi(double[] xs, int n) {
        Double[] out = new Double[xs.length];
        double up = 0;
        double down = 0;
        for (int i = 1; i < xs.length; i++) {
            double d = xs[i] - xs[i - 1];
            double u = Math.max(d, 0);
            double w = Math.max(-d, 0);
            if (i <= n) {
                up += u / n;
                down += w / n;
            } else {
                up = (up * (n - 1) + u) / n;
                down = (down * (n - 1) + w) / n;
            }
            if (i >= n) {
                out[i] = up + down != 0 ? up / (up + down) * 100 : 50.0;
            }
        }
        return out;
    }

    /** {macd[], signal[]} (앞쪽은 null). MACD = 빠른 EMA - 느린 EMA, 시그널 = MACD의 EMA */
    static Double[][] macd(double[] xs, int f, int sl, int sg) {
        Double[] boxed = Arrays.stream(xs).boxed().toArray(Double[]::new);
        Double[] a = ema(boxed, f);
        Double[] b = ema(boxed, sl);
        Double[] m = new Double[xs.length];
        for (int i = 0; i < xs.length; i++) {
            m[i] = a[i] == null || b[i] == null ? null : a[i] - b[i];
        }
        return new Double[][]{m, ema(m, sg)};
    }

    /** 마지막 날의 {중심, 상단, 하단} (표준편차는 모집단). 데이터가 모자라면 null */
    static double[] bollingerLast(double[] xs, int n, double k) {
        int last = xs.length - 1;
        Double m = sma(xs, n)[last];
        if (m == null) {
            return null;
        }
        double ss = 0;
        for (int i = last - n + 1; i <= last; i++) {
            ss += (xs[i] - m) * (xs[i] - m);
        }
        double sd = Math.sqrt(ss / n);
        return new double[]{m, m + k * sd, m - k * sd};
    }

    /** 일봉 [{"d", "c"}](오래된 날부터) -> [{"dir", "text", "d"?}] (signals.js signalsOf와 같은 순서·문구) */
    static List<Map<String, Object>> of(List<Map<String, Object>> cs) {
        double[] xs = cs.stream().mapToDouble(c -> Values.num(c.get("c"))).toArray();
        int last = xs.length - 1;
        List<Map<String, Object>> out = new ArrayList<>();
        if (xs.length < 30) {
            return out;
        }
        Map<Integer, Double[]> ma = new LinkedHashMap<>();
        for (int n : MAS) {
            ma.put(n, sma(xs, n));
        }
        for (int[] p : new int[][]{{5, 20}, {20, 60}}) {
            Map<String, Object> c = cross(cs, ma.get(p[0]), ma.get(p[1]));
            if (c != null) {
                c.put("text", p[0] + "·" + p[1] + "일선 " + ((Integer) c.get("dir") > 0 ? "골든" : "데드") + "크로스");
                out.add(c);
            }
        }
        Double[][] m = macd(xs, MACD_FAST, MACD_SLOW, MACD_SIGNAL);
        Map<String, Object> mc = cross(cs, m[0], m[1]);
        if (mc != null) {
            mc.put("text", "MACD 시그널 " + ((Integer) mc.get("dir") > 0 ? "상향" : "하향") + " 돌파");
            out.add(mc);
        }
        Double r = rsi(xs, RSI_N)[last];
        if (r != null && r >= 70) {
            out.add(signal(-1, "RSI 과매수 " + Math.round(r)));
        } else if (r != null && r <= 30) {
            out.add(signal(1, "RSI 과매도 " + Math.round(r)));
        }
        double[] b = bollingerLast(xs, BB_N, BB_K);
        if (b != null && xs[last] > b[1]) {
            out.add(signal(-1, "볼린저 상단 돌파 (과열)"));
        } else if (b != null && xs[last] < b[2]) {
            out.add(signal(1, "볼린저 하단 이탈 (침체)"));
        }
        Double a5 = ma.get(5)[last];
        Double a20 = ma.get(20)[last];
        Double a60 = ma.get(60)[last];
        Double a120 = ma.get(120)[last];
        if (a120 != null && a5 > a20 && a20 > a60 && a60 > a120) {
            out.add(signal(1, "이평선 정배열"));
        } else if (a120 != null && a5 < a20 && a20 < a60 && a60 < a120) {
            out.add(signal(-1, "이평선 역배열"));
        }
        return out;
    }

    /** a가 b를 위로 뚫으면 dir 1, 아래로 뚫으면 -1 (최근 SIG_DAYS 거래일 안의 가장 최근 교차). 없으면 null */
    private static Map<String, Object> cross(List<Map<String, Object>> cs, Double[] a, Double[] b) {
        int last = a.length - 1;
        for (int i = last; i > Math.max(0, last - SIG_DAYS); i--) {
            if (a[i] == null || b[i] == null || a[i - 1] == null || b[i - 1] == null) {
                return null;
            }
            if (a[i - 1] <= b[i - 1] && a[i] > b[i]) {
                return crossed(1, cs.get(i));
            }
            if (a[i - 1] >= b[i - 1] && a[i] < b[i]) {
                return crossed(-1, cs.get(i));
            }
        }
        return null;
    }

    private static Map<String, Object> crossed(int dir, Map<String, Object> candle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", dir);
        m.put("d", Values.str(candle.get("d")));
        return m;
    }

    private static Map<String, Object> signal(int dir, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", dir);
        m.put("text", text);
        return m;
    }
}
