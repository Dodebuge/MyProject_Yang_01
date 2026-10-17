package kb.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.TreeMap

/**
 * 마지막 API 응답 (화면 첫 로딩용, daily_store.last_file/save_last/read_last와 같은 파일).
 * API가 성공할 때마다 응답 JSON을 last_api/&lt;경로&gt;[_&lt;파라미터&gt;].txt에 덮어써 둡니다.
 * 화면은 먼저 ?cached=1로 이 값을 받아 바로 그리고, 실제 API 응답이 오면 교체합니다.
 */
internal class LastResponse(private val dir: Path) {

    /** 저장할 API면 파일 경로, 아니면 null. 예: ("/api/heatmap", {market=kr}) -> last_api/heatmap_market=kr.txt */
    fun file(path: String, params: Map<String, Array<String>>): Path? {
        val api = path.removePrefix("/api/")
        if (api !in APIS) {
            return null
        }
        val kv = TreeMap(params).entries
            .filter { it.key !in IGNORED && it.value.isNotEmpty() }
            .joinToString("&") { it.key + "=" + it.value[0] }
        val name = (api + (if (kv.isEmpty()) "" else "_$kv")).replace(Regex("""[^\w=&.-]"""), "_")
        return dir.resolve("$name.txt")
    }

    companion object {
        private val APIS = setOf(
            "heatmap", "quarters", "holdings", "dividends", "recurring", "transactions", "chart", "kosdaq-volume", "russell-volume")  // 첫 로딩이 느린 API만
        private val IGNORED = setOf("refresh", "cached", "recent")  // recent=0도 같은 차트

        @JvmStatic
        fun cached(params: Map<String, Array<String>>): Boolean {
            val v = params["cached"]
            return v != null && v.isNotEmpty() && (v[0] == "1" || v[0] == "true")
        }

        @JvmStatic
        @Throws(IOException::class)
        fun read(file: Path): String? = try {
            Files.readString(file)
        } catch (e: NoSuchFileException) {
            null
        }

        /**
         * 읽는 쪽이 반쯤 쓴 파일을 보지 않게 임시 파일에 쓰고 통째로 교체합니다.
         * 임시 파일 이름은 요청마다 다르게: 같은 API 요청 두 개가 동시에 끝나면(예: 러셀 주간 평균을 두 화면이 함께 기다림)
         * 고정 이름(.tmp)은 한쪽이 옮긴 파일을 다른 쪽이 다시 옮기려다 NoSuchFileException이 났습니다. 나중에 끝난 쪽이 이깁니다.
         * synchronized: Windows는 같은 파일을 동시에 교체하면 AccessDeniedException이라 한 서버 안에서는 하나씩 (리눅스는 없어도 안전).
         */
        @JvmStatic
        @Synchronized
        @Throws(IOException::class)
        fun save(file: Path, body: String) {
            Files.createDirectories(file.parent)
            val tmp = Files.createTempFile(file.parent, "${file.fileName}.", ".tmp")
            try {
                Files.writeString(tmp, body)
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)  // 옮기지 못했을 때 남은 임시 파일
            }
        }
    }
}
