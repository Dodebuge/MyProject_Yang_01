package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LastResponseTest {

    /** 같은 API 응답을 여러 요청이 동시에 저장해도 실패하지 않고, 임시 파일이 남지 않음 (서버에서 난 NoSuchFileException 재현). */
    @Test
    void concurrentSavesOfSameFileDoNotFail() throws Exception {
        Path dir = Files.createTempDirectory("last_api");
        Path file = dir.resolve("russell-volume.txt");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> jobs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String body = "{\"n\":" + i + "}";
            jobs.add(pool.submit(() -> {
                LastResponse.save(file, body);
                return null;
            }));
        }
        for (Future<?> f : jobs) {
            f.get();  // 저장 중 예외가 나면 여기서 실패
        }
        pool.shutdown();
        assertTrue(LastResponse.read(file).matches("\\{\"n\":\\d+}"));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("russell-volume.txt"), files.map(p -> p.getFileName().toString()).collect(Collectors.toList()));
        }
    }
}
