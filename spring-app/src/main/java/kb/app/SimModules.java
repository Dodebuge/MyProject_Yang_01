package kb.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 코스닥 매수 시뮬레이션 모듈 (sim_modules.py와 같은 이름·규칙·파일). Ticks.run(엔진)이 이름(buy, sell)으로 골라 씁니다.
 * C처럼 씁니다: 매수 조건은 struct Rule { id, label, Cond all[] } 배열이고, 값은 번호(PRICE, CHANGE …)로 찾는 double 배열.
 *
 * 매수 조건은 설정(sim_buy_rules.json, 없으면 DEFAULT_BUY)이라 모니터 화면에서 추가·수정합니다:
 * {id, label, all: [{left: 값, op: > >= < <=, right: 숫자 | 값 | "min"}]} (모두 만족하면 매수 조건).
 * 값 = price(현재가), change(전일 대비 %), ratio(전일 같은 시각 대비 누적 거래량 증가 %), open(시가), vwap(거래량가중평균가), value(누적 거래대금).
 * "min" = 화면의 거래량 기준 %.
 * 매도는 함수 테이블(SELL_FN): 거래(Ticks.Trade)와 지금 가격으로 이유를 돌려주면 팔고, null이면 계속 들고 있음.
 */
final class SimModules {

    private SimModules() {
    }

    // ------------------------------------------------------------------ 값 번호 (C의 enum field)

    static final int PRICE = 0;
    static final int CHANGE = 1;
    static final int RATIO = 2;
    static final int OPEN = 3;
    static final int VWAP = 4;
    static final int VALUE = 5;
    static final int NFIELD = 6;
    static final String[] FIELD_ID = {"price", "change", "ratio", "open", "vwap", "value"};
    static final String[] FIELD_NAME = {"현재가", "등락률 %", "거래량 증가율 %", "시가", "VWAP", "누적 거래대금"};

    // 비교 연산 (enum op)
    static final int GT = 0;
    static final int GE = 1;
    static final int LT = 2;
    static final int LE = 3;
    static final String[] OP_ID = {">", ">=", "<", "<="};

    // 오른쪽 값의 종류 (enum operand_kind)
    static final int R_NUM = 0;
    static final int R_FIELD = 1;
    static final int R_MIN = 2;

    /** 조건 하나: s[left] op 오른쪽 */
    static final class Cond {
        int left;
        int op;
        int rkind;
        double rnum;   // rkind == R_NUM
        int rfield;    // rkind == R_FIELD
    }

    /** 매수 조건 하나: all[]을 모두 만족하면 참 */
    static final class Rule {
        String id;
        String label;
        Cond[] all;
    }

    static final int MAX_RULES = 30;
    static final int MAX_CONDS = 10;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 매수 조건 파일 (AppConfig가 데이터 폴더로 정함). null이면 기본 규칙만 (단위 테스트) */
    static volatile Path rulesFile;

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

    // ------------------------------------------------------------------ 매수 조건 검사

    /** s = 그 시각 그 종목의 값 (FIELD 번호로), min = 화면의 거래량 기준 %. 조건을 모두 만족하면 true */
    static boolean test(Rule r, double[] s, double min) {
        for (int i = 0; i < r.all.length; i++) {
            Cond c = r.all[i];
            double left = s[c.left];
            double right;
            if (c.rkind == R_NUM) {
                right = c.rnum;
            } else if (c.rkind == R_MIN) {
                right = min;
            } else {
                right = s[c.rfield];
            }
            boolean ok;
            switch (c.op) {
                case GT: ok = left > right; break;
                case GE: ok = left >= right; break;
                case LT: ok = left < right; break;
                default: ok = left <= right;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 매도 모듈 (함수 테이블)

    interface Sell {
        String check(Ticks.Trade tr, double px, double tp, double sl);
    }

    static String tpSl(Ticks.Trade tr, double px, double tp, double sl) {
        if (tp > 0 && px >= tr.buyPrice * (1 + tp / 100)) {
            return "익절";
        }
        if (sl > 0 && px <= tr.buyPrice * (1 - sl / 100)) {
            return "손절";
        }
        return null;
    }

    static String trailing(Ticks.Trade tr, double px, double tp, double sl) {
        tr.peak = Math.max(tr.peak > 0 ? tr.peak : tr.buyPrice, px);  // 산 뒤 기록 중 최고 현재가
        if (tp > 0 && px >= tr.buyPrice * (1 + tp / 100)) {
            return "익절";
        }
        if (sl > 0 && px <= tr.peak * (1 - sl / 100)) {
            return "고점 대비 손절";
        }
        return null;
    }

    static final String[] SELL_ID = {"tp-sl", "trailing"};
    static final String[] SELL_LABEL = {"익절·손절, 아니면 마지막 기록", "익절 · 고점 대비 손절(추적), 아니면 마지막 기록"};
    static final Sell[] SELL_FN = {SimModules::tpSl, SimModules::trailing};

    static Sell sell(String name) {
        for (int i = 0; i < SELL_ID.length; i++) {
            if (SELL_ID[i].equals(name)) {
                return SELL_FN[i];
            }
        }
        throw new IllegalArgumentException("없는 매도 모듈입니다: " + name);
    }

    // ------------------------------------------------------------------ 매수 조건 파일

    private static int indexOf(String[] names, String v) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(v)) {
                return i;
            }
        }
        return -1;
    }

    private static String text(JsonElement v) {
        return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
    }

    /** JSON 규칙 목록 -> Rule 배열. 잘못되면 IllegalArgumentException (화면에 그대로 보임). */
    static Rule[] parse(JsonElement json) {
        if (json == null || !json.isJsonArray() || json.getAsJsonArray().size() < 1 || json.getAsJsonArray().size() > MAX_RULES) {
            throw new IllegalArgumentException("매수 조건은 1~" + MAX_RULES + "개입니다.");
        }
        JsonArray arr = json.getAsJsonArray();
        Rule[] out = new Rule[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).isJsonObject() ? arr.get(i).getAsJsonObject() : new JsonObject();
            Rule r = new Rule();
            r.id = text(o.get("id"));
            boolean dup = false;
            for (int k = 0; k < i; k++) {
                dup |= out[k].id.equals(r.id);
            }
            if (!ID.matcher(r.id).matches() || dup) {
                throw new IllegalArgumentException("매수 조건 이름(id)은 영문 소문자·숫자·-로 40자까지, 서로 달라야 합니다: " + r.id);
            }
            String label = text(o.get("label")).trim().replaceAll("\\s+", " ");
            r.label = label.isEmpty() ? r.id : label.substring(0, Math.min(60, label.length()));
            JsonElement all = o.get("all");
            if (all == null || !all.isJsonArray() || all.getAsJsonArray().size() < 1 || all.getAsJsonArray().size() > MAX_CONDS) {
                throw new IllegalArgumentException(r.label + ": 조건은 1~" + MAX_CONDS + "개입니다.");
            }
            r.all = new Cond[all.getAsJsonArray().size()];
            for (int j = 0; j < r.all.length; j++) {
                JsonElement x = all.getAsJsonArray().get(j);
                JsonObject xo = x.isJsonObject() ? x.getAsJsonObject() : new JsonObject();
                Cond c = new Cond();
                c.left = indexOf(FIELD_ID, text(xo.get("left")));
                c.op = indexOf(OP_ID, text(xo.get("op")));
                c.rkind = -1;
                JsonElement right = xo.get("right");
                if (right != null && right.isJsonPrimitive()) {
                    JsonPrimitive p = right.getAsJsonPrimitive();
                    if (p.isNumber() && Double.isFinite(p.getAsDouble())) {
                        c.rkind = R_NUM;
                        c.rnum = p.getAsDouble();
                    } else if (p.isString() && "min".equals(p.getAsString())) {
                        c.rkind = R_MIN;
                    } else if (p.isString() && indexOf(FIELD_ID, p.getAsString()) >= 0) {
                        c.rkind = R_FIELD;
                        c.rfield = indexOf(FIELD_ID, p.getAsString());
                    }
                }
                if (c.left < 0 || c.op < 0 || c.rkind < 0) {
                    throw new IllegalArgumentException(r.label + ": 조건이 올바르지 않습니다: " + text(xo.get("left")) + " " + text(xo.get("op"))
                            + " " + (right == null ? "" : right));
                }
                r.all[j] = c;
            }
            out[i] = r;
        }
        return out;
    }

    /** Rule -> 파일·화면 JSON {id, label, all: [{left, op, right}]}. 정수는 정수로 (Python과 같은 파일) */
    static Map<String, Object> toJson(Rule r) {
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Cond c : r.all) {
            Object right;
            if (c.rkind == R_NUM) {
                right = c.rnum == Math.rint(c.rnum) && Math.abs(c.rnum) < 1e15 ? (Object) (long) c.rnum : (Object) c.rnum;
            } else if (c.rkind == R_MIN) {
                right = "min";
            } else {
                right = FIELD_ID[c.rfield];
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("left", FIELD_ID[c.left]);
            m.put("op", OP_ID[c.op]);
            m.put("right", right);
            cs.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("label", r.label);
        m.put("all", cs);
        return m;
    }

    /** 저장된 매수 조건 (파일이 없거나 깨졌으면 기본 규칙). */
    static Rule[] rules() {
        Path f = rulesFile;
        if (f != null) {
            try {
                return parse(JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
            } catch (NoSuchFileException | JsonParseException | IllegalArgumentException e) {
                // 기본 규칙
            } catch (IOException e) {
                System.out.println("[시뮬레이션] " + f.getFileName() + " 읽기 실패, 기본 매수 조건을 씀: " + e.getMessage());
            }
        }
        return parse(JsonParser.parseString(DEFAULT_BUY));
    }

    /** 이름의 매수 조건 */
    static Rule buy(String name) {
        for (Rule r : rules()) {
            if (r.id.equals(name)) {
                return r;
            }
        }
        throw new IllegalArgumentException("없는 매수 조건입니다: " + name);
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
        Rule[] rules = parse(d.getAsJsonObject().get("buy"));
        Path f = rulesFile;
        if (f == null) {
            throw new IllegalStateException("매수 조건 파일 위치가 정해지지 않았습니다");
        }
        List<Map<String, Object>> json = new ArrayList<>();
        for (Rule r : rules) {
            json.add(toJson(r));
        }
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);  // 반쯤 쓴 파일을 읽지 않게 통째로 교체
        return catalog();
    }

    /** GET /api/kosdaq-sim-modules -> {buy: [{id, label, all}], sell: [{id, label}], fields: {값: 이름}, ops} (화면 선택 목록·편집) */
    static Map<String, Object> catalog() {
        List<Map<String, Object>> buys = new ArrayList<>();
        for (Rule r : rules()) {
            buys.add(toJson(r));
        }
        List<Map<String, Object>> sells = new ArrayList<>();
        for (int i = 0; i < SELL_ID.length; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", SELL_ID[i]);
            m.put("label", SELL_LABEL[i]);
            sells.add(m);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < NFIELD; i++) {
            fields.put(FIELD_ID[i], FIELD_NAME[i]);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buy", buys);
        out.put("sell", sells);
        out.put("fields", fields);
        out.put("ops", List.of(OP_ID));
        return out;
    }
}
