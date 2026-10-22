package kb.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 코스닥 매수 시뮬레이션 모듈 (sim_modules.py와 같은 이름·규칙·파일). Ticks.simulate(엔진)가 이름(buy, sell)으로 골라 끼웁니다.
 * 매수 조건은 설정(sim_buy_rules.json, 없으면 DEFAULT_BUY)의 조건 목록이라 모니터 화면에서 추가·수정합니다:
 * {id, label, all: [{left: 값, op: > >= < <=, right: 숫자 | 값 | "min"}]} (모두 만족하면 매수 조건).
 * 값 = price(현재가), change(전일 대비 %), ratio(전일 같은 시각 대비 누적 거래량 증가 %), open(시가), value(누적 거래대금),
 * vwap(거래량가중평균가 = 누적 거래대금 ÷ 누적 거래량). "min" = 화면의 거래량 기준 %.
 * 매도는 코드 모듈(SELL): 그 거래 tr(buyPrice 등, 상태를 남겨도 됨)와 현재가 px(0 아님)로 이유를 돌려주면 팔고, null이면 계속 들고 있음.
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

    /** 매수 조건 파일 (AppServlet·KbApplication이 데이터 폴더로 정함). null이면 기본 규칙만 (단위 테스트) */
    static volatile Path rulesFile;

    static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static final List<String> OPS = List.of(">", ">=", "<", "<=");
    static final int MAX_RULES = 30;
    static final int MAX_CONDS = 10;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    // surge: 10-20·10-21 -0.86%·+1.43%, surge-vwap: 10-20~10-22 알림 종목 +1.12%. surge300(화면 기본): 거래량 기준을 300%로 낮춘 surge,
    // 10-20~10-22 알림 종목 · 유지 30 · 익절 7 · 손절 5를 1분봉으로 다시 재면 10건 +1.64%. surge300은 Java 서버만 (sim_modules.py에는 없음)
    private static final String DEFAULT_BUY = "["
            + "{\"id\": \"volume-open-up\", \"label\": \"거래량 증가 + 시가 위 + 상승\", \"all\": [{\"left\": \"ratio\", \"op\": \">=\", \"right\": \"min\"},"
            + " {\"left\": \"price\", \"op\": \">\", \"right\": \"open\"}, {\"left\": \"change\", \"op\": \">\", \"right\": 0}]},"
            + "{\"id\": \"volume-up\", \"label\": \"거래량 증가 + 상승 (시가 무시)\", \"all\": [{\"left\": \"ratio\", \"op\": \">=\", \"right\": \"min\"},"
            + " {\"left\": \"change\", \"op\": \">\", \"right\": 0}]},"
            + "{\"id\": \"surge\", \"label\": \"급등 초입: 거래량 500%↑ + 등락 5~15%\", \"all\": [{\"left\": \"ratio\", \"op\": \">=\", \"right\": 500},"
            + " {\"left\": \"change\", \"op\": \">=\", \"right\": 5}, {\"left\": \"change\", \"op\": \"<\", \"right\": 15}]},"
            + "{\"id\": \"surge300\", \"label\": \"급등 초입: 거래량 300%↑ + 등락 5~15%\", \"all\": [{\"left\": \"ratio\", \"op\": \">=\", \"right\": 300},"
            + " {\"left\": \"change\", \"op\": \">=\", \"right\": 5}, {\"left\": \"change\", \"op\": \"<\", \"right\": 15}]},"
            + "{\"id\": \"surge-vwap\", \"label\": \"급등 초입 + VWAP 위\", \"all\": [{\"left\": \"ratio\", \"op\": \">=\", \"right\": 500},"
            + " {\"left\": \"change\", \"op\": \">=\", \"right\": 5}, {\"left\": \"change\", \"op\": \"<\", \"right\": 15}, {\"left\": \"price\", \"op\": \">\", \"right\": \"vwap\"}]},"
            + "{\"id\": \"vwap-up\", \"label\": \"거래량 증가 + VWAP 위 + 상승\", \"all\": [{\"left\": \"ratio\", \"op\": \">=\", \"right\": \"min\"},"
            + " {\"left\": \"price\", \"op\": \">\", \"right\": \"vwap\"}, {\"left\": \"change\", \"op\": \">\", \"right\": 0}]}]";

    static final Map<String, Module<Sell>> SELL = new LinkedHashMap<>();

    static {
        FIELDS.put("price", "현재가");
        FIELDS.put("change", "등락률 %");
        FIELDS.put("ratio", "거래량 증가율 %");
        FIELDS.put("open", "시가");
        FIELDS.put("vwap", "VWAP");
        FIELDS.put("value", "누적 거래대금");
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

    /** 매수 조건 규칙 목록 검사. 잘못되면 IllegalArgumentException (화면에 그대로 보임). */
    static List<Map<String, Object>> validRules(JsonElement rules) {
        if (rules == null || !rules.isJsonArray() || rules.getAsJsonArray().size() < 1 || rules.getAsJsonArray().size() > MAX_RULES) {
            throw new IllegalArgumentException("매수 조건은 1~" + MAX_RULES + "개입니다.");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement e : rules.getAsJsonArray()) {
            String id = e.isJsonObject() ? text(e.getAsJsonObject().get("id")) : "";
            if (!ID.matcher(id).matches() || !ids.add(id)) {
                throw new IllegalArgumentException("매수 조건 이름(id)은 영문 소문자·숫자·-로 40자까지, 서로 달라야 합니다: " + id);
            }
            String label = text(e.getAsJsonObject().get("label")).trim().replaceAll("\\s+", " ");
            label = label.isEmpty() ? id : label.substring(0, Math.min(60, label.length()));
            JsonElement all = e.getAsJsonObject().get("all");
            if (all == null || !all.isJsonArray() || all.getAsJsonArray().size() < 1 || all.getAsJsonArray().size() > MAX_CONDS) {
                throw new IllegalArgumentException(label + ": 조건은 1~" + MAX_CONDS + "개입니다.");
            }
            List<Map<String, Object>> cs = new ArrayList<>();
            for (JsonElement x : all.getAsJsonArray()) {
                String left = x.isJsonObject() ? text(x.getAsJsonObject().get("left")) : "";
                String op = x.isJsonObject() ? text(x.getAsJsonObject().get("op")) : "";
                JsonElement r = x.isJsonObject() ? x.getAsJsonObject().get("right") : null;
                Object right = null;
                if (r != null && r.isJsonPrimitive() && r.getAsJsonPrimitive().isNumber() && Double.isFinite(r.getAsDouble())) {
                    double v = r.getAsDouble();
                    right = v == Math.rint(v) && Math.abs(v) < 1e15 ? (Object) (long) v : (Object) v;  // 정수는 정수로 저장 (Python과 같은 파일)
                } else if (r != null && r.isJsonPrimitive() && r.getAsJsonPrimitive().isString()
                        && (FIELDS.containsKey(r.getAsString()) || "min".equals(r.getAsString()))) {
                    right = r.getAsString();
                }
                if (!FIELDS.containsKey(left) || !OPS.contains(op) || right == null) {
                    throw new IllegalArgumentException(label + ": 조건이 올바르지 않습니다: " + left + " " + op + " " + (r == null ? "" : r));
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("left", left);
                c.put("op", op);
                c.put("right", right);
                cs.add(c);
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("id", id);
            rule.put("label", label);
            rule.put("all", cs);
            out.add(rule);
        }
        return out;
    }

    private static String text(JsonElement v) {
        return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
    }

    /** 저장된 매수 조건 (파일이 없거나 깨졌으면 기본 규칙). */
    static List<Map<String, Object>> buyRules() {
        Path f = rulesFile;
        if (f != null) {
            try {
                return validRules(JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
            } catch (NoSuchFileException | JsonParseException | IllegalArgumentException e) {
                // 기본 규칙
            } catch (IOException e) {
                System.out.println("[시뮬레이션] " + f.getFileName() + " 읽기 실패, 기본 매수 조건을 씀: " + e.getMessage());
            }
        }
        return validRules(JsonParser.parseString(DEFAULT_BUY));
    }

    /** POST /api/kosdaq-sim-modules {"buy": [...]} : 검사해 통째로 저장하고 catalog()를 돌려줍니다. */
    static synchronized Map<String, Object> saveBuyRules(String body) throws IOException {
        JsonElement d;
        try {
            d = JsonParser.parseString(body);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("매수 조건이 올바른 JSON이 아닙니다.");
        }
        if (d == null || !d.isJsonObject()) {
            throw new IllegalArgumentException("매수 조건은 {\"buy\": [...]} 이어야 합니다.");
        }
        List<Map<String, Object>> rules = validRules(d.getAsJsonObject().get("buy"));
        Path f = rulesFile;
        if (f == null) {
            throw new IllegalStateException("매수 조건 파일 위치가 정해지지 않았습니다");
        }
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, (GSON.toJson(rules) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);  // 반쯤 쓴 파일을 읽지 않게 통째로 교체
        return catalog();
    }

    /** 이름의 매수 조건. s = 그 시각 종목 값(FIELDS), o = {min, tp, sl}. */
    @SuppressWarnings("unchecked")
    static Buy buy(String name) {
        for (Map<String, Object> rule : buyRules()) {
            if (rule.get("id").equals(name)) {
                List<Map<String, Object>> conds = (List<Map<String, Object>>) rule.get("all");
                return (s, o) -> {
                    for (Map<String, Object> c : conds) {
                        Object r = c.get("right");
                        double left = s.get((String) c.get("left"));
                        double right = r instanceof Number ? ((Number) r).doubleValue() : "min".equals(r) ? o.get("min") : s.get((String) r);
                        boolean ok;
                        switch ((String) c.get("op")) {
                            case ">": ok = left > right; break;
                            case ">=": ok = left >= right; break;
                            case "<": ok = left < right; break;
                            default: ok = left <= right;
                        }
                        if (!ok) {
                            return false;
                        }
                    }
                    return true;
                };
            }
        }
        throw new IllegalArgumentException("없는 매수 조건입니다: " + name);
    }

    static Sell sell(String name) {
        Module<Sell> m = SELL.get(name);
        if (m == null) {
            throw new IllegalArgumentException("없는 매도 모듈입니다: " + name);
        }
        return m.rule;
    }

    /** GET /api/kosdaq-sim-modules -> {buy: [{id, label, all}], sell: [{id, label}], fields: {값: 이름}, ops} (화면 선택 목록·편집) */
    static Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buy", buyRules());
        List<Map<String, Object>> sells = new ArrayList<>();
        SELL.forEach((id, m) -> sells.add(new LinkedHashMap<>(Map.of("id", id, "label", m.label))));
        out.put("sell", sells);
        out.put("fields", FIELDS);
        out.put("ops", OPS);
        return out;
    }
}
