package kb.app;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   GET /api/chart?market=kr|us&code= 일봉 차트 (최근 약 1년). &minutes=1|3|5면 분봉
 *   GET /api/kosdaq-volume          코스닥 전 종목 이번 주·전주 하루 평균 거래량 (10분 캐시)
 *   GET /api/kosdaq-intraday        장중 5분 기록(kosdaq.db)으로 같은 시각 거래량의 전일·전주 대비
 *   GET /api/kosdaq-sim             같은 기록으로 매수·매도 시뮬레이션 (market=kosdaq|russell, buy·sell = SimModules 모듈 이름,
 *                                   start·maxHold·maxBuys, alerts=1이면 알림 종목만 알림 시각부터)
 *   GET /api/kosdaq-scalp           1분봉(minutes.db)으로 1분 이내 단타 시뮬레이션 (Scalp). days=2026-10-23,2026-10-26 (비우면 마지막 2일),
 *                                   grid=1이면 값 조합을 격자 탐색해 손익 상위 top개, wick15=1이면 매수 신호를 15분봉 상단 꼬리 없음으로,
 *                                   vwap=1이면 KB 실제 거래대금 종목만 + 신호 봉 종가 > VWAP,
 *                                   big=3이면 15분봉 장대양봉(몸통 3%↑), maSell=20이면 1분봉 20분 이평이 꺾일 때 시장가 매도
 *                                   (예: big=3&start=09:30&tp=0&sl=0&hold=400&minValue=0&maSell=20),
 *                                   maBelow=60이면 1분봉 종가가 60분 이평 아래로 내려갈 때 시장가 매도,
 *                                   ride=1이면 종가가 maBelow 이평 위면 사고 익절하면 다시 사고 이평 아래면 팔고 끝
 *                                   (예: ride=1&prevVol=300&maBelow=30&tp=3&sl=0&hold=400&minValue=0&dip=1.5),
 *                                   dip=1.5면 익절 뒤 판 값 −1.5%에 지정가로 다시 삼, aligned=1이면 첫 매수는 1분봉 이평 정배열(5>20>60>120)일 때만,
 *                                   dayStop=30000·dayLoss=30000이면 그날 실현 손익이 +3만원 이상·−3만원 이하가 된 뒤에는 새로 안 삼,
 *                                   budget=300000이면 동시에 들고 있는 매수 금액을 30만원까지만 (amount=300000과 같이),
 *                                   maxGap=5·maxDayUp=20이면 이평 +5%·당일 시가 +20%보다 위에서는 안 삼 (ride),
 *                                   prevVol=100이면 전날 같은 시각까지 누적 거래량 대비 +100% 이상인 종목만 (전날 1분봉 필요, 출처가 같은 종목만)
 *                                   (예: wick15=1&start=09:30&tp=5&sl=3&hold=400&minValue=0)
 *   GET·POST /api/kosdaq-sim-modules 시뮬레이션 매수 조건(sim_buy_rules.json, POST는 목록 통째로)·매도 모듈 목록
 *   GET /api/russell-volume, /api/russell-intraday  러셀 2000 (russell2000.csv 종목) 같은 모양
 *   GET·POST /api/trader             토스 자동매매 상태, 설정·매수 대상·전량 매도 (Trader)
 *   GET /api/alerts[?since=id]       코스닥·러셀 2000 증가율 top 30의 새 상승 시그널 알림
 *   GET /api/recent                  최근 검색 종목 (recent_searches.txt, 최대 10개)
 *   GET·POST /api/settings           차트 설정 (chart_settings.json: 기간, 이평선·볼린저밴드 체크)
 *   GET·POST /api/monitor            시장 모니터 지수 칸 + 추가한 종목 (monitor_items.json, POST는 목록 통째로)
 *   GET /api/monitor-quotes          5분마다 kb.db에 저장한 모니터 시세 (?refresh=1이면 지금 KB에서 다시 받아 저장)
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
    private final Monitor monitor;
    private final MinuteBars minuteBars;

    ApiController(Heatmap heatmap, Dividends dividends, Chart chart, Snapshots snapshots, Volume volume, Russell russell, Alerts alerts, Trader trader,
                  Monitor monitor, MinuteBars minuteBars) {
        this.minuteBars = minuteBars;
        this.monitor = monitor;
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
        return ok(monitor.listing());
    }

    @PostMapping("/monitor")
    ResponseEntity<Map<String, Object>> saveMonitor(@RequestBody String body) throws IOException {
        if (body.length() > 16384) {  // 30종목이 수 KB
            throw new IllegalArgumentException("요청 크기가 올바르지 않습니다");
        }
        Map<String, Object> out = chart.saveMonitorItems(body);
        monitor.changed();  // 새 종목 시세를 곧바로 받아 저장
        return ok(out);
    }

    @GetMapping("/monitor-quotes")
    ResponseEntity<Map<String, Object>> monitorQuotes(@RequestParam(defaultValue = "false") boolean refresh) throws IOException {
        return ok(refresh ? monitor.refresh() : monitor.quotes());  // 5분마다 kb.db에 저장한 시세, refresh면 지금 다시 받아 저장
    }

    @GetMapping("/recent")
    ResponseEntity<Map<String, Object>> recent() throws IOException {
        return ok(chart.recent());
    }

    @GetMapping("/chart")
    ResponseEntity<Map<String, Object>> chart(@RequestParam(defaultValue = "kr") String market, @RequestParam(defaultValue = "") String code,
                                              @RequestParam(defaultValue = "") String exchange,
                                              @RequestParam(defaultValue = "1") String recent, @RequestParam(defaultValue = "0") int minutes) throws IOException {
        return ok(chart.chart(market, code, exchange, !"0".equals(recent), minutes));  // recent=0: 최근 검색에 안 남김
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

    /** 저장된 5분 기록으로 '같은 시각 전일 대비 거래량 min% 이상 + 주가 상승' 종목을 amount원씩 사고 판 시뮬레이션 (Ticks.simulate) */
    @GetMapping("/kosdaq-sim")
    ResponseEntity<Map<String, Object>> kosdaqSim(@RequestParam(defaultValue = "100") double min, @RequestParam(defaultValue = "300000") double amount,
                                                  @RequestParam(defaultValue = "0") double tp, @RequestParam(defaultValue = "0") double sl,
                                                  @RequestParam(defaultValue = "15:00") String until, @RequestParam(defaultValue = "0.23") double cost,
                                                  @RequestParam(defaultValue = "1") String spac, @RequestParam(defaultValue = "30") int hold,
                                                  @RequestParam(defaultValue = "volume-open-up") String buy,
                                                  @RequestParam(defaultValue = "tp-sl") String sell,
                                                  @RequestParam(defaultValue = "kosdaq") String market, @RequestParam(defaultValue = "") String start,
                                                  @RequestParam(defaultValue = "0") int maxHold, @RequestParam(defaultValue = "0") int maxBuys,
                                                  @RequestParam(defaultValue = "0") String alerts) throws IOException {
        Ticks ticks = "kosdaq".equals(market) ? volume.ticks : "russell".equals(market) ? russell.ticks : null;
        if (ticks == null) {
            throw new IllegalArgumentException("없는 시장입니다: " + market);
        }
        return ok(ticks.simulate(min, amount, tp, sl, until, cost, !"0".equals(spac), hold, buy, sell, start, maxHold, maxBuys,
                "1".equals(alerts) ? this.alerts.firstTimes(market) : null));
    }

    /** 1분봉 단타: 봉 거래량 앞 lookback봉 평균 × volX↑ + 봉 상승 up%↑ + 봉 거래대금 minValue 백만원↑이면 다음 봉 시가에 사서 hold분 안에 익절·손절·시간 매도 */
    @GetMapping("/kosdaq-scalp")
    ResponseEntity<Map<String, Object>> kosdaqScalp(@RequestParam(defaultValue = "") String days, @RequestParam(defaultValue = "5") double volX,
                                                    @RequestParam(defaultValue = "20") int lookback, @RequestParam(defaultValue = "1") double up,
                                                    @RequestParam(defaultValue = "200") double minValue, @RequestParam(defaultValue = "1") double tp,
                                                    @RequestParam(defaultValue = "1") double sl, @RequestParam(defaultValue = "1") int hold,
                                                    @RequestParam(defaultValue = "0.23") double cost, @RequestParam(defaultValue = "1000000") double amount,
                                                    @RequestParam(defaultValue = "09:05") String start, @RequestParam(defaultValue = "15:15") String until,
                                                    @RequestParam(defaultValue = "0") String grid, @RequestParam(defaultValue = "20") int minTrades,
                                                    @RequestParam(defaultValue = "30") int top, @RequestParam(defaultValue = "0") String wick15,
                                                    @RequestParam(defaultValue = "0") String vwap, @RequestParam(defaultValue = "0") double big,
                                                    @RequestParam(defaultValue = "0") int maSell, @RequestParam(defaultValue = "0") double prevVol,
                                                    @RequestParam(defaultValue = "0") int maBelow, @RequestParam(defaultValue = "0") String ride,
                                                    @RequestParam(defaultValue = "0") double dip, @RequestParam(defaultValue = "0") String aligned,
                                                    @RequestParam(defaultValue = "0") double maxGap, @RequestParam(defaultValue = "0") double maxDayUp,
                                                    @RequestParam(defaultValue = "0") double dayStop, @RequestParam(defaultValue = "0") double dayLoss,
                                                    @RequestParam(defaultValue = "0") double budget) {
        List<String> stored = minuteBars.days();
        List<String> pick = days.isBlank() ? stored.subList(Math.max(0, stored.size() - 2), stored.size()) : List.of(days.split(","));
        Map<String, List<Scalp.Stock>> data = new LinkedHashMap<>();
        for (String d : pick) {
            if (!stored.contains(d.trim())) {
                throw new IllegalArgumentException("1분봉이 없는 날짜입니다: " + d);
            }
            data.put(d.trim(), minuteBars.stocks(d.trim()));
        }
        if (!start.matches("\\d\\d:\\d\\d") || !until.matches("\\d\\d:\\d\\d")) {
            throw new IllegalArgumentException("시각은 HH:MM 입니다");
        }
        Scalp.Params p = new Scalp.Params();
        p.volX = volX;
        p.lookback = lookback;
        p.up = up;
        p.minValue = minValue;
        p.tp = tp;
        p.sl = sl;
        p.hold = hold;
        p.cost = cost;
        p.amount = amount;
        p.start = Scalp.mins(start.replace(":", ""));
        p.until = Scalp.mins(until.replace(":", ""));
        p.wick15 = "1".equals(wick15);
        p.vwap = "1".equals(vwap);
        p.big = big;
        p.maSell = maSell;
        p.prevVol = prevVol;
        p.maBelow = maBelow;
        p.ride = "1".equals(ride);
        p.dip = dip;
        p.aligned = "1".equals(aligned);
        p.maxGap = maxGap;
        p.maxDayUp = maxDayUp;
        p.dayStop = dayStop;
        p.dayLoss = dayLoss;
        p.budget = budget;
        return ok("1".equals(grid) ? Scalp.grid(data, p, minTrades, top) : Scalp.simulate(data, p));
    }

    @GetMapping("/kosdaq-sim-modules")
    ResponseEntity<Map<String, Object>> kosdaqSimModules() {
        return ok(SimModules.catalog());
    }

    @PostMapping("/kosdaq-sim-modules")
    ResponseEntity<Map<String, Object>> saveKosdaqSimModules(@RequestBody String body) throws IOException {
        if (body.length() > 65536) {  // 매수 조건 30개 × 조건 10개
            throw new IllegalArgumentException("요청 크기가 올바르지 않습니다");
        }
        return ok(SimModules.saveBuyRules(body));
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
