package kb.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 코스닥 매수 시뮬레이션 모듈 (sim_modules.py와 같은 이름·규칙). Ticks.simulate(엔진)가 이름(buy, sell)으로 골라 끼웁니다.
 * 새 규칙은 BUY·SELL에 한 줄 추가하면 모니터 화면 선택 목록에 바로 나옵니다.
 * 매수 조건 s: price(현재가), change(전일 대비 %), ratio(전일 같은 시각 대비 누적 거래량 증가 %), open(시가). o: min, tp, sl (화면 옵션).
 * 매도: 그 거래 tr(buyPrice 등, 상태를 남겨도 됨)와 현재가 px(0 아님)로 이유를 돌려주면 팔고, null이면 계속 들고 있음.
 */
final class SimModules {
    interface Buy {
        boolean test(Map<String, Double> s, Map<String, Double> o);
    }

    interface Sell {
        String check(Map<String, Object> tr, double px, Map<String, Double> o);
    }

    static final class Module<F> {
        final String label;
        final F rule;

        Module(String label, F rule) {
            this.label = label;
            this.rule = rule;
        }
    }

    static final Map<String, Module<Buy>> BUY = new LinkedHashMap<>();
    static final Map<String, Module<Sell>> SELL = new LinkedHashMap<>();

    static {
        BUY.put("volume-open-up", new Module<Buy>("거래량 증가 + 시가 위 + 상승",
                (s, o) -> s.get("ratio") >= o.get("min") && s.get("price") > s.get("open") && s.get("change") > 0));
        BUY.put("volume-up", new Module<Buy>("거래량 증가 + 상승 (시가 무시)", (s, o) -> s.get("ratio") >= o.get("min") && s.get("change") > 0));
        SELL.put("tp-sl", new Module<Sell>("익절·손절, 아니면 마지막 기록", SimModules::tpSl));
        SELL.put("trailing", new Module<Sell>("익절 · 고점 대비 손절(추적), 아니면 마지막 기록", SimModules::trailing));
    }

    private SimModules() {
    }

    static String tpSl(Map<String, Object> tr, double px, Map<String, Double> o) {
        double buy = (double) tr.get("buyPrice");
        if (o.get("tp") > 0 && px >= buy * (1 + o.get("tp") / 100)) {
            return "익절";
        }
        return o.get("sl") > 0 && px <= buy * (1 - o.get("sl") / 100) ? "손절" : null;
    }

    static String trailing(Map<String, Object> tr, double px, Map<String, Double> o) {
        double buy = (double) tr.get("buyPrice");
        double peak = Math.max(tr.containsKey("peak") ? (double) tr.get("peak") : buy, px);  // 산 뒤 기록 중 최고 현재가
        tr.put("peak", peak);
        if (o.get("tp") > 0 && px >= buy * (1 + o.get("tp") / 100)) {
            return "익절";
        }
        return o.get("sl") > 0 && px <= peak * (1 - o.get("sl") / 100) ? "고점 대비 손절" : null;
    }

    static <F> F pick(Map<String, Module<F>> modules, String name) {
        Module<F> m = modules.get(name);
        if (m == null) {
            throw new IllegalArgumentException("없는 시뮬레이션 모듈입니다: " + name);
        }
        return m.rule;
    }

    /** GET /api/kosdaq-sim-modules -> {buy: [{id, label}], sell: [...]} (화면 선택 목록, 첫 항목이 기본) */
    static Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buy", list(BUY));
        out.put("sell", list(SELL));
        return out;
    }

    private static List<Map<String, Object>> list(Map<String, ? extends Module<?>> modules) {
        List<Map<String, Object>> out = new ArrayList<>();
        modules.forEach((id, m) -> out.add(new LinkedHashMap<>(Map.of("id", id, "label", m.label))));
        return out;
    }
}
