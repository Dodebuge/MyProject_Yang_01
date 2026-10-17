package kb.app

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Values.parallelIO: 코루틴 동시 조회 (블로킹 호출 흉내는 Thread.sleep). */
class ValuesTest {

    @Test
    fun parallelIOKeepsOrderAndLimitsConcurrency() {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val out = Values.parallelIO((1..40).toList(), 8) { n ->
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            Thread.sleep(20)  // 블로킹 HTTP 흉내
            running.decrementAndGet()
            n * 10
        }
        assertEquals((1..40).map { it * 10 }, out, "결과는 입력 순서")
        assertTrue(peak.get() in 2..8, "동시에 최대 8개 (실제 ${peak.get()})")
    }

    @Test
    fun parallelIORethrowsFailure() {
        val e = assertThrows<IllegalStateException> {
            Values.parallelIO(listOf(1, 2, 3), 2) { if (it == 2) throw IllegalStateException("깨짐") else it }
        }
        assertEquals("깨짐", e.message)
    }
}
