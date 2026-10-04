package kb.app;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 배당 내역과 소수점 정기 구매 내역 (dividends.py + dividends_web.query_dividends, query_recurring).
 * 계좌 거래내역(SWQA2301)에서 배당 관련 거래, 소수단위매수 거래를 골라 원화로 환산합니다.
 */
final class Dividends {

    static final String DIVIDEND = "배당금 입금";
    static final String FOREIGN_TAX = "해외원천세 출금";
    static final String DOMESTIC_TAX = "배당세금추징 출금";
    static final String TAX_REFUND = "해외원천세 환급 입금";
    private static final Set<String> RELEVANT = new HashSet<>(Arrays.asList(DIVIDEND, FOREIGN_TAX, DOMESTIC_TAX, TAX_REFUND));
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final KBClient client;
    private final DailyStore store;

    Dividends(KBClient client, DailyStore store) {
        this.client = client;
        this.store = store;
    }

    /**
     * GET /api/dividends?year= : 연도별로 하루 한 번만 KB를 조회해 kb.db에 저장하고, 같은 날은 저장된 값을 돌려줍니다.
     * refresh(?refresh=1)면 지금 다시 조회해 오늘 값을 덮어씁니다.
     */
    Map<String, Object> query(int year, boolean refresh) throws IOException {
        LocalDate today = LocalDate.now();
        if (year < 2000 || year > today.getYear()) {
            throw new IllegalArgumentException("조회할 수 없는 연도입니다: " + year);
        }
        String start = year + "0101";
        String end = year == today.getYear() ? today.format(YMD) : year + "1231";
        return store.daily("dividends:" + year, refresh, () -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("year", year);
            payload.put("start", start);
            payload.put("end", end);
            payload.put("fetchedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            payload.put("entries", fetchEntries(start, end));
            return payload;
        });
    }

    List<Map<String, Object>> fetchEntries(String start, String end) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strt_dt", start);
        body.put("end_dt", end);
        body.put("is_no", "");
        body.put("srt_clsf", "");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> t : client.callPages("SWQA2301", body, "Record1", 1000)) {
            Map<String, Object> e = toEntry(t);
            if (e != null) {
                entries.add(e);
            }
        }
        entries.sort(Comparator.comparing(e -> (String) e.get("date")));
        return entries;
    }

    // ------------------------------------------------------------------ 정기 구매 (소수점 모으기)

    static final String FRACTIONAL_BUY = "A23";  // 소수단위매수: KB 해외주식 소수점 정기 구매가 체결될 때마다 남는 거래
    static final String FX_BUY = "A69";          // 글로벌원마켓플러스외화매수 출금: 같은 날 원화 -> 달러 환전 (exch_r = 그날 환율)
    static final int RECURRING_DAYS = 180;       // 조회 기간 (거래내역이 많으면 오래 걸려 반년으로 제한)

    /**
     * GET /api/recurring : KB 소수점 정기 구매 내역 (거래내역 소수단위매수, 최근 반년). 하루 한 번만 조회해 kb.db에 저장합니다.
     * 토스 OpenAPI에는 정기 구매·거래내역 API가 없어 토스 계좌는 포함하지 않습니다.
     */
    Map<String, Object> queryRecurring(boolean refresh) throws IOException {
        return store.daily("recurring", refresh, () -> {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(RECURRING_DAYS);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("strt_dt", start.format(YMD));
            body.put("end_dt", end.format(YMD));
            body.put("is_no", "");
            body.put("srt_clsf", "");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("start", start.toString());
            payload.put("end", end.toString());
            payload.put("fetchedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            payload.put("plans", summarizeRecurring(client.callPages("SWQA2301", body, "Record1", 1000), end));
            return payload;
        });
    }

    /**
     * 소수단위매수만 골라 종목별 정기 구매 요약 (dividends.summarize_recurring과 같은 계산). 최근 구매일 순.
     * 주기 = 구매일 간격의 중앙값 (3일 이하 매일, 10일 이하 매주, 그 밖은 매월).
     * 원화 = 달러 × 같은 날 환전(A69) 환율의 평균, 그날 환전이 없으면 가장 가까운 이전 날의 평균.
     */
    static List<Map<String, Object>> summarizeRecurring(List<Map<String, Object>> rows, LocalDate today) {
        Map<String, double[]> fxSum = new LinkedHashMap<>();  // 날짜 -> {환율 합, 건수}
        Map<String, List<Map<String, Object>>> by = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String type = Values.str(r.get("smry_typ_cd"));
            if (type.equals(FX_BUY) && Values.num(r.get("exch_r")) != 0) {
                double[] acc = fxSum.computeIfAbsent(Values.str(r.get("dl_dt")), k -> new double[2]);
                acc[0] += Values.num(r.get("exch_r"));
                acc[1]++;
            } else if (type.equals(FRACTIONAL_BUY)) {
                String code = Values.str(r.get("stnd_is_cd"));
                by.computeIfAbsent(code.isEmpty() ? Values.str(r.get("is_nm")) : code, k -> new ArrayList<>()).add(r);
            }
        }
        TreeMap<String, Double> rates = new TreeMap<>();
        fxSum.forEach((d, acc) -> rates.put(d, acc[0] / acc[1]));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : by.entrySet()) {
            List<Map<String, Object>> rs = e.getValue();
            rs.sort(Comparator.comparing(r -> Values.str(r.get("dl_dt"))));
            List<LocalDate> days = new ArrayList<>(new TreeSet<>(rs.stream().map(r -> LocalDate.parse(Values.str(r.get("dl_dt")), YMD)).collect(Collectors.toList())));
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < days.size(); i++) {
                gaps.add(ChronoUnit.DAYS.between(days.get(i - 1), days.get(i)));
            }
            Collections.sort(gaps);
            Long gap = gaps.isEmpty() ? null : gaps.get(gaps.size() / 2);
            double usd = 0;
            double usd30 = 0;
            double krw = 0;
            for (Map<String, Object> r : rs) {
                String d = Values.str(r.get("dl_dt"));
                double u = Values.num(r.get("fcrncy_amt"));  // 체결금액 + 해외 수수료
                Map.Entry<String, Double> rate = rates.floorEntry(d);
                usd += u;
                krw += u * (rate != null ? rate.getValue() : rates.isEmpty() ? 0 : rates.firstEntry().getValue());
                if (ChronoUnit.DAYS.between(LocalDate.parse(d, YMD), today) < 30) {
                    usd30 += u;
                }
            }
            LocalDate last = days.get(days.size() - 1);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("code", e.getKey());
            p.put("name", Values.str(rs.get(rs.size() - 1).get("is_nm")));
            p.put("count", rs.size());
            p.put("freq", gap == null ? null : gap <= 3 ? "매일" : gap <= 10 ? "매주" : "매월");
            p.put("first", days.get(0).toString());
            p.put("last", last.toString());
            p.put("daysAgo", ChronoUnit.DAYS.between(last, today));
            p.put("lastUsd", Values.num(rs.get(rs.size() - 1).get("fcrncy_amt")));
            p.put("usd", Math.round(usd * 100) / 100.0);
            p.put("krw", Math.round(krw));
            p.put("usd30", Math.round(usd30 * 100) / 100.0);
            out.add(p);
        }
        out.sort(Comparator.comparing((Map<String, Object> p) -> (String) p.get("last")).reversed());
        return out;
    }

    /** '1,073,000', 1073000, '' 모두 원 단위 정수로. */
    private static long won(Object v) {
        return Values.lng(v);
    }

    static Map<String, Object> toEntry(Map<String, Object> t) {
        String kind = Values.str(t.get("smry_nm"));
        if (!RELEVANT.contains(kind)) {
            return null;
        }
        String currency = Values.str(t.get("crncy_clsf_nm"));
        if (currency.isEmpty()) {
            currency = "KRW";
        }
        double fxAmount = Values.num(t.get("fcrncy_amt"));
        double fxRate = Values.num(t.get("exch_r"));
        long krwOfFx = Math.round(fxAmount * fxRate);
        long gross = 0;
        long tax = 0;

        if (kind.equals(DIVIDEND)) {
            if (currency.equals("KRW")) {
                gross = won(t.get("dl_amt"));
                tax = won(t.get("tx"));
            } else {
                gross = krwOfFx;
            }
        } else if (kind.equals(FOREIGN_TAX)) {
            tax = krwOfFx;
        } else if (kind.equals(TAX_REFUND)) {
            tax = -krwOfFx;
        } else {  // DOMESTIC_TAX
            currency = "KRW";
            tax = won(t.get("tx")) != 0 ? won(t.get("tx")) : won(t.get("dl_amt"));
        }

        String date = Values.str(t.get("dl_dt"));
        boolean krw = currency.equals("KRW");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("date", date);
        e.put("name", Values.str(t.get("is_nm")));
        e.put("code", Values.str(t.get("stnd_is_cd")));
        e.put("kind", kind);
        e.put("currency", currency);
        e.put("gross", gross);
        e.put("tax", tax);
        e.put("fx_amount", krw ? 0.0 : fxAmount);
        e.put("fx_rate", krw ? 0.0 : fxRate);
        e.put("month", Integer.parseInt(date.substring(4, 6)));
        e.put("net", gross - tax);
        return e;
    }
}
