package kb.app

import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.TreeMap

/**
 * 코스닥 전 종목의 이번 주 거래량을 전주와 비교합니다 (volume.py와 같은 계산).
 *
 * 코스닥 종목 목록: 거래대금 상위(IVU10210, mkt_clsf=3)를 전부(약 1,700종목) 받습니다.
 * 종목별 최근 일봉(IVS11560, 차트와 같은 약 1년)의 최근 15거래일을 주(월~일)별로 묶어 이번 주와 전주의 '하루 평균 거래량'을 비교합니다.
 * 휴장일이 있는 주나 아직 끝나지 않은 이번 주도 공정하게 비교하려고 합계 대신 하루 평균을 씁니다.
 * 종목별 일봉은 코루틴으로 8개씩 동시에 부릅니다 (Values.parallelIO).
 * 여러 종목을 동시에 부르면 가끔(수백 번에 한 번꼴) 필드가 밀린 응답(거래량 빈 값 등)이나 500 오류가 와서, 그런 응답은 다시 부릅니다.
 * 장중 5분마다 코스닥 전 종목의 누적 거래량을 kosdaq.db에 저장해, 같은 시각의 거래량을 전일·전주와 비교합니다 (Ticks, 아래 '장중 기록').
 */
internal class Volume(private val client: KBClient, tickDb: Path) {

    private var cachedAt = 0L
    private var cache: Map<String, Any?>? = null

    /** 장중(평일 09:00~15:40, 15:40은 15:30 종가 단일가까지 담기게) 10분마다 코스닥 거래량 기록. 하루 약 4MB */
    @JvmField
    val ticks = Ticks("코스닥 5분", tickDb, "kosdaq_tick", ZoneId.of("Asia/Seoul"), 5,
        LocalTime.of(9, 0), LocalTime.of(15, 40), ::kosdaqRows)

    /** GET /api/kosdaq-volume -> {"fetchedAt", "total", "stocks": [...]} (stocks는 전 종목, 화면에서 거름). synchronized: 동시에 와도 KB 조회는 한 번 */
    @Synchronized
    @Throws(IOException::class)
    fun query(refresh: Boolean): Map<String, Any?> {
        cache?.let {
            if (!refresh && System.currentTimeMillis() - cachedAt < CACHE_SECONDS * 1000L) {
                return it
            }
        }
        val stocks = fetch()
        val payload = linkedMapOf("fetchedAt" to LocalDateTime.now().format(STAMP), "total" to stocks.size, "stocks" to stocks)
        cachedAt = System.currentTimeMillis()
        cache = payload
        return payload
    }

    /** 코스닥 종목 일봉 행 (IVS11560, 차트와 같은 약 1년, 최신순). 밀린 응답·500 오류면 다시 부르고, 끝내 안 되면 null. 시그널 알림(Alerts)도 씀 */
    fun daily(code: String): List<Map<String, Any?>>? {
        var candles: List<Map<String, Any?>>? = null
        for (attempt in 0 until 3) {
            val got = try {
                KBClient.records(client.call("IVS11560", Heatmap.body("info_ccd", "1", "mkt_clsf", "1", "chrt_clsf", "D",
                    "minute_tck_indx", "", "is_cd", code, "inq_clsf", "2", "strt_dy", "", "inq_cnt", Chart.CHART_DAYS)), "out2")
            } catch (e: KBApiException) {
                continue
            } catch (e: IOException) {
                continue
            }
            if (sane(recent(got))) {  // 주간 비교에 쓰는 최근 행은 반드시 멀쩡해야 함
                candles = got
                if (whole(got, "p2")) {  // 오래된 행까지 멀쩡하면 끝 (아니면 한 번 더 받아 보고, 끝내 안 되면 깨진 행만 빼고 씀)
                    break
                }
            }
        }
        return candles
    }

    private fun fetch(): List<Map<String, Any?>> {
        val listed = KBClient.records(client.call("IVU10210", Heatmap.body("srt_clsf", "", "inq_cnt", "3000",
            "excg_clsf", "0", "mkt_clsf", "3", "thdy_bdy_clsf", "")), "out2")
        client.accessToken()  // 동시 호출 전에 토큰을 한 번만 발급
        return Values.parallelIO(listed, WORKERS) { row ->
            val candles = daily(Values.str(row["is_cd"])) ?: return@parallelIO null  // 거래정지 등
            val w = weekChange(recent(candles)) ?: return@parallelIO null
            linkedMapOf<String, Any?>(
                "code" to Values.str(row["is_cd"]),
                "name" to Values.str(row["is_nm"]),
                "price" to Values.lng(row["now_prc"]),
                "change" to Values.num(row["up_dwn_r_p2"]),
                "value" to Values.lng(row["dl_tw_amt"]),
            ).apply {
                putAll(w)
                putAll(closes(Chart.candles(candles, "p2", 10.0)))
            }
        }.filterNotNull()
    }

    // ------------------------------------------------------------------ 장중 기록 (Ticks)

    /** 코스닥 전 종목 시세 (IVU10210 한 번). 휴장일(오늘 일봉이 없음)이면 빈 목록. */
    @Throws(IOException::class)
    private fun kosdaqRows(at: LocalDateTime): List<List<Any?>> {
        val rows = KBClient.records(client.call("IVU10210", Heatmap.body("srt_clsf", "", "inq_cnt", "3000",
            "excg_clsf", "0", "mkt_clsf", "3", "thdy_bdy_clsf", "")), "out2")
            .filter { Values.str(it["is_cd"]).length == 6 && it["vlm"] is Number }  // 필드가 밀린 행은 버림
            .map { r ->
                listOf(Values.str(r["is_cd"]), Values.str(r["is_nm"]), Values.lng(r["now_prc"]),
                    Values.num(r["up_dwn_r_p2"]), Values.lng(r["vlm"]), Values.lng(r["dl_tw_amt"]))
            }
        if (rows.isEmpty()) {
            return rows
        }
        val last = KBClient.records(client.call("IVS11560", Heatmap.body("info_ccd", "1", "mkt_clsf", "1",
            "chrt_clsf", "D", "minute_tck_indx", "", "is_cd", rows[0][0] as String, "inq_clsf", "2", "strt_dy", "",
            "inq_cnt", "1")), "out2")
        if (last.isEmpty() || at.format(DateTimeFormatter.BASIC_ISO_DATE) != Values.str(last[0]["dt"])) {
            return emptyList()  // 휴장일: 시세가 마지막 거래일 값 그대로
        }
        return rows
    }

    companion object {
        private const val CACHE_SECONDS = 600  // 1,700번 넘게 부르는 조회라 10분 동안은 다시 부르지 않음
        private const val WORKERS = 8
        const val DAYS = 15                    // 이번 주 + 전주 (휴장일이 끼어도 넉넉하게)
        private const val LAST_DAYS = 10       // 화면이 시그널 교차 날짜(최근 5거래일)를 보여 줄 수 있게 날짜를 보내는 마지막 거래일 수
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** 주간 비교에 쓰는 최근 DAYS개 행 (최신순). 이 행만 엄격히 검사하고, 오래된 밀린 행은 closes에서 건너뜁니다. */
        @JvmStatic
        fun recent(candles: List<Map<String, Any?>>): List<Map<String, Any?>> = candles.subList(0, minOf(DAYS, candles.size))

        /** 일봉 행이 하나도 깨지지 않았는지 (깨진 행은 차트 계산에서 빠져 시그널이 달라질 수 있음). */
        @JvmStatic
        fun whole(rows: List<Map<String, Any?>>, suffix: String): Boolean = Chart.candles(rows, suffix, 1.0).size == rows.size

        /**
         * 차트 페이지와 같은 기술적 시그널(signals.js)을 화면에서 계산하도록 일봉 종가(오래된 날부터, 약 1년)와
         * 마지막 LAST_DAYS 거래일 날짜(교차 날짜 표시용)를 보냅니다. 날짜를 다 보내지 않는 건 응답 크기 때문. (volume.closes와 같음)
         */
        @JvmStatic
        fun closes(candles: List<Map<String, Any?>>): Map<String, Any?> {
            val c = ArrayList<Any>()
            val d = ArrayList<Any?>()
            candles.forEachIndexed { i, r ->
                val v = Values.num(r["c"])
                c.add(if (v == Math.rint(v)) Math.round(v) else v)  // 12345.0 -> 12345 (응답 크기)
                if (i >= candles.size - LAST_DAYS) {
                    d.add(r["d"])
                }
            }
            return linkedMapOf("closes" to c, "lastDays" to d)
        }

        /** 필드가 밀리지 않은 일봉인지: 날짜가 실제 날짜이고 최신순으로 겹치지 않으며, 거래량이 숫자. */
        @JvmStatic
        fun sane(candles: List<Map<String, Any?>>?): Boolean {
            if (candles.isNullOrEmpty()) {
                return false
            }
            var before: String? = null
            for (r in candles) {
                val d = Values.str(r["dt"])
                if (!Heatmap.validDay(d) || (before != null && d >= before) || r["vlm"] !is Number) {
                    return false
                }
                before = d
            }
            return true
        }

        /** 일봉 [{dt, vlm}] -> 이번 주·전주 하루 평균 거래량과 증가율(%). 두 주가 안 되면 null. */
        @JvmStatic
        fun weekChange(candles: List<Map<String, Any?>>): Map<String, Any?>? {
            val weeks = TreeMap<Int, MutableList<Long>>()
            for (r in candles) {
                val d = Values.str(r["dt"])
                if (Heatmap.validDay(d)) {  // 필드가 밀린 행은 날짜 자리에 엉뚱한 숫자
                    val day = LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE)
                    val week = day.get(IsoFields.WEEK_BASED_YEAR) * 100 + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                    weeks.getOrPut(week) { mutableListOf() }.add(Values.lng(r["vlm"]))
                }
            }
            if (weeks.size < 2) {
                return null
            }
            val now = weeks.lastEntry().value
            val prev = weeks.lowerEntry(weeks.lastKey()).value
            val avg = now.average()
            val before = prev.average()
            return linkedMapOf(
                "avg" to Math.round(avg),
                "prevAvg" to Math.round(before),
                "days" to now.size,
                "prevDays" to prev.size,
                "ratio" to if (before > 0) Math.round((avg / before - 1) * 10000) / 100.0 else null,
            )
        }
    }
}
