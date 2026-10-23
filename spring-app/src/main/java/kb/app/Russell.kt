package kb.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 러셀 2000 거래량 (russell.py와 같은 계산·같은 JSON). 코스닥 거래량(Volume)과 같은 두 보기: 주간 평균, 같은 시각.
 *
 * KB OpenAPI에는 미국 종목 목록·순위 TR이 없어, 구성 종목은 데이터 폴더의 russell2000.csv에서 읽습니다.
 * iShares IWM(러셀 2000 ETF) Holdings의 'Download' CSV를 그대로 넣으면 되고(Asset Class가 Equity인 행만), Ticker 열만 있는 CSV도 됩니다.
 * 주간 평균: 종목별 일봉(GSC10060). 같은 시각: 뉴욕 장중(평일 10:00~16:30 뉴욕 시간) 30분마다 상세시세(GSS10030)를 russell.db에 저장.
 * 종목별 조회는 코루틴으로 8개씩 동시에 부릅니다 (Values.parallelIO).
 * KB 미국 시세는 15분 지연이라 10:00 기록은 09:45 무렵 값이고, 16:30 기록에 16:00 종가까지 담깁니다.
 * 거래소는 CSV의 Exchange 열을 먼저 쓰고, 없거나 틀리면 NAS·NYS·AMX 순으로 찾습니다. iShares 표기(MOGA)가 KB 표기(MOG.A)와
 * 다르면 마지막 글자 앞에 점을 넣어 한 번 더 찾고, 화면·기록의 종목 코드는 KB 티커라 차트 링크로 바로 열립니다.
 */
@Component
internal class Russell(private val client: KBClient, private val csv: Path, tickDb: JdbcTemplate) {

    /** Spring: 구성 종목은 데이터 폴더의 russell2000.csv, 기록은 russell.db */
    @Autowired
    constructor(client: KBClient, props: KbProperties, @Qualifier("russellDb") tickDb: JdbcTemplate) :
        this(client, props.data("russell2000.csv"), tickDb)

    private val found = ConcurrentHashMap<String, Array<String>>()  // 티커 -> {KB 거래소, KB 티커}. 한 번 찾으면 다시 찾지 않음
    private var cachedAt = 0L
    private var cache: Map<String, Any?>? = null

    /** 뉴욕 장중 30분마다 (10:00~16:30 뉴욕 시간). 하루 약 1.5MB */
    @JvmField
    val ticks = Ticks("러셀 2000 30분", tickDb, "russell_tick", ZoneId.of("America/New_York"), 30,
        LocalTime.of(10, 0), LocalTime.of(16, 30), ::rows)

    /** 뉴욕 장중 30분마다 기록 (정각 30초 뒤, 장 시간 밖은 Ticks.tick이 건너뜀) */
    @Scheduled(cron = "30 0,30 10-16 * * MON-FRI", zone = "America/New_York")
    fun recordTicks() = ticks.tick()

    /** 필드가 밀린 응답 */
    private class Shifted : Exception()

    /**
     * probe가 값을 돌려줄 때까지 거래소·티커 표기를 바꿔 가며 부릅니다. 밀린 응답·500 오류면 세 번까지. 못 찾으면 null.
     * probe: 이 거래소·표기에 없으면 null, 필드가 밀린 응답이면 Shifted.
     */
    private fun <T : Any> resolve(item: Map<String, String>, probe: (exchange: String, code: String) -> T?): T? {
        val code = item.getValue("code")
        val tries = found[code]?.let { listOf(it) } ?: run {
            val names = mutableListOf(code)
            if (code.length >= 4 && '.' !in code) {
                names.add(code.dropLast(1) + "." + code.last())
            }
            val exchanges = (listOf(item.getValue("exchange")).filter { it.isNotEmpty() } + EXCHANGES).distinct()
            names.flatMap { n -> exchanges.map { x -> arrayOf(x, n) } }
        }
        for (t in tries) {
            for (attempt in 0 until 3) {
                val got = try {
                    probe(t[0], t[1])
                } catch (e: KBApiException) {
                    continue
                } catch (e: IOException) {
                    continue
                } catch (e: Shifted) {
                    continue
                }
                if (got != null) {
                    found[code] = t
                    return got
                }
                break  // 빈 응답: 이 거래소·표기에는 없음
            }
        }
        return null
    }

    private fun quote(item: Map<String, String>): Map<String, Any?>? = resolve<Map<String, Any?>>(item) { x, n ->
        val q = client.call("GSS10030", Heatmap.body("krx_cd", x, "is_cd", n))
        val price = q["now_prc_p4"]
        if (price == null || price == "") {
            return@resolve null  // 이 거래소·표기에는 없음
        }
        if (price !is Number || q["vlm"] !is Number) {
            throw Shifted()
        }
        q
    }

    /** KB 티커·거래소(주간 평균 응답의 code, exchange)로 일봉 행 (GSC10060, 약 1년, 최신순). 시그널 알림(Alerts)이 씀 */
    fun daily(code: String, exchange: String): List<Map<String, Any?>>? =
        wholeCandles(mapOf("code" to code, "name" to code, "exchange" to exchange))

    /** 일봉. 오래된 행이 깨졌으면 두 번까지 다시 받아 봄 */
    private fun wholeCandles(item: Map<String, String>): List<Map<String, Any?>>? {
        var rows = candles(item)
        var attempt = 0
        while (attempt < 2 && rows != null && !Volume.whole(rows, "p4")) {
            rows = candles(item) ?: rows
            attempt++
        }
        return rows
    }

    private fun candles(item: Map<String, String>): List<Map<String, Any?>>? = resolve<List<Map<String, Any?>>>(item) { x, n ->
        val rows = KBClient.records(client.call("GSC10060", Heatmap.body("chrt_clsf", "3",  // 3 = 일봉
            "mdfy_stk_prc_use_f", "", "is_cd", n, "clsf", "1", "srch_strt_dy", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
            "rcrd_c", Chart.CHART_DAYS, "bndl", "", "krx_cd", x)), "out2")
        if (rows.isEmpty()) {
            return@resolve null
        }
        if (!Volume.sane(Volume.recent(rows))) {  // 주간 비교에 쓰는 최근 행만 엄격히
            throw Shifted()
        }
        rows
    }

    /** GET /api/russell-volume -> {"fetchedAt", "total", "listed", "stocks": [...]} (Volume.query와 같은 모양, listed: CSV 종목 수) */
    @Synchronized
    @Throws(IOException::class)
    fun query(refresh: Boolean): Map<String, Any?> {
        cache?.let {
            if (!refresh && System.currentTimeMillis() - cachedAt < CACHE_SECONDS * 1000L) {
                return it
            }
        }
        val items = loadList(csv)
        client.accessToken()  // 동시 호출 전에 토큰을 한 번만 발급
        val stocks = Values.parallelIO(items, WORKERS) { item ->
            val rows = wholeCandles(item) ?: return@parallelIO null
            val w = Volume.weekChange(Volume.recent(rows)) ?: return@parallelIO null
            val last = rows[0]
            val kb = found.getValue(item.getValue("code"))
            linkedMapOf<String, Any?>(
                "code" to kb[1],  // KB 티커 (차트 링크용, MOGA -> MOG.A)
                "exchange" to kb[0],
                "name" to item["name"],
                "price" to last["cls_prc_p4"],
                "change" to Values.num(last["up_dwn_r_p2"]),
                "value" to Values.lng(last["dl_tw_amt"]),
            ).apply {
                putAll(w)
                putAll(Volume.closes(Chart.candles(rows, "p4", 1.0)))  // 화면의 기술적 시그널 태그용
            }
        }.filterNotNull()
        val payload = linkedMapOf(
            "fetchedAt" to LocalDateTime.now().format(STAMP),
            "total" to stocks.size,
            "listed" to items.size,
            "stocks" to stocks,
        )
        cachedAt = System.currentTimeMillis()
        cache = payload
        return payload
    }

    /**
     * 점검: CSV 종목마다 KB 시세(GSS10030)를 찾아 {"listed", "found", "missing": [못 찾은 티커], "renamed": ["MOGA -> NYS:MOG.A", ...]}.
     * 단위 테스트 RussellTest(-Drussell.live=true)와 python russell.py --check가 씁니다.
     */
    @Throws(IOException::class)
    fun check(): Map<String, Any?> {
        val items = loadList(csv)
        client.accessToken()
        val missing = Values.parallelIO(items, WORKERS) { if (quote(it) == null) it["code"] else null }.filterNotNull()
        val renamed = items.mapNotNull { item ->
            val f = found[item["code"]]
            if (f != null && f[1] != item["code"]) "${item["code"]} -> ${f[0]}:${f[1]}" else null
        }
        return linkedMapOf("listed" to items.size, "found" to items.size - missing.size, "missing" to missing, "renamed" to renamed)
    }

    /** 러셀 2000 종목별 상세시세. 그날(뉴욕 날짜) 체결이 있는 종목만 (휴장일이면 빈 목록). */
    @Throws(IOException::class)
    private fun rows(at: LocalDateTime): List<List<Any?>> {
        val items = loadList(csv)
        val day = at.format(DateTimeFormatter.BASIC_ISO_DATE)
        client.accessToken()
        return Values.parallelIO(items, WORKERS) { item ->
            val q = quote(item)
            if (q == null || day != Values.str(q["dt"])) {
                null
            } else {
                listOf(found.getValue(item.getValue("code"))[1], item["name"], q["now_prc_p4"],
                    Values.num(q["up_dwn_r_p2"]), Values.lng(q["vlm"]), Values.lng(q["dl_tw_amt"]))
            }
        }.filterNotNull()
    }

    companion object {
        private val EXCHANGES = listOf("NAS", "NYS", "AMX")
        private val TICKER = Regex("[A-Z][A-Z0-9.]{0,7}")
        private const val CACHE_SECONDS = 600  // 2,000번 가까이 부르는 조회라 10분 동안은 다시 부르지 않음
        private const val WORKERS = 8
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** iShares CSV의 Exchange 값 -> KB 거래소 코드 (모르면 ""). */
        @JvmStatic
        fun exchangeOf(name: String): String {
            val n = name.lowercase()
            return when {
                "nasdaq" in n -> "NAS"
                "new york stock exchange" in n || n.trim() == "nyse" || n.trim() == "nys" -> "NYS"
                listOf("mkt", "american", "arca", "cboe", "bats", "amex").any { it in n } -> "AMX"
                else -> ""
            }
        }

        /** russell2000.csv -> [{"code", "name", "exchange"}]. 파일이 없거나 Ticker 열이 없으면 IllegalArgumentException (화면에 그대로 보임). */
        @JvmStatic
        @Throws(IOException::class)
        fun loadList(path: Path): List<Map<String, String>> {
            require(Files.isRegularFile(path)) {
                "${path.fileName}이 없습니다. iShares IWM(러셀 2000 ETF) 페이지 Holdings에서 CSV를 받아 ${path.parent}에 ${path.fileName}로 넣어 주세요."
            }
            val table = parseCsv(String(Files.readAllBytes(path), Charsets.UTF_8).replace("﻿", ""))
            val head = table.indexOfFirst { it.isNotEmpty() && it[0].trim() == "Ticker" }
            require(head >= 0) { "${path.fileName}에서 Ticker 열을 찾지 못했습니다." }
            val cols = table[head]
            val ticker = cols.indexOf("Ticker")
            val name = cols.indexOf("Name")
            val asset = cols.indexOf("Asset Class")
            val exchange = cols.indexOf("Exchange")
            val seen = HashSet<String>()
            val out = table.subList(head + 1, table.size).mapNotNull { r ->
                val code = cell(r, ticker).uppercase()
                val cls = if (asset < 0) "Equity" else cell(r, asset)
                if (cls != "Equity" || !TICKER.matches(code) || !seen.add(code)) {
                    null  // 현금·선물 행, 표 아래 안내문
                } else {
                    linkedMapOf("code" to code, "name" to cell(r, name).ifEmpty { code }, "exchange" to exchangeOf(cell(r, exchange)))
                }
            }
            require(out.isNotEmpty()) { "${path.fileName}에 종목이 없습니다." }
            return out
        }

        private fun cell(row: List<String>, i: Int): String = if (i >= 0 && i < row.size) row[i].trim() else ""

        /** 따옴표("a, b", "")를 지원하는 간단한 CSV 파서. */
        @JvmStatic
        fun parseCsv(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val next = text.getOrNull(i + 1)
                when {
                    quoted && c == '"' -> if (next == '"') {
                        cell.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                    quoted -> cell.append(c)
                    c == '"' -> quoted = true
                    c == ',' -> {
                        row.add(cell.toString())
                        cell.setLength(0)
                    }
                    c == '\n' || c == '\r' -> {
                        if (c == '\r' && next == '\n') {
                            i++
                        }
                        row.add(cell.toString())
                        cell.setLength(0)
                        rows.add(row)
                        row = mutableListOf()
                    }
                    else -> cell.append(c)
                }
                i++
            }
            if (cell.isNotEmpty() || row.isNotEmpty()) {
                row.add(cell.toString())
                rows.add(row)
            }
            return rows
        }
    }
}
