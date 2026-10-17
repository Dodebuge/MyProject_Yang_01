package kb.app

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.function.Function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 정리된 응답 값(Long, Double, String, null)을 다루는 도우미. Python의 `int(x or 0)`, `float(x or 0)`에 해당합니다. */
internal object Values {

    @JvmStatic
    fun str(v: Any?): String = v?.toString() ?: ""

    /** 숫자 또는 숫자 문자열("1,073,000" 포함). 비었거나 숫자가 아니면 0. */
    @JvmStatic
    fun num(v: Any?): Double {
        if (v is Number) {
            return v.toDouble()
        }
        val s = str(v).replace(",", "").trim()
        return if (s.isEmpty()) 0.0 else s.toDoubleOrNull() ?: 0.0
    }

    /** 숫자로 읽을 수 없으면 null (깨진 값 거르기용). */
    @JvmStatic
    fun numOrNull(v: Any?): Double? {
        if (v is Number) {
            return v.toDouble()
        }
        val s = str(v).trim()
        return if (s.isEmpty()) 0.0 else s.toDoubleOrNull()
    }

    @JvmStatic
    fun lng(v: Any?): Long = Math.round(num(v))

    /** 병렬 실행. fn 안에서 예외를 처리해야 합니다(처리하지 않은 예외는 RuntimeException으로 다시 던짐). */
    @JvmStatic
    fun <T, R> parallel(items: List<T>, workers: Int, fn: Function<T, R>): List<R> {
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = items.map { item -> pool.submit<R> { fn.apply(item) } }
            return futures.map { it.get() }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw if (cause is RuntimeException) cause else RuntimeException(cause)
        } finally {
            pool.shutdown()
        }
    }

    /**
     * 외부 API를 코루틴으로 동시에 부릅니다 (Kotlin 전용). 결과는 items 순서.
     * KB 호출은 블로킹 HTTP라 Dispatchers.IO에서 돌리고, Semaphore로 한 번에 workers개까지만 (KB 서버 부담·밀린 응답 줄이기).
     * fn에서 처리하지 않은 예외가 나면 나머지 호출은 취소하고 그 예외를 그대로 던집니다.
     */
    fun <T, R> parallelIO(items: List<T>, workers: Int, fn: (T) -> R): List<R> = runBlocking {
        val permits = Semaphore(workers)
        items.map { item -> async(Dispatchers.IO) { permits.withPermit { fn(item) } } }.awaitAll()
    }
}
