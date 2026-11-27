package kb.slo

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.sqlite.SQLiteConfig

/*
 * 에러 예산과 SLI 계산 (README 'SLO / SLI'). 화면(SloController)과 GitHub Actions(`java -jar slo-monitor.jar check`)가 같이 씁니다.
 * Spring 서버(kb-openapi-spring)가 남긴 SQLite·접근 로그를 읽기만 하고, 쓰는 곳은 이 모니터의 slo.db 하나입니다.
 *
 * 에러 예산: 코스닥 5분 기록(kosdaq.db kosdaq_tick) 평일 09:00~15:40 하루 81슬롯, SLO 98%. 허용 = 이번 달 평일 수 × 81 × 2%.
 * SLI (이번 달, 끝난 날만):
 *   Spring 응답          slo.db probe: 30초마다 블랙박스 점검 성공 / 점검                                   99.5%
 *   외부 연결 (KB·토스)   slo.db probe.ext: 같은 30초 점검에서 KB·토스에 닿음 / 점검                           99.5%
 *                       + check는 지난 평일 점검 이후 3번(90초) 넘게 이어진 끊김이 있으면 실패 (--fail-slo)
 *   자동매매 5초 확인     trader.db trader_beat(tick) / trader_session 정규장 분                              99.5%
 *   단타 1분 신호 처리    trader_beat(scalp) / 단타 쓴 날 09:01~마감 10분 전                                  99%
 *   러셀 2000 30분 기록   russell_tick 저장 슬롯 / 기록 있는 날 × 14                                          93%
 *   코스닥 1분봉 수집     minutes.db kosdaq_min1 종목 / 그날 마지막 5분 기록 종목 수 (21:00 뒤)              99%
 *   장마감 전 매도        trader_position 산 날(미국은 다음 날 07:30 KST) 안에 CLOSED / 산 종목 (미장 연동 제외) 100%
 *   /api 응답 5xx 아님    access_log 평일 08:50~16:00 /api/… 중 5xx 아님                                      99%
 *   응답 시간            같은 로그, 목표 시간 안 비율(= p95): 캐시 0.5초·히트맵 10초·배당 30초·주간 평균 90초 95%
 */

const val SLO = 0.98
const val PER_DAY = 81                // 09:00~15:40, 5분 간격
const val RUSSELL_PER_DAY = 14        // 10:00~16:30 뉴욕, 30분 간격
const val TICK_KEEP_DAYS = 14         // Ticks.KEEP_DAYS: 저장할 때마다 '지금 − 14일'보다 이른 기록을 시각 단위로 지워 14일 전 날은 잘려 있음
const val PAIR = "미장 연동"           // Trader.PAIR: 장마감 매도를 끌 수 있어 장마감 SLI에서 뺌
val KST: ZoneId = ZoneId.of("Asia/Seoul")
val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val YM = DateTimeFormatter.ofPattern("yyyy-MM")

data class Budget(val allowed: Double, val missing: Int, val usedPct: Double, val unknown: List<String>, val short: Map<String, Int>)

data class Sli(val name: String, val good: Int, val total: Int, val slo: Double) {
    val value: Double? get() = if (total == 0) null else good.toDouble() / total
    val met: Boolean? get() = value?.let { it >= slo }
}

data class Trade(val market: String, val day: String, val source: String, val status: String, val closed: String?)

data class Session(val market: String, val open: String, val close: String)

/** 오늘 now까지 이미 기록됐어야 할 슬롯 수. 기록은 정각 30초 뒤라 2분 여유. */
fun expectedToday(now: LocalDateTime): Int {
    val minutes = now.hour * 60 + now.minute - 2 - 9 * 60
    return if (minutes < 0) 0 else minOf(PER_DAY, minutes / 5 + 1)
}

/** counts: {날짜: 저장된 슬롯 수}. 이번 달 1일~오늘 평일만. */
fun budget(counts: Map<String, Int>, now: LocalDateTime): Budget {
    val today = now.toLocalDate()
    val weekdays = (1..today.lengthOfMonth()).map { today.withDayOfMonth(it) }.filter { it.dayOfWeek.value <= 5 }
    val allowed = weekdays.size * PER_DAY * (1 - SLO)
    var missing = 0
    val unknown = mutableListOf<String>()
    for (d in weekdays.filter { it <= today }) {
        val want = if (d < today) PER_DAY else expectedToday(now)
        val got = counts[d.toString()] ?: 0
        if (got == 0 && want > 0) {
            // ponytail: 기록 0인 평일은 휴장일과 하루 전체 장애를 구분 못 해 예산에서 뺌. 휴장일 목록이 생기면 그걸로 가르기
            unknown += d.toString()
            continue
        }
        missing += maxOf(0, want - got)
    }
    val short = counts.filter { (d, n) -> n in 1 until PER_DAY && d < today.toString() }.toSortedMap()
    return Budget(allowed, missing, 100.0 * missing / allowed, unknown, short)
}

/** 이 날짜 전의 뉴욕 거래일은 끝났음 (16:30 ET 기록 = 다음 날 06:30·07:30 KST). */
fun nyDone(now: LocalDateTime): LocalDate = now.minusHours(7).minusMinutes(30).toLocalDate()

/** 이 날짜 전의 한국 날은 끝났음. 오늘도 hhmm(KST)이 지났으면 포함. */
fun krDone(now: LocalDateTime, hhmm: String): LocalDate =
    if (now.format(HHMM) >= hhmm) now.toLocalDate().plusDays(1) else now.toLocalDate()

/** counts: {뉴욕 날짜: 슬롯 수}. 기록 0인 날(휴장·전체 장애)은 코스닥 예산처럼 뺌. */
fun russellSli(counts: Map<String, Int>, now: LocalDateTime): Pair<Int, Int> {
    val days = counts.keys.filter { it < nyDone(now).toString() }
    return days.sumOf { minOf(counts.getValue(it), RUSSELL_PER_DAY) } to days.size * RUSSELL_PER_DAY
}

/** got: {날짜: 1분봉 저장 종목 수}, listed: {날짜: 그날 마지막 5분 기록 종목 수}. 21:00 KST 전인 오늘은 뺌. */
fun minutesSli(got: Map<String, Int>, listed: Map<String, Int>, now: LocalDateTime): Pair<Int, Int> {
    val days = listed.keys.filter { it < krDone(now, "21:00").toString() }
    return days.sumOf { minOf(got[it] ?: 0, listed.getValue(it)) } to days.sumOf { listed.getValue(it) }
}

/** 산 종목 중 그날 장 안에 팔린 것. closed는 KST 'yyyy-MM-dd HH:mm:ss'. */
fun closeSli(rows: List<Trade>, now: LocalDateTime): Pair<Int, Int> {
    var good = 0
    var total = 0
    for (t in rows) {
        if (t.source == PAIR) continue
        val d = LocalDate.parse(t.day)
        val deadline = if (t.market == "KR") {
            if (d >= krDone(now, "15:30")) continue
            "${t.day} 15:30:00"
        } else {
            if (d >= nyDone(now)) continue
            "${d.plusDays(1)} 07:30:00"
        }
        total++
        if (t.status == "CLOSED" && t.closed != null && t.closed <= deadline) good++
    }
    return good to total
}

/** 'yyyy-MM-dd HH:mm' start 이상 end 미만의 분. */
fun minutesBetween(start: String, end: String): List<String> {
    val out = mutableListOf<String>()
    var t = LocalDateTime.parse(start, MINUTE)
    val stop = LocalDateTime.parse(end, MINUTE)
    while (t < stop) {
        out += t.format(MINUTE)
        t = t.plusMinutes(1)
    }
    return out
}

/**
 * trader.db 정규장(KST)과 성공한 분(kind, market, minute). 정규장이 끝난 날만.
 * tick: 정규장 전체. scalp: 그날 한 번이라도 처리한 날만, 첫 분(5분 기록 전)과 마감 10분 전부터는 뺌 (Trader.CUTOFF_MINUTES).
 */
fun beatSli(sessions: List<Session>, beats: Set<Triple<String, String, String>>, kind: String, now: LocalDateTime): Pair<Int, Int> {
    var good = 0
    var total = 0
    val stamp = now.format(MINUTE)
    for (s in sessions) {
        if (s.close > stamp) continue
        var window = minutesBetween(s.open, s.close)
        if (kind == "scalp") {
            if (s.market != "KR" || window.none { Triple(kind, s.market, it) in beats }) continue
            // ponytail: 장중에 단타를 켜고 끈 시간도 놓친 분으로 셈. 켠 시간 기록이 생기면 그걸로 분모를 줄이기
            window = window.subList(1, window.size - 10)
        }
        total += window.size
        good += window.count { Triple(kind, s.market, it) in beats }
    }
    return good to total
}

// 접근 로그 (spring-app application.properties): 2026-11-25 13:01:02 200 12345 "GET /api/heatmap?market=kr HTTP/1.1"  (%D 마이크로초)
private val ACCESS = Regex("""^(\d{4}-\d\d-\d\d) (\d\d:\d\d):\d\d (\d{3}) (\d+) "\S+ (\S+)""")
// (이름, 경로 (null이면 ?cached=1), 목표 ms): 목표 안 비율 95% = p95가 목표보다 짧음
private val LATENCY = listOf(Triple("캐시 응답 0.5초 안", null, 500), Triple("히트맵 10초 안", "/api/heatmap", 10_000),
    Triple("배당 30초 안", "/api/dividends", 30_000), Triple("주간 평균 90초 안", "/api/kosdaq-volume", 90_000))

/** 평일 08:50~16:00 KST의 /api/… 요청만 (주석 안 슬래시-별은 Kotlin에서 중첩 주석이 됨). */
fun accessSlis(lines: Sequence<String>): List<Sli> {
    var ok = 0
    var api = 0
    val lat = LATENCY.associate { it.first to IntArray(2) }
    for (line in lines) {
        val (day, hhmm, status, micros, url) = ACCESS.find(line)?.destructured ?: continue
        if (!url.startsWith("/api/") || LocalDate.parse(day).dayOfWeek.value > 5 || hhmm < "08:50" || hhmm >= "16:00") continue
        api++
        if (status.toInt() < 500) ok++
        val cached = "cached=1" in url
        for ((name, prefix, limit) in LATENCY) {
            if ((prefix == null && cached) || (prefix != null && !cached && url.substringBefore('?') == prefix)) {
                lat.getValue(name)[1]++
                if (micros.toLong() / 1000.0 < limit) lat.getValue(name)[0]++
            }
        }
    }
    return listOf(Sli("/api 응답 5xx 아님 (T3)", ok, api, 0.99)) + LATENCY.map { Sli(it.first, lat.getValue(it.first)[0], lat.getValue(it.first)[1], 0.95) }
}

/** 점검 끊김 구간 (첫 실패, 마지막 실패, 실패 점검 수). */
data class Outage(val start: String, val end: String, val n: Int)

/** (시각, 성공) 시각순에서 min번 넘게 이어진 실패 구간. */
fun outages(rows: List<Pair<String, Boolean>>, min: Int = 3): List<Outage> {
    val out = mutableListOf<Outage>()
    var run = mutableListOf<String>()
    for ((at, ok) in rows + ("" to true)) {
        if (!ok) { run += at; continue }
        if (run.size >= min) out += Outage(run.first(), run.last(), run.size)
        run = mutableListOf()
    }
    return out
}

/** 지난 평일 같은 시각 (월요일이면 금요일): 매일 점검이 그 사이 끊김을 놓치지 않게. */
fun lastCheck(now: LocalDateTime): LocalDateTime {
    var t = now.minusDays(1)
    while (t.dayOfWeek.value > 5) t = t.minusDays(1)
    return t
}

/** 데이터 폴더(KB_DATA_DIR)의 SQLite·로그. Spring 서버 파일은 읽기만, slo.db에만 씁니다. */
class Store(val data: Path) {

    /** 읽기 전용. 파일이나 표가 없으면 빈 목록 (그 기능을 안 쓰는 서버). */
    fun <T> query(db: String, sql: String, vararg args: Any, row: (ResultSet) -> T): List<T> {
        val file = data.resolve(db)
        if (!Files.exists(file)) return emptyList()
        return try {
            SQLiteConfig().apply { setReadOnly(true); setBusyTimeout(5000) }.createConnection("jdbc:sqlite:$file").use { c ->
                c.prepareStatement(sql).use { st ->
                    args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                    st.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
                }
            }
        } catch (e: SQLException) {
            emptyList()
        }
    }

    /** slo.db에 씀 (트랜잭션 하나). */
    fun <T> write(block: (Connection) -> T): T =
        SQLiteConfig().apply { setBusyTimeout(5000) }.createConnection("jdbc:sqlite:${data.resolve("slo.db")}").use { c ->
            c.autoCommit = false
            block(c).also { c.commit() }
        }

    /**
     * {날짜: 슬롯 수}. 잘리지 않은 날(오늘 − 12일부터, 뉴욕 날짜를 위해 하루 여유)만 세어 slo.db day_count에 쌓고,
     * 쌓인 이번 달 전체를 돌려줌 (14일이 지나도 남게). 한 번도 쌓지 못하고 지워진 날은 없음 = '기록 0인 평일'.
     */
    fun dayCounts(db: String, table: String, month: String, today: LocalDate): Map<String, Int> {
        val since = today.minusDays((TICK_KEEP_DAYS - 2).toLong()).toString()
        val fresh = query(db, "SELECT substr(at,1,10), count(*) FROM $table WHERE at LIKE ? GROUP BY 1", "$month%") {
            it.getString(1) to it.getInt(2)
        }.filter { it.first >= since }.toMap()
        val kept = try {
            write { c ->
                c.createStatement().use {
                    it.execute("CREATE TABLE IF NOT EXISTS day_count (tbl TEXT NOT NULL, day TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (tbl, day))")
                }
                c.prepareStatement("INSERT OR REPLACE INTO day_count VALUES (?, ?, ?)").use { st ->
                    for ((d, n) in fresh) {
                        st.setString(1, table); st.setString(2, d); st.setInt(3, n); st.executeUpdate()
                    }
                }
                c.prepareStatement("SELECT day, n FROM day_count WHERE tbl = ? AND day LIKE ?").use { st ->
                    st.setString(1, table); st.setString(2, "$month%")
                    st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
                }
            }
        } catch (e: SQLException) {  // slo.db에 못 써도 지금 보관 중인 값으로는 셈
            emptyMap()
        }
        return kept + fresh
    }

    fun budget(now: LocalDateTime): Budget = budget(dayCounts("kosdaq.db", "kosdaq_tick", now.format(YM), now.toLocalDate()), now)

    /** 30초 블랙박스 점검 (SloMonitor.Probe가 slo.db probe에 남김). */
    fun probeSli(month: String): Sli {
        val (good, total) = query("slo.db", "SELECT coalesce(sum(ok), 0), count(*) FROM probe WHERE at LIKE ?", "$month%") {
            it.getInt(1) to it.getInt(2)
        }.firstOrNull() ?: (0 to 0)
        return Sli("Spring 응답 (블랙박스, 30초)", good, total, 0.995)
    }

    /** 같은 점검의 외부(KB·토스) 연결. ext 없는 예전 줄은 빼고 셈. */
    fun externalSli(month: String): Sli {
        val (good, total) = query("slo.db", "SELECT coalesce(sum(ext), 0), count(ext) FROM probe WHERE at LIKE ?", "$month%") {
            it.getInt(1) to it.getInt(2)
        }.firstOrNull() ?: (0 to 0)
        return Sli("외부 연결 KB·토스 (30초)", good, total, 0.995)
    }

    fun externalOutages(since: LocalDateTime): List<Outage> = outages(
        query("slo.db", "SELECT at, ext FROM probe WHERE ext IS NOT NULL AND at >= ? ORDER BY at", since.format(SECOND)) {
            it.getString(1) to (it.getInt(2) == 1)
        })

    fun slis(now: LocalDateTime): List<Sli> {
        val month = now.format(YM)
        val like = "$month%"
        val sessions = query("trader.db", "SELECT market, open, close FROM trader_session WHERE open LIKE ?", like) {
            Session(it.getString(1), it.getString(2), it.getString(3))
        }
        val beats = query("trader.db", "SELECT kind, market, minute FROM trader_beat WHERE minute LIKE ?", like) {
            Triple(it.getString(1), it.getString(2), it.getString(3))
        }.toSet()
        val russell = dayCounts("russell.db", "russell_tick", month, now.toLocalDate())
        val listed = query("kosdaq.db", "SELECT substr(at,1,10), json_array_length(payload) FROM kosdaq_tick"
                + " WHERE at IN (SELECT max(at) FROM kosdaq_tick WHERE at LIKE ? GROUP BY substr(at,1,10))", like) {
            it.getString(1) to it.getInt(2)
        }.toMap()
        val got = query("minutes.db", "SELECT day, count(DISTINCT code) FROM kosdaq_min1 WHERE day LIKE ? GROUP BY day", like) {
            it.getString(1) to it.getInt(2)
        }.toMap()
        val trades = query("trader.db", "SELECT market, day, source, status, closed FROM trader_position WHERE bought IS NOT NULL AND day LIKE ?", like) {
            Trade(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }
        fun sli(name: String, p: Pair<Int, Int>, slo: Double) = Sli(name, p.first, p.second, slo)
        return listOf(
            probeSli(month),
            externalSli(month),
            sli("자동매매 5초 확인 (T0)", beatSli(sessions, beats, "tick", now), 0.995),
            sli("단타 1분 신호 처리 (T0)", beatSli(sessions, beats, "scalp", now), 0.99),
            sli("러셀 2000 30분 기록", russellSli(russell, now), 0.93),
            sli("코스닥 1분봉 수집", minutesSli(got, listed, now), 0.99),
            sli("장마감 전 매도 (T0)", closeSli(trades, now), 1.0),
        ) + accessSlis(accessFiles(month).asSequence().flatMap { it.toFile().readLines().asSequence() })
    }

    /** 이번 달 access.yyyy-MM-dd.log (오늘 것까지). */
    private fun accessFiles(month: String): List<Path> {
        val folder = data.resolve("access_log")
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { s -> s.filter { it.fileName.toString().startsWith("access.$month") }.sorted().toList() }
    }
}

/** GitHub Actions Job Summary용 Markdown. */
fun report(b: Budget, slis: List<Sli>, now: LocalDateTime, cut: List<Outage> = emptyList()): String = buildString {
    val left = maxOf(0.0, b.allowed - b.missing)
    appendLine("### 에러 예산 ${now.format(YM)} (코스닥 5분 기록, SLO ${(SLO * 100).toInt()}%)")
    appendLine()
    appendLine("- 사용: **${"%.1f".format(b.usedPct)}%** (빠진 슬롯 ${b.missing} / 허용 ${"%.0f".format(b.allowed)}, 1슬롯 = 5분)")
    appendLine("- 남은 예산: ${"%.0f".format(left)}슬롯 (약 ${"%.0f".format(left * 5)}분)")
    if (b.unknown.isNotEmpty()) appendLine("- 기록 0인 평일 (휴장일이 아니면 하루 전체 장애, 예산에서 뺌): ${b.unknown.joinToString(", ")}")
    if (b.short.isNotEmpty()) appendLine("- 81칸이 안 되는 날: " + b.short.entries.joinToString(", ") { "${it.key} (${it.value}/$PER_DAY)" })
    if (b.usedPct >= 100) appendLine("- **예산 소진**: 자동매매를 끄고 원인부터 고치세요 (README '에러 예산 운용').")
    else if (b.usedPct >= 50) appendLine("- **절반 넘게 사용**: 장중 수동 배포(now) 금지, 자동매매 새 기능 배포 보류.")
    for (o in cut) appendLine("- **외부 연결 끊김** (KB·토스에 닿지 못함, 자동매매·기록 멈춤): ${o.start} ~ ${o.end} (약 ${(o.n + 1) / 2}분)")
    appendLine()
    appendLine("| SLI | 값 | 목표 | |")
    appendLine("|---|---|---|---|")
    for (s in slis) {
        val v = s.value
        val target = "%.1f%%".format(s.slo * 100)
        if (v == null) appendLine("| ${s.name} | 셀 이벤트 없음 | $target | – |")
        else appendLine("| ${s.name} | ${"%.2f".format(v * 100)}% (${s.good}/${s.total}) | $target | ${if (s.met == true) "✅" else "❌"} |")
    }
}

/**
 * `java -jar slo-monitor.jar check [--max 50] [--fail-slo]` (GitHub Actions): 출력하고 Job Summary에 덧붙임.
 * --max: 예산을 이 % 이상 썼으면 1, --fail-slo: SLI 하나라도 목표 미달이거나 지난 평일 점검 이후 외부 연결이 끊겼으면 1.
 */
fun check(args: Array<String>, data: Path, now: LocalDateTime = LocalDateTime.now(KST)): Int {
    val max = args.indexOf("--max").takeIf { it >= 0 }?.let { args[it + 1].toDouble() }
    val store = Store(data)
    val b = store.budget(now)
    val slis = store.slis(now)
    val cut = store.externalOutages(lastCheck(now))
    val text = report(b, slis, now, cut)
    print(text)
    System.getenv("GITHUB_STEP_SUMMARY")?.let { Files.writeString(Path.of(it), text, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) }
    if (max != null && b.usedPct >= max) {
        System.err.println("에러 예산 ${"%.1f".format(b.usedPct)}% 사용 (기준 ${max}%)")
        return 1
    }
    val missed = slis.filter { it.met == false }.map { it.name } + cut.map { "외부 연결 끊김 ${it.start}~${it.end.substring(11)}" }
    if ("--fail-slo" in args && missed.isNotEmpty()) {
        System.err.println("SLO 미달: ${missed.joinToString(", ")}")
        return 1
    }
    return 0
}
