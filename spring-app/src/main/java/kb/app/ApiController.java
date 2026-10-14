package kb.app;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * /api/* (dividends_web.py의 API와 같은 주소·같은 JSON).
 *   GET /api/heatmap?market=kr|us    섹터 히트맵
 *   GET /api/quarters?market=kr|us   분기별 일평균 거래대금
 *   GET /api/holdings                보유 종목과 현금, KB + 토스 (하루 한 번 조회해 kb.db에 저장)
 *   GET /api/holdings-history        날짜별 총자산 (kb.db에 저장된 보유 현황, KB 조회 없음)
 *   GET /api/dividends?year=2026     배당 내역
 *   GET /api/recurring               소수점 정기 구매 내역 (거래내역 소수단위매수, 최근 반년)
 *   GET /api/transactions            최근 1달 거래내역
 *   GET /api/search?q=               종목 검색 (국내 이름·종목번호, 미국 티커)
 *   GET /api/chart?market=kr|us&code= 일봉 차트 (최근 약 1년)
 *   GET /api/recent                  최근 검색 종목 (recent_searches.txt, 최대 10개)
 *   GET·POST /api/settings           차트 설정 (chart_settings.json: 기간, 이평선·볼린저밴드 체크)
 *   내정보 API는 ?refresh=1 이면 저장된 오늘 값을 건너뛰고 KB에서 다시 조회합니다 (Spring은 "1"을 true로 변환).
 */
@RestController
@RequestMapping("/api")
class ApiController {

    private final Heatmap heatmap;
    private final Dividends dividends;
    private final Chart chart;

    ApiController(Heatmap heatmap, Dividends dividends, Chart chart) {
        this.heatmap = heatmap;
        this.dividends = dividends;
        this.chart = chart;
    }

    @GetMapping("/heatmap")
    ResponseEntity<Map<String, Object>> heatmap(@RequestParam(defaultValue = "kr") String market) throws IOException {
        return ok(heatmap.queryHeatmap(market));
    }

    @GetMapping("/quarters")
    ResponseEntity<Map<String, Object>> quarters(@RequestParam(defaultValue = "kr") String market) throws IOException {
        return ok(heatmap.queryQuarters(market));
    }

    @GetMapping("/holdings")
    ResponseEntity<Map<String, Object>> holdings(@RequestParam(defaultValue = "false") boolean refresh) throws IOException {
        return ok(heatmap.queryHoldings(refresh));
    }

    @GetMapping("/holdings-history")
    ResponseEntity<Map<String, Object>> holdingsHistory() throws IOException {
        return ok(heatmap.holdingsHistory());
    }

    @GetMapping("/recurring")
    ResponseEntity<Map<String, Object>> recurring(@RequestParam(defaultValue = "false") boolean refresh) throws IOException {
        return ok(dividends.queryRecurring(refresh));
    }

    @GetMapping("/search")
    ResponseEntity<Map<String, Object>> search(@RequestParam(defaultValue = "") String q) throws IOException {
        return ok(chart.search(q));
    }

    @GetMapping("/settings")
    ResponseEntity<Map<String, Object>> settings() throws IOException {
        return ok(chart.settings());
    }

    @PostMapping("/settings")
    ResponseEntity<Map<String, Object>> saveSettings(@RequestBody String body) throws IOException {
        if (body.length() > 4096) {  // 설정은 수십 바이트
            throw new IllegalArgumentException("설정 크기가 올바르지 않습니다");
        }
        return ok(chart.saveSettings(body));
    }

    @GetMapping("/recent")
    ResponseEntity<Map<String, Object>> recent() throws IOException {
        return ok(chart.recent());
    }

    @GetMapping("/chart")
    ResponseEntity<Map<String, Object>> chart(@RequestParam(defaultValue = "kr") String market, @RequestParam(defaultValue = "") String code,
                                              @RequestParam(defaultValue = "") String exchange) throws IOException {
        return ok(chart.chart(market, code, exchange));
    }

    @GetMapping("/transactions")
    ResponseEntity<Map<String, Object>> transactions(@RequestParam(defaultValue = "false") boolean refresh) throws IOException {
        return ok(dividends.queryTransactions(refresh));
    }

    @GetMapping("/dividends")
    ResponseEntity<Map<String, Object>> dividends(@RequestParam(required = false) Integer year,
                                                  @RequestParam(defaultValue = "false") boolean refresh) throws IOException {
        return ok(dividends.query(year == null ? LocalDate.now().getYear() : year, refresh));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    // 페이지의 fetch()가 {"error": ...}를 읽어 화면에 보여 줍니다 (Python 서버와 같은 상태 코드).

    @ExceptionHandler({IllegalArgumentException.class, KBApiException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(Exception.class)  // 네트워크 오류 등
    ResponseEntity<Map<String, Object>> badGateway(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
    }
}
