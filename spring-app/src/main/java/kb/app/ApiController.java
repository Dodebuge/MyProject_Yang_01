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
 *   GET /api/holdings-history        날짜별 총자산 (kb.db 하루 기록 + MYSQL_HOST가 있으면 4시간 기록)
 *   GET /api/dividends?year=2026     배당 내역
 *   GET /api/recurring               소수점 정기 구매 내역 (거래내역 소수단위매수, 최근 반년)
 *   GET /api/transactions            최근 1달 거래내역
 *   GET /api/search?q=               종목 검색 (국내 이름·종목번호, 미국 티커)
 *   GET /api/chart?market=kr|us&code= 일봉 차트 (최근 약 1년)
 *   GET /api/kosdaq-volume          코스닥 전 종목 이번 주·전주 하루 평균 거래량 (10분 캐시)
 *   GET /api/kosdaq-intraday        장중 10분 기록(kosdaq.db)으로 같은 시각 거래량의 전일·전주 대비
 *   GET /api/russell-volume, /api/russell-intraday  러셀 2000 (russell2000.csv 종목) 같은 모양
 *   GET·POST /api/trader             토스 자동매매 상태, 설정·매수 대상·전량 매도 (Trader)
 *   GET /api/alerts[?since=id]       코스닥·러셀 2000 증가율 top 30의 새 상승 시그널 알림
 *   GET /api/recent                  최근 검색 종목 (recent_searches.txt, 최대 10개)
 *   GET·POST /api/settings           차트 설정 (chart_settings.json: 기간, 이평선·볼린저밴드 체크)
 *   GET·POST /api/monitor            시장 모니터에 추가한 종목 (monitor_items.json, POST는 목록 통째로)
 *   내정보 API는 ?refresh=1 이면 저장된 오늘 값을 건너뛰고 KB에서 다시 조회합니다 (Spring은 "1"을 true로 변환).
 */
@RestController
@RequestMapping("/api")
class ApiController {

    private final Heatmap heatmap;
    private final Dividends dividends;
    private final Chart chart;
    private final Snapshots snapshots;
    private final Volume volume;
    private final Russell russell;
    private final Alerts alerts;
    private final Trader trader;

    ApiController(Heatmap heatmap, Dividends dividends, Chart chart, Snapshots snapshots, Volume volume, Russell russell, Alerts alerts, Trader trader) {
        this.heatmap = heatmap;
        this.dividends = dividends;
        this.chart = chart;
        this.snapshots = snapshots;
        this.volume = volume;
        this.russell = russell;
        this.alerts = alerts;
        this.trader = trader;
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
        return ok(snapshots.history());
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

    @GetMapping("/monitor")
    ResponseEntity<Map<String, Object>> monitor() throws IOException {
        return ok(chart.monitorItems());
    }

    @PostMapping("/monitor")
    ResponseEntity<Map<String, Object>> saveMonitor(@RequestBody String body) throws IOException {
        if (body.length() > 16384) {  // 30종목이 수 KB
            throw new IllegalArgumentException("요청 크기가 올바르지 않습니다");
        }
        return ok(chart.saveMonitorItems(body));
    }

    @GetMapping("/recent")
    ResponseEntity<Map<String, Object>> recent() throws IOException {
        return ok(chart.recent());
    }

    @GetMapping("/chart")
    ResponseEntity<Map<String, Object>> chart(@RequestParam(defaultValue = "kr") String market, @RequestParam(defaultValue = "") String code,
                                              @RequestParam(defaultValue = "") String exchange,
                                              @RequestParam(defaultValue = "1") String recent) throws IOException {
        return ok(chart.chart(market, code, exchange, !"0".equals(recent)));  // recent=0: 최근 검색에 안 남김
    }

    @GetMapping("/kosdaq-volume")
    ResponseEntity<Map<String, Object>> kosdaqVolume(@RequestParam(defaultValue = "false") boolean refresh) throws IOException {
        return ok(volume.query(refresh));
    }

    @GetMapping("/kosdaq-intraday")
    ResponseEntity<Map<String, Object>> kosdaqIntraday(@RequestParam(defaultValue = "") String date,
                                                       @RequestParam(defaultValue = "") String at) throws IOException {
        return ok(volume.ticks.intraday(date, at));
    }

    @GetMapping("/trader")
    ResponseEntity<Map<String, Object>> trader() throws IOException {
        return ok(trader.status());
    }

    /** {"action": "config"|"pick"|"unpick"|"sellAll", ...} (Trader.command) */
    @PostMapping("/trader")
    ResponseEntity<Map<String, Object>> traderCommand(@RequestBody Map<String, Object> body) throws IOException {
        return ok(trader.command(body));
    }

    @GetMapping("/alerts")
    ResponseEntity<Map<String, Object>> alerts(@RequestParam(defaultValue = "0") long since) throws IOException {
        return ok(alerts.recent(since));
    }

    @GetMapping("/russell-volume")
    ResponseEntity<Map<String, Object>> russellVolume(@RequestParam(defaultValue = "false") boolean refresh) throws IOException {
        return ok(russell.query(refresh));
    }

    @GetMapping("/russell-intraday")
    ResponseEntity<Map<String, Object>> russellIntraday(@RequestParam(defaultValue = "") String date,
                                                        @RequestParam(defaultValue = "") String at) throws IOException {
        return ok(russell.ticks.intraday(date, at));
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
