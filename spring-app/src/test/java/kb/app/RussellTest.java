package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * -Drussell.live=true 를 주면 데이터 폴더의 russell2000.csv 종목을 실제 KB에서 찾아 봅니다 (KB 시세 약 2,000번 조회, 10초 남짓).
 *   ./gradlew test --tests RussellTest -Drussell.live=true                                (spring-app 폴더에서, 저장소 루트의 .env·CSV)
 *   ./gradlew test --tests RussellTest -Drussell.live=true -Dkb.data.dir=/opt/kb_openapi_sample
 */
class RussellTest {

    private static final double MIN_FOUND = 0.95;  // 상장폐지·합병 직후 종목이 CSV에 남아 있을 수 있어 100%는 요구하지 않음

    @Test
    @EnabledIfSystemProperty(named = "russell.live", matches = "true")
    @SuppressWarnings("unchecked")
    void liveCsvTickersAreFoundOnKb() throws Exception {
        Path dir = Path.of(System.getProperty("kb.data.dir", "..")).toAbsolutePath().normalize();
        Russell r = new Russell(KBClient.fromEnv(dir.resolve(".env")), dir.resolve("russell2000.csv"), Sqlite.jdbc(dir.resolve("russell.db")));
        Map<String, Object> c = r.check();
        int listed = (Integer) c.get("listed");
        int found = (Integer) c.get("found");
        List<String> missing = (List<String>) c.get("missing");
        List<String> renamed = (List<String>) c.get("renamed");
        System.out.println("[러셀 2000 점검] " + dir.resolve("russell2000.csv") + ": " + listed + "종목 중 " + found + "종목 KB에서 찾음");
        System.out.println("  표기를 바꿔 찾은 종목 " + renamed.size() + "개: " + renamed);
        System.out.println("  못 찾은 종목 " + missing.size() + "개: " + missing);
        assertTrue(found >= listed * MIN_FOUND,
                () -> "KB에서 찾은 종목이 " + (int) (MIN_FOUND * 100) + "% 미만입니다 (" + found + "/" + listed + "). 못 찾은 종목: " + missing);
    }

    @Test
    void readsISharesHoldingsCsv() throws Exception {
        // iShares CSV 모양: 위 안내 줄, 현금 행, 중복 행, 아래 안내문 (russell.py 자체 점검과 같음)
        Path csv = Files.createTempDirectory("r2k").resolve("russell2000.csv");
        Files.write(csv, ("﻿iShares Russell 2000 ETF\nFund Holdings as of,\"Oct 15, 2026\"\n \n"
                + "Ticker,Name,Sector,Asset Class,Market Value,Weight (%),Exchange\n"
                + "\"CRDO\",\"CREDO TECHNOLOGY\",\"Information Technology\",\"Equity\",\"1\",\"0.5\",\"NASDAQ\"\n"
                + "\"MOGA\",\"MOOG INC CLASS A\",\"Industrials\",\"Equity\",\"1\",\"0.1\",\"New York Stock Exchange Inc.\"\n"
                + "\"USD\",\"USD CASH\",\"Cash and/or Derivatives\",\"Cash\",\"1\",\"0.1\",\"-\"\n"
                + "\"CRDO\",\"CREDO TECHNOLOGY\",\"Information Technology\",\"Equity\",\"1\",\"0.5\",\"NASDAQ\"\n"
                + " \n\"The content contained herein is owned or licensed by BlackRock\"\n").getBytes(StandardCharsets.UTF_8));
        List<Map<String, String>> list = Russell.loadList(csv);
        assertEquals("CRDO:CREDO TECHNOLOGY:NAS,MOGA:MOOG INC CLASS A:NYS",
                list.stream().map(m -> m.get("code") + ":" + m.get("name") + ":" + m.get("exchange")).collect(Collectors.joining(",")));
        Files.write(csv, "Ticker\r\nAAPL\r\nmsft\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("AAPL,MSFT", Russell.loadList(csv).stream().map(m -> m.get("code")).collect(Collectors.joining(",")));
    }

    @Test
    void mapsExchangeNames() {
        assertEquals(Arrays.asList("NAS", "NYS", "AMX", "AMX", ""), Arrays.stream(
                new String[]{"NASDAQ", "New York Stock Exchange Inc.", "Nyse Mkt Llc", "Cboe BZX", "-"}).map(Russell::exchangeOf).collect(Collectors.toList()));
    }

    @Test
    void ticksEveryThirtyMinutesInNewYorkHours() throws Exception {
        Ticks t = new Russell(new KBClient("", "", "", "", ""), Path.of("unused.csv"),
                Sqlite.jdbc(java.nio.file.Files.createTempDirectory("russell").resolve("russell.db"))).ticks;
        assertEquals(LocalDateTime.of(2026, 10, 16, 10, 0), t.nextTick(LocalDateTime.of(2026, 10, 16, 9, 0)));
        assertEquals(LocalDateTime.of(2026, 10, 16, 10, 30), t.nextTick(LocalDateTime.of(2026, 10, 16, 10, 5)));
        assertEquals(LocalDateTime.of(2026, 10, 16, 16, 30), t.nextTick(LocalDateTime.of(2026, 10, 16, 16, 10)));
        assertEquals(LocalDateTime.of(2026, 10, 19, 10, 0), t.nextTick(LocalDateTime.of(2026, 10, 16, 16, 30)));
    }
}
