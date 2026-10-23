package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** signals.js, signals.py 자체 점검과 같은 입력·같은 결과. */
class SignalsTest {

    private static List<Map<String, Object>> mk(double[] closes) {
        List<Map<String, Object>> cs = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("d", String.valueOf(20260101 + i));
            c.put("c", closes[i]);
            cs.add(c);
        }
        return cs;
    }

    private static List<String> texts(double[] closes) {
        return Signals.of(mk(closes)).stream().map(g -> ((Integer) g.get("dir") > 0 ? "+" : "-") + g.get("text")).collect(Collectors.toList());
    }

    private static double[] concat(double[] a, double... b) {
        double[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void sameSignalsAsChartPage() {
        double[] up = concat(IntStream.range(0, 150).mapToDouble(i -> 200 - i * 0.5).toArray(), 140, 150, 165);
        assertEquals(Arrays.asList("+5·20일선 골든크로스", "-RSI 과매수 87", "-볼린저 상단 돌파 (과열)"), texts(up));
        List<String> rise = texts(IntStream.range(0, 200).mapToDouble(i -> 100 + i).toArray());
        assertTrue(rise.contains("+이평선 정배열") && rise.stream().anyMatch(t -> t.startsWith("-RSI 과매수")));
        assertFalse(rise.stream().anyMatch(t -> t.contains("크로스")));
        List<String> fall = texts(IntStream.range(0, 200).mapToDouble(i -> 300 - i).toArray());
        assertTrue(fall.contains("-이평선 역배열") && fall.stream().anyMatch(t -> t.startsWith("+RSI 과매도")));
        double[] down = concat(IntStream.range(0, 150).mapToDouble(i -> 100 + i * 0.5).toArray(), 160, 150, 135);
        assertTrue(texts(down).stream().anyMatch(t -> t.startsWith("-5·20일선 데드크로스")));
        assertTrue(Signals.of(mk(new double[]{1, 2, 3})).isEmpty());
    }

    @Test
    void rsiMatchesStockChartsExample() {
        double[] xs = {44.3389, 44.0902, 44.1497, 43.6124, 44.3278, 44.8264, 45.0955, 45.4245, 45.8433, 46.0826, 45.8931, 46.0328, 45.6140,
                46.2820, 46.2820, 46.0028, 46.0328, 46.4116, 46.2222};
        Double[] r = Signals.rsi(xs, 14);
        assertEquals(Arrays.asList("70.53", "66.32", "66.55", "69.41", "66.36"),
                Arrays.stream(r, 14, 19).map(v -> String.format("%.2f", v)).collect(Collectors.toList()));
    }
}
