package kb.app;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * /api/* (dividends_web.py의 API와 같은 주소·같은 JSON).
 *   GET /api/heatmap?market=kr|us    섹터 히트맵
 *   GET /api/quarters?market=kr|us   분기별 일평균 거래대금
 *   GET /api/holdings                보유 종목과 현금 (잔고 TR 실시간, 파일 저장 없음)
 *   GET /api/dividends?year=2026     배당 내역
 */
@RestController
@RequestMapping("/api")
class ApiController {

    private final Heatmap heatmap;
    private final Dividends dividends;

    ApiController(Heatmap heatmap, Dividends dividends) {
        this.heatmap = heatmap;
        this.dividends = dividends;
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
    ResponseEntity<Map<String, Object>> holdings() throws IOException {
        return ok(heatmap.queryHoldings());
    }

    @GetMapping("/dividends")
    ResponseEntity<Map<String, Object>> dividends(@RequestParam(required = false) Integer year) throws IOException {
        return ok(dividends.query(year == null ? LocalDate.now().getYear() : year));
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
