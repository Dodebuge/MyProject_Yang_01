package kb.slo

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils

/**
 * SLO 모니터 (README 'SLO 모니터 서버'): kb-openapi-spring(9080)과 따로 9090에서 도는 Spring Boot 서버.
 * Spring 서버가 죽거나 멈춰도 화면과 ntfy 알림이 남도록 프로세스·JAR를 나눴습니다.
 *
 *   GET /          이번 달 에러 예산과 SLI 표 (60초마다 새로 고침)
 *   GET /api/slo   같은 내용 JSON
 *   java -jar slo-monitor.jar check [--max 50] [--fail-slo]   GitHub Actions 점검 (서버를 띄우지 않음, Slo.kt check)
 */
@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
class SloMonitor

const val DEFAULT_DATA = "/home/jetson/yang/MyProject_Yang_01"

fun main(args: Array<String>) {
    if (args.firstOrNull() == "check") {
        exitProcess(check(args, Path.of(System.getenv("KB_DATA_DIR") ?: DEFAULT_DATA)))
    }
    runApplication<SloMonitor>(*args)
}

/** 점검 한 번 뒤 (장애 중?, 연속 실패 수, 보낼 알림 "down"|"recovered"|null). DOWN_AFTER번 연속 실패면 장애. */
fun nextState(down: Boolean, fails: Int, ok: Boolean): Triple<Boolean, Int, String?> = when {
    ok -> Triple(false, 0, if (down) "recovered" else null)
    !down && fails + 1 >= Probe.DOWN_AFTER -> Triple(true, fails + 1, "down")
    else -> Triple(down, fails + 1, null)
}

data class ProbeResult(val at: String, val ok: Boolean, val ms: Long, val error: String, val down: Boolean)

/** 30초마다 Spring(slo.target)을 직접 불러 slo.db probe(at KST, ok, ms)에 남기고, 장애·복구를 ntfy로 알림. */
@Component
class Probe(
    @Value("\${kb.data-dir}") dataDir: String,
    @Value("\${slo.target}") val target: String,
    @Value("\${NTFY_TOPIC:}") private val topic: String,
    @Value("\${NTFY_SERVER:https://ntfy.sh}") private val server: String,
) {
    companion object {
        const val DOWN_AFTER = 3     // 연속 실패 3번(90초)이면 장애
        const val KEEP_DAYS = 40L
        private val AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val log = LoggerFactory.getLogger(Probe::class.java)
    }

    val store = Store(Path.of(dataDir))
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private var down = false
    private var fails = 0

    @Volatile
    var last: ProbeResult? = null
        private set

    init {
        store.write { c -> c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS probe (at TEXT PRIMARY KEY, ok INTEGER NOT NULL, ms INTEGER NOT NULL)") } }
        log.info("SLO 모니터: 점검 대상 {}, 데이터 {}, ntfy {}", target, dataDir, topic.ifEmpty { "(없음, 로그만)" })
    }

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    fun once() {
        val t0 = System.nanoTime()
        val error = try {
            val res = http.send(HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.discarding())
            if (res.statusCode() < 400) "" else "HTTP ${res.statusCode()}"
        } catch (e: Exception) {  // 연결 거부·시간 초과
            "${e.javaClass.simpleName}: ${e.message}"
        }
        val ok = error.isEmpty()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val now = LocalDateTime.now(KST)
        try {
            store.write { c ->
                c.prepareStatement("INSERT OR REPLACE INTO probe VALUES (?, ?, ?)").use {
                    it.setString(1, now.format(AT)); it.setInt(2, if (ok) 1 else 0); it.setLong(3, ms); it.executeUpdate()
                }
                c.prepareStatement("DELETE FROM probe WHERE at < ?").use { it.setString(1, now.minusDays(KEEP_DAYS).format(AT)); it.executeUpdate() }
            }
        } catch (e: Exception) {  // slo.db 오류여도 점검·알림은 계속
            log.warn("slo.db 기록 실패: {}", e.toString())
        }
        val (d, f, alert) = nextState(down, fails, ok)
        down = d
        fails = f
        last = ProbeResult(now.format(AT), ok, ms, error, down)
        when (alert) {
            "down" -> notify("Spring 응답 없음", "$target ${fails}번 연속 실패 (${fails * 30}초)\n$error", 5)
            "recovered" -> notify("Spring 복구", "$target 다시 응답 (${ms}ms)", 3)
        }
    }

    private fun notify(title: String, message: String, priority: Int) {
        log.warn("[알림] {}: {}", title, message)
        if (topic.isEmpty()) return
        val body = ObjectMapper().writeValueAsString(mapOf("topic" to topic, "title" to "[SLO] $title", "message" to message,
            "priority" to priority, "tags" to listOf(if (priority >= 5) "rotating_light" else "white_check_mark")))
        try {
            val res = http.send(HttpRequest.newBuilder(URI.create(server)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() >= 300) log.warn("ntfy 보내기 실패: HTTP {} {}", res.statusCode(), res.body())
        } catch (e: Exception) {
            log.warn("ntfy 보내기 실패: {}", e.toString())
        }
    }
}

data class Snapshot(val at: String, val month: String, val budget: Budget, val probe: ProbeResult?, val slis: List<Sli>)

@RestController
class SloController(private val probe: Probe) {
    private var cachedAt = 0L
    private var cached: Snapshot? = null

    /** 화면을 자주 열어도 DB는 1분에 한 번만 읽음 */
    @Synchronized
    fun snapshot(): Snapshot {
        cached?.takeIf { System.currentTimeMillis() - cachedAt < 60_000 }?.let { return it }
        val now = LocalDateTime.now(KST)
        val s = Snapshot(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), now.format(DateTimeFormatter.ofPattern("yyyy-MM")),
            probe.store.budget(now), probe.last, probe.store.slis(now))
        cached = s
        cachedAt = System.currentTimeMillis()
        return s
    }

    @GetMapping("/api/slo")
    fun api(): ResponseEntity<Snapshot> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot())

    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(): ResponseEntity<String> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(render(snapshot()))
}

private fun esc(s: String) = HtmlUtils.htmlEscape(s)

fun render(s: Snapshot): String {
    val b = s.budget
    val p = s.probe
    val level = if (b.usedPct >= 100) "bad" else if (b.usedPct >= 50) "warn" else "good"
    val state = when {
        p == null -> "확인 전"
        p.down -> "장애"
        p.ok -> "정상"
        else -> "실패 (아직 장애 전)"
    }
    val stateClass = if (p?.down == true) "bad" else if (p?.ok == true) "good" else "warn"
    val rows = s.slis.joinToString("") { r ->
        val v = r.value
        val (cls, word) = when (r.met) { null -> "mute" to "셀 이벤트 없음"; true -> "good" to "충족"; false -> "bad" to "미달" }
        "<tr><td>${esc(r.name)}</td><td class=\"num\">${if (v == null) "–" else "%.2f%%".format(v * 100)}</td>" +
            "<td class=\"num\">${"%,d".format(r.good)} / ${"%,d".format(r.total)}</td><td class=\"num\">${"%.1f%%".format(r.slo * 100)}</td>" +
            "<td class=\"$cls\">$word</td></tr>"
    }
    var notes = ""
    if (b.unknown.isNotEmpty()) notes += "<p class=\"mute\">기록 0인 평일 (휴장 또는 하루 전체 장애, 예산에서 뺌): ${b.unknown.joinToString(", ")}</p>"
    if (b.short.isNotEmpty()) notes += "<p class=\"mute\">81칸이 안 되는 날: ${b.short.entries.joinToString(", ") { "${it.key} (${it.value}/$PER_DAY)" }}</p>"
    return """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="60">
<title>SLO 모니터</title><style>
:root{--bg:#fff;--fg:#1f2328;--mute:#656d76;--line:#d0d7de;--card:#f6f8fa;--good:#1a7f37;--warn:#9a6700;--bad:#cf222e}
@media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--mute:#8d96a0;--line:#30363d;--card:#161b22;--good:#3fb950;--warn:#d29922;--bad:#f85149}}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,"Malgun Gothic",sans-serif}
main{max-width:960px;margin:0 auto;padding:16px}h1{font-size:22px;margin:4px 0 12px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 14px}
.big{font-size:28px;font-weight:600}.track{height:8px;background:var(--line);border-radius:4px;overflow:hidden;margin:8px 0}
.fill{height:100%}.good{color:var(--good)}.warn{color:var(--warn)}.bad{color:var(--bad)}.mute{color:var(--mute)}
.fill.good{background:var(--good)}.fill.warn{background:var(--warn)}.fill.bad{background:var(--bad)}
.wrap{overflow-x:auto;margin-top:16px}table{border-collapse:collapse;width:100%;min-width:560px}
th,td{border-bottom:1px solid var(--line);padding:8px;text-align:left}th{color:var(--mute);font-weight:500}.num{text-align:right;font-variant-numeric:tabular-nums}
</style></head><body><main>
<h1>SLO 모니터 · ${s.month}</h1>
<div class="cards">
<div class="card"><div class="mute">에러 예산 (코스닥 5분 기록, SLO ${(SLO * 100).toInt()}%)</div>
<div class="big $level">${"%.1f".format(b.usedPct)}% 사용</div><div class="track"><div class="fill $level" style="width:${"%.1f".format(minOf(100.0, b.usedPct))}%"></div></div>
<div class="mute">빠진 슬롯 ${b.missing} / 허용 ${"%.0f".format(b.allowed)} · 50% 이상 장중 배포 차단 · 100% 자동매매 끔</div></div>
<div class="card"><div class="mute">Spring 응답 (마지막 점검)</div>
<div class="big $stateClass">$state</div>
<div class="mute">${p?.at ?: "–"} · ${p?.ms ?: "–"}ms ${esc(p?.error ?: "")}</div></div>
</div>$notes
<div class="wrap"><table><thead><tr><th>SLI</th><th class="num">값</th><th class="num">좋은 / 전체</th><th class="num">목표</th><th>상태</th></tr></thead>
<tbody>$rows</tbody></table></div>
<p class="mute">${s.at} KST 계산 · 1분마다 다시 계산 · <a href="api/slo">JSON</a> · 매일 16:00 GitHub Actions 점검과 같은 계산 (slo-monitor.jar check)</p>
</main></body></html>"""
}
