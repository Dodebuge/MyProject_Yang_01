package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RussellTest {

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
    void ticksEveryThirtyMinutesInNewYorkHours() {
        Ticks t = new Russell(null, Path.of("unused.csv"), Path.of("unused.db")).ticks;
        assertEquals(LocalDateTime.of(2026, 10, 16, 10, 0), t.nextTick(LocalDateTime.of(2026, 10, 16, 9, 0)));
        assertEquals(LocalDateTime.of(2026, 10, 16, 10, 30), t.nextTick(LocalDateTime.of(2026, 10, 16, 10, 5)));
        assertEquals(LocalDateTime.of(2026, 10, 16, 16, 30), t.nextTick(LocalDateTime.of(2026, 10, 16, 16, 10)));
        assertEquals(LocalDateTime.of(2026, 10, 19, 10, 0), t.nextTick(LocalDateTime.of(2026, 10, 16, 16, 30)));
    }
}
