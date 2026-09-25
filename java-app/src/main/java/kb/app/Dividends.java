package kb.app;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 배당 내역 (dividends.py + dividends_web.query_dividends).
 * 계좌 거래내역(SWQA2301)에서 배당 관련 거래만 골라 원화로 환산합니다.
 */
final class Dividends {

    static final String DIVIDEND = "배당금 입금";
    static final String FOREIGN_TAX = "해외원천세 출금";
    static final String DOMESTIC_TAX = "배당세금추징 출금";
    static final String TAX_REFUND = "해외원천세 환급 입금";
    private static final Set<String> RELEVANT = new HashSet<>(Arrays.asList(DIVIDEND, FOREIGN_TAX, DOMESTIC_TAX, TAX_REFUND));
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final KBClient client;

    Dividends(KBClient client) {
        this.client = client;
    }

    /** GET /api/dividends?year= */
    Map<String, Object> query(int year) throws IOException {
        LocalDate today = LocalDate.now();
        if (year < 2000 || year > today.getYear()) {
            throw new IllegalArgumentException("조회할 수 없는 연도입니다: " + year);
        }
        String start = year + "0101";
        String end = year == today.getYear() ? today.format(YMD) : year + "1231";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("year", year);
        payload.put("start", start);
        payload.put("end", end);
        payload.put("fetchedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        payload.put("entries", fetchEntries(start, end));
        return payload;
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
