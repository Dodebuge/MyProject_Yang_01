package kb.slo

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SloTest {
    private val now = LocalDateTime.of(2026, 11, 25, 16, 0)  // 수요일, 11월 평일 21일 → 허용 21 × 81 × 2% = 34.02

    @Test
    fun expectedSlotsToday() {
        assertEquals(81, expectedToday(now))
        assertEquals(0, expectedToday(LocalDateTime.of(2026, 11, 25, 9, 1)))
        assertEquals(1, expectedToday(LocalDateTime.of(2026, 11, 25, 9, 2)))
        assertEquals(3, expectedToday(LocalDateTime.of(2026, 11, 25, 9, 12)), "09:00·09:05·09:10")
    }

    @Test
    fun budgetCountsMissingSlotsAndSkipsZeroDays() {
        val full = (1..25).associate { "2026-11-%02d".format(it) to PER_DAY }
        val b = budget(full, now)
        assertEquals(0, b.missing)
        assertEquals(34.02, b.allowed, 1e-9)
        assertTrue(b.unknown.isEmpty())
        val b2 = budget(full + mapOf("2026-11-24" to 64, "2026-11-20" to 0), now)
        assertEquals(17, b2.missing)
        assertEquals(listOf("2026-11-20"), b2.unknown, "기록 0인 평일은 휴장·장애를 구분 못 해 뺌")
        assertTrue(b2.usedPct > 49 && b2.usedPct < 50.1)
        assertEquals(mapOf("2026-11-24" to 64), b2.short)
        assertEquals(2, budget(full + mapOf("2026-11-25" to 10), LocalDateTime.of(2026, 11, 25, 10, 0)).missing, "오늘은 09:55까지 12슬롯")
    }

    @Test
    fun russellMinutesAndCloseSell() {
        assertEquals(27 to 28, russellSli(mapOf("2026-11-24" to 13, "2026-11-23" to 14, "2026-11-25" to 3), now), "뉴욕 오늘은 아직 장 전")
        assertEquals(1690 to 1700, minutesSli(mapOf("2026-11-24" to 1690, "2026-11-25" to 5), mapOf("2026-11-24" to 1700, "2026-11-25" to 1700), now))
        assertEquals(0 to 1700, minutesSli(emptyMap(), mapOf("2026-11-25" to 1700), LocalDateTime.of(2026, 11, 25, 21, 0)))
        val rows = listOf(
            Trade("KR", "2026-11-25", "단타", "CLOSED", "2026-11-25 15:20:05"),      // 좋음
            Trade("KR", "2026-11-24", "직접", "CLOSED", "2026-11-25 09:00:10"),      // 다음 날 팜: 나쁨
            Trade("KR", "2026-11-24", "직접", "OPEN", null),                         // 안 팜: 나쁨
            Trade("KR", "2026-11-24", PAIR, "OPEN", null),                           // 미장 연동: 뺌
            Trade("US", "2026-11-24", "시뮬레이션", "CLOSED", "2026-11-25 05:50:00"), // 뉴욕 장 안: 좋음
            Trade("US", "2026-11-25", "직접", "OPEN", null),                         // 뉴욕 오늘 장 전: 뺌
        )
        assertEquals(2 to 4, closeSli(rows, now))
        assertEquals(0 to 0, closeSli(rows.take(1), LocalDateTime.of(2026, 11, 25, 15, 0)), "장 마감 전이면 오늘은 아직")
    }

    @Test
    fun traderBeats() {
        val sessions = listOf(Session("KR", "2026-11-25 09:00", "2026-11-25 15:30"), Session("KR", "2026-11-24 09:00", "2026-11-24 15:30"),
            Session("US", "2026-11-25 23:30", "2026-11-26 06:00"))
        val kr = minutesBetween("2026-11-25 09:00", "2026-11-25 15:30")
        assertEquals(390, kr.size)
        assertEquals("2026-11-25 15:29", kr.last())
        val beats = kr.filter { it != "2026-11-25 10:00" }.flatMap { listOf(Triple("tick", "KR", it), Triple("scalp", "KR", it)) }.toSet()
        assertEquals(389 to 780, beatSli(sessions, beats, "tick", now), "11-24는 기록 0 → 하루 전체 놓침, 미국은 아직 장 중")
        assertEquals(378 to 379, beatSli(sessions, beats, "scalp", now), "09:01~15:19, 단타 안 쓴 11-24는 뺌")
        assertEquals(0 to 390, beatSli(sessions, beats, "tick", LocalDateTime.of(2026, 11, 25, 15, 0)), "오늘은 아직, 11-24만")
    }

    @Test
    fun accessLog() {
        val log = sequenceOf(
            "2026-11-25 13:01:02 200 120000 \"GET /api/monitor-quotes?cached=1 HTTP/1.1\"",   // 캐시 0.12초: 좋음
            "2026-11-25 13:01:03 200 900000 \"GET /api/heatmap?cached=1&market=kr HTTP/1.1\"",  // 캐시 0.9초: 나쁨
            "2026-11-25 13:01:09 200 6000000 \"GET /api/heatmap?market=kr HTTP/1.1\"",         // 히트맵 6초: 좋음
            "2026-11-25 13:02:00 502 31000000 \"GET /api/dividends?year=2026 HTTP/1.1\"",      // 5xx, 31초: 나쁨
            "2026-11-25 13:02:00 404 100 \"GET /api/kosdaq-volume?cached=1 HTTP/1.1\"",        // 캐시 404: 5xx 아님
            "2026-11-25 13:03:00 200 1000 \"GET /trader HTTP/1.1\"",                           // /api 아님
            "2026-11-25 07:00:00 500 1000 \"GET /api/heatmap HTTP/1.1\"",                      // 장 전
            "2026-11-28 13:00:00 500 1000 \"GET /api/heatmap HTTP/1.1\"",                      // 토요일
            "garbage")
        val got = accessSlis(log).associate { it.name to (it.good to it.total) }
        assertEquals(4 to 5, got["/api 응답 5xx 아님 (T3)"])
        assertEquals(2 to 3, got["캐시 응답 0.5초 안"])
        assertEquals(1 to 1, got["히트맵 10초 안"])
        assertEquals(0 to 1, got["배당 30초 안"])
        assertEquals(0 to 0, got["주간 평균 90초 안"])
    }

    /** 14일 전 날(11-11)은 시각 단위로 잘려 15:40 한 칸만 남음 → 세지 않음. 예전에 쌓아 둔 날(11-05)은 slo.db에서 */
    @Test
    fun dayCountsSkipTrimmedDaysAndKeepHistory(@TempDir dir: Path) {
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("kosdaq.db")}").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE kosdaq_tick (at TEXT PRIMARY KEY, payload TEXT NOT NULL)") }
            val slots = (0 until PER_DAY).map { "2026-11-20T%02d:%02d".format(9 + it * 5 / 60, it * 5 % 60) }
            assertEquals("2026-11-20T15:40", slots.last())
            c.prepareStatement("INSERT INTO kosdaq_tick VALUES (?, '[]')").use { st ->
                for (t in listOf("2026-11-11T15:40") + slots) {
                    st.setString(1, t); st.executeUpdate()
                }
            }
        }
        val store = Store(dir)
        store.write { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE day_count (tbl TEXT NOT NULL, day TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (tbl, day))")
                it.execute("INSERT INTO day_count VALUES ('kosdaq_tick', '2026-11-05', 80)")
            }
        }
        val counts = store.dayCounts("kosdaq.db", "kosdaq_tick", "2026-11", now.toLocalDate())
        assertEquals(mapOf("2026-11-05" to 80, "2026-11-20" to 81), counts)
        assertEquals(counts, store.dayCounts("kosdaq.db", "kosdaq_tick", "2026-11", now.toLocalDate()), "다시 불러도 같음")
        assertEquals(counts, store.dayCounts("없음.db", "kosdaq_tick", "2026-11", now.toLocalDate()), "보관 중 값이 없어도 쌓인 값")
        assertEquals(mapOf("2026-11-05" to 80), budget(counts, now).short)
    }

    /** 다른 DB가 하나도 없어도 화면·점검이 나와야 함 */
    @Test
    fun emptyDataDirStillRenders(@TempDir dir: Path) {
        val store = Store(dir)
        val slis = store.slis(now)
        assertEquals(12, slis.size)
        assertTrue(slis.all { it.met == null })
        val page = render(Snapshot("2026-11-25 16:00:00", "2026-11", store.budget(now), null, slis))
        assertTrue("SLO 모니터" in page && "확인 전" in page && "셀 이벤트 없음" in page)
        assertEquals(0, check(arrayOf("check", "--max", "100", "--fail-slo"), dir, now))
        assertTrue(Files.exists(dir.resolve("slo.db")))
    }

    @Test
    fun probeAlertsOnceWhenDownAndOnRecovery() {
        assertEquals(Triple(false, 0, null), nextState(false, 0, true))
        assertEquals(Triple(false, 2, null), nextState(false, 1, false))
        assertEquals(Triple(true, 3, "down"), nextState(false, 2, false))
        assertEquals(Triple(true, 4, null), nextState(true, 3, false), "장애 중에는 다시 알리지 않음")
        assertEquals(Triple(false, 0, "recovered"), nextState(true, 4, true))
    }

    /** 11-26처럼 Spring은 살아 있고 인터넷만 끊긴 경우: SLI는 목표를 넘어도 check가 끊김으로 실패해야 함 */
    @Test
    fun externalOutageFailsDailyCheck(@TempDir dir: Path) {
        assertEquals(listOf(Outage("b", "d", 3)), outages(listOf("a" to true, "b" to false, "c" to false, "d" to false, "e" to true, "f" to false, "g" to false)))
        assertEquals(LocalDateTime.of(2026, 11, 27, 16, 0), lastCheck(LocalDateTime.of(2026, 11, 30, 16, 0)), "월요일 → 금요일")
        val store = Store(dir)
        store.write { c ->
            c.createStatement().use { it.execute("CREATE TABLE probe (at TEXT PRIMARY KEY, ok INTEGER NOT NULL, ms INTEGER NOT NULL, ext INTEGER)") }
            c.prepareStatement("INSERT INTO probe VALUES (?, 1, 5, ?)").use { st ->
                var t = LocalDateTime.of(2026, 11, 24, 16, 0)
                while (t < now) {
                    st.setString(1, t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    // 11-25 08:36~10:19 끊김
                    st.setInt(2, if (t >= LocalDateTime.of(2026, 11, 25, 8, 36) && t < LocalDateTime.of(2026, 11, 25, 10, 20)) 0 else 1)
                    st.executeUpdate()
                    t = t.plusSeconds(30)
                }
            }
        }
        val sli = store.externalSli("2026-11")
        assertEquals(2880 - 208, sli.good)
        val cut = store.externalOutages(lastCheck(now))
        assertEquals(listOf(Outage("2026-11-25 08:36:00", "2026-11-25 10:19:30", 208)), cut)
        assertTrue("외부 연결 끊김" in report(store.budget(now), store.slis(now), now, cut))
        assertEquals(1, check(arrayOf("check", "--fail-slo"), dir, now))
        assertEquals(0, check(arrayOf("check", "--max", "50"), dir, now), "배포 전 확인은 예산만 봄")
    }
}
