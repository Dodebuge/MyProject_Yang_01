package kb.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1분 이내 단타 시뮬레이션 (코스닥 1분봉 minutes.db kosdaq_min1, Spring·Java 서버만). 장중 5분 기록(Ticks)으로는 1분 안의 매매를 잴 수 없어 따로 둡니다.
 * C처럼 씁니다: 종목 하루 = struct Stock { t[], o[], h[], l[], c[], v[], val[] } 배열, 함수는 static.
 *
 * 매수 신호 (봉 i가 끝난 시점에 아는 값만): start~until 사이,
 *   봉 거래량 ≥ 앞 lookback개 봉 평균 × volX  그리고  봉 상승률(종가/시가 − 1) ≥ up%  그리고  봉 거래대금 ≥ minValue 백만원.
 * 매수: 바로 다음 분 봉(i+1)의 시가. 다음 분에 거래가 없으면(봉이 빠짐) 안 삼.
 * 매도: 산 봉부터 hold분 안의 봉마다 저가가 −sl%면 손절, 고가가 +tp%면 익절 (한 봉에 둘 다면 순서를 몰라 손절로 봄). 아니면 hold분째 봉 종가 "시간".
 *   손절은 손절가 지정가로 신청 (갭으로 손절가 아래면 다시 손절가에 올 때까지 hold를 넘겨도 기다림). 장마감 30분 전(15:00)부터는 시장가.
 * 손익 = 수량 × 매도가 × (1 − cost%) − 매수 금액. 판 뒤 다음 봉부터 같은 종목을 다시 살 수 있음. 동시에 들고 있는 금액 한도는 없음.
 * vwap이면 KB 실제 거래대금 종목만 보고, 신호 봉 종가가 그때까지의 VWAP(09:00부터) 위일 때만 삼.
 * wick15면 매수 신호만 '15분봉 상단 꼬리 없음'으로, big > 0이면 '15분봉 장대양봉'으로 바꿈 (run15). maSell이면 이평이 꺾일 때, maBelow면 종가가 이평 아래일 때 매도.
 * ride면 이평 위에서 사고 익절하면 다시 사는 반복 매매 (runRide).
 * ponytail: 호가 단위·체결 미끄러짐은 cost%로만 봄. 실제 주문을 붙이면 신호 봉 다음 시가보다 비싸게 사질 수 있으니 cost를 넉넉히.
 */
final class Scalp {

    private Scalp() {
    }

    /** 시뮬레이션 값 (struct scalp_params) */
    static final class Params {
        double volX = 5;          // 봉 거래량이 앞 봉 평균의 몇 배
        int lookback = 20;        // 평균을 낼 앞 봉 개수
        double up = 1;            // 신호 봉 상승률 %
        double minValue = 200;    // 신호 봉 거래대금 (백만원)
        double tp = 1;            // 익절 % (0이면 안 함)
        double sl = 1;            // 손절 % (0이면 안 함)
        int hold = 1;             // 최대 보유 (분)
        double cost = 0.23;       // 매도 금액에서 뺄 비용 % (세금·수수료·미끄러짐)
        double amount = 1_000_000;  // 한 번 매수 금액
        int start = 9 * 60 + 5;   // 이 시각(분) 전 신호는 안 씀: 동시호가 직후 봉은 거래량이 튐
        int until = 15 * 60 + 15; // 이 시각(분) 뒤 신호는 안 씀: 15:20부터 종가 동시호가
        int marketFrom = 15 * 60; // 장마감(15:30) 30분 전부터 손절은 시장가, 그 전에는 손절가 지정가
        boolean wick15;           // 매수 신호를 '15분봉 상단 꼬리 없음'으로 (run15). volX·lookback·up은 안 씀
        double big;               // > 0이면 매수 신호를 '15분봉 장대양봉(몸통 ≥ big%, 몸통 ≥ 고가−저가의 70%)'으로 (run15)
        int maSell;               // > 0이면 1분봉 maSell분 이평이 꺾이면(종가 < maSell봉 전 종가) 다음 봉 시가에 시장가 매도
        int maBelow;              // > 0이면 1분봉 종가가 maBelow분 이평 아래로 내려가면 다음 봉 시가에 시장가 매도
        double dayStop;           // > 0이면 그날 실현 손익(비용 뺀 값)이 이 금액(원) 이상이 된 뒤에는 새로 안 삼 (들고 있는 것은 그대로 팖)
        double dayLoss;           // > 0이면 그날 실현 손익이 −이 금액(원) 이하가 된 뒤에는 새로 안 삼
        double budget;            // > 0이면 동시에 들고 있는 매수 금액 합계 한도 (원, 예수금)
        double maxGap;            // ride 매수: 산 값이 maBelow분 이평보다 이만큼(%) 넘게 위면 안 삼 (0이면 안 봄, 급등 끝물 방어)
        double maxDayUp;          // ride 매수: 산 값이 당일 시가보다 이만큼(%) 넘게 위면 안 삼 (0이면 안 봄)
        boolean aligned;          // ride 첫 매수: 1분봉 이평 정배열(5 > 20 > 60 > 120분, 120봉 이상)일 때만
        double dip;               // ride: 익절 뒤 판 값보다 이만큼(%) 아래에 지정가로 다시 삼 (0이면 이평 위면 바로 다시 삼)
        boolean ride;             // 이평 타기 (runRide): 종가가 maBelow분 이평 위면 사고, 익절하면 다시 사고, 이평 아래로 가면 팔고 그날 끝
        double prevVol;           // > 0이면 전날 같은 시각까지(09:00부터) 누적 거래량 대비 이만큼(%) 이상 늘어난 종목만 (100 = 2배)
        boolean vwap;             // 매수 신호에 '신호 봉 종가 > VWAP'를 더하고, VWAP를 낼 수 있는 종목(realValue)만
    }

    static final int CLOSE = 15 * 60 + 30;  // 정규장 마감: 이 뒤 NXT 봉에서는 팔지 않음

    /** 한 종목 하루 1분봉 (거래가 없던 분은 빠져 있음). t = 분 (9:01 = 541) */
    static final class Stock {
        String code;
        String name;
        int[] t;
        double[] o;
        double[] h;
        double[] l;
        double[] c;
        double[] v;
        double[] val;
        double[] cum;  // cum[i] = v[0] + … + v[i-1] (앞 봉 평균용)
        double[] cumC; // cumC[i] = c[0] + … + c[i-1] (종가 이평용)
        double[] vwap; // 09:00부터 봉 i까지 거래대금 합 / 거래량 합 (09:00 전 봉은 0)
        String source;      // kb | toss
        Stock prev;         // prevVol: 전날 같은 종목 (출처가 같을 때만, 토스 거래량은 NXT까지 합쳐져 KB와 비교 못 함)
        double dayOpen;     // 09:00 이후 첫 봉 시가 (없으면 0)
        boolean realValue;  // KB에서 받아 거래대금이 실제 값이고 거래가 있는 봉마다 거래대금이 있음 (토스는 (고+저+종)/3 × 거래량 추정)
    }

    /** 거래 하나 */
    static final class Trade {
        String date;
        String code;
        String name;
        int signalAt;
        int buyAt;
        double buyPrice;
        int sellAt;
        double sellPrice;
        String reason;
        long qty;
        long invested;
        long pnl;
    }

    static int mins(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(2, 4));
    }

    static String hm(int m) {
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    /** MinuteBars 행 {종목, 이름, bars JSON [[HHMM, 시가, 고가, 저가, 종가, 거래량, 거래대금], ...]} -> Stock */
    static Stock parse(String code, String name, String bars) {
        return parse(code, name, "kb", bars);
    }

    static Stock parse(String code, String name, String source, String bars) {
        JsonArray a = JsonParser.parseString(bars).getAsJsonArray();
        Stock s = new Stock();
        int n = a.size();
        s.code = code;
        s.name = name;
        s.t = new int[n];
        s.o = new double[n];
        s.h = new double[n];
        s.l = new double[n];
        s.c = new double[n];
        s.v = new double[n];
        s.val = new double[n];
        s.cum = new double[n + 1];
        s.cumC = new double[n + 1];
        s.vwap = new double[n];
        s.source = source;
        s.realValue = "kb".equals(source);
        double cv = 0;
        double cval = 0;
        for (int i = 0; i < n; i++) {
            JsonArray b = a.get(i).getAsJsonArray();
            s.t[i] = mins(b.get(0).getAsString());
            s.o[i] = b.get(1).getAsDouble();
            s.h[i] = b.get(2).getAsDouble();
            s.l[i] = b.get(3).getAsDouble();
            s.c[i] = b.get(4).getAsDouble();
            s.v[i] = b.get(5).getAsDouble();
            s.val[i] = b.get(6).getAsDouble();
            s.cum[i + 1] = s.cum[i] + s.v[i];
            s.cumC[i + 1] = s.cumC[i] + s.c[i];
            if (s.t[i] >= 9 * 60) {
                cv += s.v[i];
                cval += s.val[i];
                s.realValue &= s.v[i] <= 0 || s.val[i] > 0;
            }
            s.vwap[i] = cv > 0 ? cval / cv : 0;
            if (s.dayOpen == 0 && s.t[i] >= 9 * 60) {
                s.dayOpen = s.o[i];
            }
        }
        return s;
    }

    /** 한 종목 하루를 돌려 거래를 out에 더함 */
    static void runStock(String date, Stock s, Params p, List<Trade> out) {
        if (p.ride) {
            runRide(date, s, p, out);
            return;
        }
        if (p.wick15 || p.big > 0) {
            run15(date, s, p, out);
            return;
        }
        int n = s.t.length;
        for (int i = p.lookback; i < n - 1; i++) {
            double avg = (s.cum[i] - s.cum[i - p.lookback]) / p.lookback;
            if (s.t[i] < p.start || s.t[i] > p.until || avg <= 0 || s.o[i] <= 0
                    || s.v[i] < avg * p.volX || s.c[i] / s.o[i] - 1 < p.up / 100 || s.val[i] < p.minValue * 1_000_000
                    || s.t[i + 1] != s.t[i] + 1 || p.vwap && s.c[i] <= s.vwap[i] || !prevOk(s, s.t[i] + 1, p)) {
                continue;
            }
            int j = trade(date, s, s.t[i], i + 1, p, out);
            if (j >= 0) {
                i = j;  // 판 봉 다음부터 다시
            }
        }
    }

    /**
     * 15분봉 신호: start(09:30)부터 15분씩 묶은 봉(09:30~09:44, 09:45~09:59 …)이 거래대금 ≥ minValue 백만원이고
     *   wick15: 양봉이고 종가 = 고가 (상단 꼬리 없음)
     *   big > 0: 장대양봉 = 몸통(종가 − 시가) ≥ 시가의 big% 이고 몸통 ≥ (고가 − 저가)의 70%
     * 이면 그 봉이 끝난 뒤 첫 1분봉 시가에 시장가로 삼 (until까지). 판 뒤 끝난 15분봉부터 다시 봄.
     */
    static void run15(String date, Stock s, Params p, List<Trade> out) {
        int n = s.t.length;
        int i = 0;
        int busy = -1;  // 이 시각까지 들고 있었음
        for (int b = p.start; b + 15 <= p.until; b += 15) {
            while (i < n && s.t[i] < b) {
                i++;
            }
            if (i == n) {
                return;
            }
            int e = i;
            double hi = 0;
            double lo = Double.MAX_VALUE;
            double val = 0;
            while (e < n && s.t[e] < b + 15) {
                hi = Math.max(hi, s.h[e]);
                lo = Math.min(lo, s.l[e]);
                val += s.val[e];
                e++;
            }
            if (e == i || e == n || b + 15 <= busy || s.t[e] > p.until) {
                continue;  // 그 15분에 거래 없음, 뒤에 봉 없음, 들고 있던 중에 끝난 봉
            }
            double open = s.o[i];
            double close = s.c[e - 1];
            boolean ok = p.big > 0 ? close / open - 1 >= p.big / 100 && close - open >= 0.7 * (hi - lo) : close == hi && close > open;
            if (!ok || val < p.minValue * 1_000_000 || p.vwap && close <= s.vwap[e - 1] || !prevOk(s, b + 15, p)) {
                continue;
            }
            int j = trade(date, s, b, e, p, out);
            if (j >= 0) {
                busy = s.t[j];
            }
        }
    }

    /**
     * 이평 타기: start~until 사이 봉 i의 종가가 maBelow분 이평 위이고 prevVol 조건(전날 대비 거래량)이면 다음 봉 시가에 시장가로 삼.
     * +tp%에 닿으면 익절하고 다시 삼: dip이 0이면 그 봉 종가가 아직 이평 위일 때 다음 봉에서 바로,
     *   dip > 0이면 판 값보다 dip% 아래에 지정가 매수를 걸어 저가가 닿는 봉에서 (기다리는 중 종가가 이평 아래면 취소하고 그날 끝).
     * 종가가 이평 아래로 가면 다음 봉 시가에 팔고 그 종목은 그날 끝.
     */
    static void runRide(String date, Stock s, Params p, List<Trade> out) {
        int n = s.t.length;
        for (int i = 0; i < n - 1; i++) {
            if (s.t[i] < p.start || s.t[i] > p.until || s.c[i] <= ma(s, i, p.maBelow) || !prevOk(s, s.t[i] + 1, p)
                    || p.aligned && !aligned(s, i) || !calm(s, i, s.c[i], p)) {
                continue;
            }
            int j = trade(date, s, s.t[i], i + 1, 0, p, out);
            while (p.dip > 0 && j >= 0 && "익절".equals(out.get(out.size() - 1).reason)) {
                double limit = out.get(out.size() - 1).sellPrice * (1 - p.dip / 100);
                int k = j + 1;  // 익절한 봉 안의 순서는 몰라 다음 봉부터
                for (; k < n && s.t[k] <= p.until; k++) {
                    if (s.l[k] <= limit && calm(s, k - 1, Math.min(s.o[k], limit), p)) {
                        break;  // 지정가 체결 (너무 오른 자리면 체결로 안 보고 계속 기다림)
                    }
                    if (s.c[k] <= ma(s, k, p.maBelow)) {
                        return;  // 지정가에 닿기 전에 이평 아래: 그날 끝
                    }
                }
                if (k == n || s.t[k] > p.until) {
                    return;  // 장 끝, until 지남
                }
                j = trade(date, s, s.t[j], k, Math.min(s.o[k], limit), p, out);  // 시가가 지정가 아래면 그 시가에 체결
            }
            if (j < 0 || !"익절".equals(out.get(out.size() - 1).reason)) {
                return;  // 이평 아래(또는 장마감)로 팔았으면 그날 끝
            }
            i = j - 1;  // 익절한 봉의 종가부터 다시 봄
        }
    }

    /**
     * 봉 e 시가에 사서 파는 거래 하나를 out에 더하고 판 봉 번호를 돌려줌 (1주도 못 사면 -1).
     * 매도: e부터 hold분 안(15:30까지)의 봉마다 손절·익절·이평 하락(maSell)·이평 아래(maBelow)를 보고, 아니면 마지막 본 봉 종가 "시간".
     */
    static int trade(String date, Stock s, int signalAt, int e, Params p, List<Trade> out) {
        return trade(date, s, signalAt, e, 0, p, out);
    }

    /** price > 0이면 봉 e에서 그 값에 산 것으로 (지정가 체결) */
    static int trade(String date, Stock s, int signalAt, int e, double price, Params p, List<Trade> out) {
        int n = s.t.length;
        double buy = price > 0 ? price : s.o[e];
        long qty = buy <= 0 ? 0 : (long) Math.floor(p.amount / buy);
        if (qty < 1) {
            return -1;
        }
        double slPx = buy * (1 - p.sl / 100);
        double tpPx = buy * (1 + p.tp / 100);
        int j = e;
        double sell = s.c[j];
        String why = "시간";
        for (; j < n && s.t[j] < s.t[e] + p.hold && (j == e || s.t[j] <= CLOSE); j++) {
            if (p.sl > 0 && s.l[j] <= slPx) {
                if (s.t[j] >= p.marketFrom) {
                    sell = Math.min(s.o[j], slPx);  // 장마감 30분 전부터는 시장가: 봉이 손절가 아래에서 시작하면 그 시가
                    why = "손절 시장가";
                    break;
                }
                // 손절가 지정가 매도: 고가가 손절가 이상인 봉에서 체결 (시가가 손절가 아래로 갭이면 다시 오를 때까지 기다림)
                while (j < n && s.h[j] < slPx && s.t[j] < p.marketFrom) {
                    j++;
                }
                if (j == n) {
                    j--;
                    sell = s.c[j];
                    why = "마지막 봉";
                } else if (s.h[j] >= slPx) {
                    sell = slPx;
                    why = "손절";
                } else {
                    sell = s.o[j];  // 장마감 30분 전까지 못 팔면 지정가 취소, 시장가
                    why = "손절 시장가";
                }
                break;
            }
            if (p.tp > 0 && s.h[j] >= tpPx) {
                sell = Math.max(s.o[j], tpPx);
                why = "익절";
                break;
            }
            boolean down = p.maSell > 0 && j >= p.maSell && s.c[j] < s.c[j - p.maSell];  // 이평 하락: (c[j] − c[j−N]) / N < 0
            boolean below = p.maBelow > 0 && s.c[j] < ma(s, j, p.maBelow);
            if (down || below) {
                if (j + 1 < n && s.t[j + 1] <= CLOSE) {
                    j++;
                    sell = s.o[j];  // 봉이 끝나야 알므로 다음 봉 시가에 시장가
                } else {
                    sell = s.c[j];
                }
                why = down ? "이평 하락" : "이평 아래";
                break;
            }
            sell = s.c[j];
        }
        if ("시간".equals(why)) {
            j--;  // 마지막으로 본 봉
        }
        Trade tr = new Trade();
        tr.date = date;
        tr.code = s.code;
        tr.name = s.name;
        tr.signalAt = signalAt;
        tr.buyAt = s.t[e];
        tr.buyPrice = buy;
        tr.sellAt = s.t[j];
        tr.sellPrice = sell;
        tr.reason = why;
        tr.qty = qty;
        tr.invested = Math.round(qty * buy);
        tr.pnl = Math.round(qty * sell * (1 - p.cost / 100) - tr.invested);
        out.add(tr);
        return j;
    }

    /** maxGap·maxDayUp: 봉 i까지 본 뒤 px에 사도 되는지 (이평에서 너무 멀거나 당일 너무 오른 자리면 false) */
    static boolean calm(Stock s, int i, double px, Params p) {
        return (p.maxGap <= 0 || px <= ma(s, i, p.maBelow) * (1 + p.maxGap / 100))
                && (p.maxDayUp <= 0 || s.dayOpen <= 0 || px <= s.dayOpen * (1 + p.maxDayUp / 100));
    }

    /** 봉 i에서 1분봉 이평 정배열: 5 > 20 > 60 > 120분 (Signals의 일봉 '이평선 정배열'과 같은 기간, 120봉이 없으면 아님) */
    static boolean aligned(Stock s, int i) {
        return i >= 119 && ma(s, i, 5) > ma(s, i, 20) && ma(s, i, 20) > ma(s, i, 60) && ma(s, i, 60) > ma(s, i, 120);
    }

    /** 봉 j까지 n봉 종가 평균 (앞 봉이 n개보다 적으면 있는 만큼: KB 종목은 09:00 전 봉이 없어 10시 전에는 60봉이 안 됨) */
    static double ma(Stock s, int j, int n) {
        int k = Math.min(n, j + 1);
        return (s.cumC[j + 1] - s.cumC[j + 1 - k]) / k;
    }

    // ------------------------------------------------------------------ 실제 자동매매 (Trader 단타)

    /** Trader 설정 scalp.params -> Params (이평 타기 첫 매수 조건 + 익절·손절·하루 한도). 없는 칸은 시뮬레이션 최종 조합 값 */
    static Params params(Map<String, Object> m) {
        Params p = new Params();
        p.ride = true;
        p.aligned = !Boolean.FALSE.equals(m.get("aligned"));
        p.prevVol = Values.num(m.getOrDefault("prevVol", 200));
        p.maBelow = (int) Values.num(m.getOrDefault("maBelow", 30));
        p.maxDayUp = Values.num(m.getOrDefault("maxDayUp", 20));
        p.tp = Values.num(m.getOrDefault("tp", 2));
        p.sl = Values.num(m.getOrDefault("sl", 2));
        p.amount = Values.num(m.getOrDefault("amount", 300_000));
        p.budget = Values.num(m.getOrDefault("budget", 300_000));
        p.dayStop = Values.num(m.getOrDefault("dayStop", 20_000));
        p.dayLoss = Values.num(m.getOrDefault("dayLoss", 10_000));
        p.start = mins(Values.str(m.getOrDefault("start", "09:05")).replace(":", ""));
        p.until = mins(Values.str(m.getOrDefault("until", "14:50")).replace(":", ""));
        return p;
    }

    /** 1분봉 목록 [[HHMM, 시가, 고가, 저가, 종가, 거래량, 거래대금], ...] (Minutes.bars 모양) -> Stock */
    static Stock of(String code, String name, String source, List<List<Object>> rows) {
        return parse(code, name, source, new com.google.gson.Gson().toJson(rows));
    }

    /**
     * 실제 자동매매(Trader 단타)용 신호: 지금 nowMin(분) 기준으로 살 종목과 팔 종목.
     * stocks: 5분 기록(Ticks.intraday)의 종목 [{code, name, price, change, prevRatio}], bars: 종목들 -> {종목: 오늘 1분봉} (KB, 실패한 종목은 null·빠짐).
     * 후보 = 전날 같은 시각 대비 거래량 prevVol% 이상(5분 기록 기준), 스팩·상한가 근처(+29.5%)·1주가 amount보다 비싼 종목 제외, 증가율 큰 순 maxCands개.
     * 끝난 봉(nowMin 전, 09:00부터)만 봄.
     *   산다: 들고 있지 않고, 마지막 봉이 2분 안이고, 시뮬레이션 runRide 첫 매수 조건 (start~until, 종가 > maBelow 이평, 정배열, calm).
     *   판다: 들고 있는 종목의 마지막 봉 종가 <= maBelow 이평 -> "이평 아래". 익절·손절은 Trader가 5초마다 현재가로 봄.
     * -> {buy: [{code, name, price, ratio}], sell: {종목: 이유}, checked: 1분봉을 본 종목 수}
     */
    static Map<String, Object> live(List<Map<String, Object>> stocks, java.util.function.Function<List<String>, Map<String, List<List<Object>>>> bars,
                                    Params p, int nowMin, java.util.Set<String> held, int maxCands) {
        List<Map<String, Object>> cands = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> r : stocks) {
            String code = Values.str(r.get("code"));
            names.put(code, Values.str(r.get("name")));
            Double ratio = Values.numOrNull(r.get("prevRatio"));
            double price = Values.num(r.get("price"));
            if (held.contains(code) || ratio == null || ratio < p.prevVol || price <= 0 || price > p.amount || Values.num(r.get("change")) >= 29.5
                    || Values.str(r.get("name")).contains("스팩")) {
                continue;
            }
            cands.add(r);
        }
        cands.sort(Comparator.comparingDouble((Map<String, Object> r) -> -Values.num(r.get("prevRatio"))));
        List<String> codes = new ArrayList<>(held);
        for (Map<String, Object> r : cands.subList(0, Math.min(maxCands, cands.size()))) {
            codes.add(Values.str(r.get("code")));
        }
        List<Map<String, Object>> buys = new ArrayList<>();
        Map<String, Object> sells = new LinkedHashMap<>();
        Map<String, List<List<Object>>> all = bars.apply(codes);
        for (String code : codes) {
            List<List<Object>> rows = all.get(code);
            if (rows == null) {
                continue;  // KB 오류: 다음 분에 다시
            }
            List<List<Object>> done = new ArrayList<>();
            for (List<Object> b : rows) {
                int t = mins(Values.str(b.get(0)));
                if (t >= 9 * 60 && t < nowMin) {
                    done.add(b);
                }
            }
            if (done.isEmpty()) {
                continue;
            }
            Stock s = of(code, names.getOrDefault(code, code), "kb", done);
            int i = s.t.length - 1;
            if (held.contains(code)) {
                if (s.c[i] <= ma(s, i, p.maBelow)) {
                    sells.put(code, "이평 아래");
                }
                continue;
            }
            if (s.t[i] < nowMin - 2 || s.t[i] < p.start || s.t[i] > p.until || s.c[i] <= ma(s, i, p.maBelow)
                    || p.aligned && !aligned(s, i) || !calm(s, i, s.c[i], p)) {
                continue;
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("code", code);
            b.put("name", s.name);
            b.put("price", s.c[i]);
            for (Map<String, Object> r : cands) {
                if (code.equals(Values.str(r.get("code")))) {
                    b.put("ratio", r.get("prevRatio"));
                }
            }
            buys.add(b);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buy", buys);
        out.put("sell", sells);
        out.put("checked", codes.size());
        return out;
    }

    /** 09:00부터 t분 전까지의 누적 거래량 */
    static double volBefore(Stock s, int t) {
        int a = java.util.Arrays.binarySearch(s.t, 9 * 60);
        int b = java.util.Arrays.binarySearch(s.t, t);
        a = a < 0 ? -a - 1 : a;
        b = b < 0 ? -b - 1 : b;
        return s.cum[b] - s.cum[a];
    }

    /** prevVol 조건: t분까지 누적 거래량이 전날 같은 시각까지보다 prevVol% 이상 많음 (전날 기록이 없으면 안 삼) */
    static boolean prevOk(Stock s, int t, Params p) {
        if (p.prevVol <= 0) {
            return true;
        }
        if (s.prev == null) {
            return false;
        }
        double pv = volBefore(s.prev, t);
        return pv > 0 && volBefore(s, t) / pv - 1 >= p.prevVol / 100;
    }

    /** days: {날짜: 종목들, 날짜순} 전체를 돌린 거래 (첫날은 prevVol 비교 대상이 없음) */
    static List<Trade> run(Map<String, List<Stock>> days, Params p) {
        if (p.volX <= 0 || p.lookback < 1 || p.hold < 1 || p.amount <= 0 || p.tp < 0 || p.sl < 0) {
            throw new IllegalArgumentException("단타 시뮬레이션 값이 올바르지 않습니다");
        }
        if (p.ride && p.maBelow < 1) {
            throw new IllegalArgumentException("이평 타기(ride)는 maBelow(이평 분)가 필요합니다");
        }
        List<Trade> out = new ArrayList<>();
        Map<String, Stock> before = Map.of();  // 전날 종목
        for (Map.Entry<String, List<Stock>> d : days.entrySet()) {
            Map<String, Stock> today = new java.util.HashMap<>();
            for (Stock s : d.getValue()) {
                today.put(s.code, s);
                Stock pr = before.get(s.code);
                s.prev = pr != null && pr.source.equals(s.source) ? pr : null;
            }
            before = today;
            for (Stock s : d.getValue()) {
                if (p.vwap && !s.realValue) {
                    continue;
                }
                runStock(d.getKey(), s, p, out);
            }
        }
        return p.dayStop > 0 || p.dayLoss > 0 || p.budget > 0 ? limitDay(out, p) : out;
    }

    /**
     * 하루 한도: 날짜마다 산 시각 순으로 보며 (종목은 따로 돌린 뒤 거름)
     *   - 그 시각 전에 판 거래들의 실현 손익 누계가 한 번이라도 dayStop원 이상이거나 −dayLoss원 이하였으면 그 거래부터 그날은 뺌
     *   - budget > 0이면 그 시각에 들고 있는 매수 금액 + 이 거래 금액이 budget을 넘으면 뺌. 뺀 종목은 그날 뒤 거래도 뺌 (익절 뒤 재매수는 앞 거래가 있어야 하므로)
     * 멈추기 전에 산 거래는 원래대로 끝까지 팖. 같은 분이면 판 것이 산 것보다 뒤로 봄 (봉 시가에 사고 봉 안에서 팖).
     * ponytail: 돈이 모자라 못 산 종목은 그날 다시 보지 않음 (돈이 풀린 뒤의 신호를 놓침). 정확히 하려면 엔진을 종목 묶음 시간순으로 돌려야 함.
     */
    static List<Trade> limitDay(List<Trade> trades, Params p) {
        Map<String, List<Trade>> byDay = new LinkedHashMap<>();
        for (Trade t : trades) {
            byDay.computeIfAbsent(t.date, k -> new ArrayList<>()).add(t);
        }
        List<Trade> out = new ArrayList<>();
        for (List<Trade> day : byDay.values()) {
            day.sort(Comparator.comparingInt((Trade t) -> t.buyAt));
            List<Trade> kept = new ArrayList<>();
            java.util.Set<String> skipped = new java.util.HashSet<>();
            for (Trade t : day) {
                List<Trade> sold = new ArrayList<>();
                long open = 0;  // 이 시각에 들고 있는 매수 금액
                for (Trade k : kept) {
                    if (k.sellAt < t.buyAt) {
                        sold.add(k);
                    } else {
                        open += k.invested;
                    }
                }
                sold.sort(Comparator.comparingInt((Trade k) -> k.sellAt));
                long sum = 0;
                boolean stop = false;
                for (Trade k : sold) {
                    sum += k.pnl;
                    stop |= p.dayStop > 0 && sum >= p.dayStop || p.dayLoss > 0 && sum <= -p.dayLoss;
                }
                if (stop) {
                    break;  // 그날은 여기서 멈춤 (뒤 거래는 모두 더 늦게 삼)
                }
                if (skipped.contains(t.code) || p.budget > 0 && open + t.invested > p.budget) {
                    skipped.add(t.code);
                    continue;
                }
                kept.add(t);
            }
            out.addAll(kept);
        }
        return out;
    }

    /** {trades, wins, invested, pnl, ret(매수 금액 대비 %), avg(한 번 평균 %)}. day가 null이면 전체 */
    static Map<String, Object> summary(List<Trade> trades, String day) {
        long n = 0;
        long wins = 0;
        long inv = 0;
        long pnl = 0;
        for (Trade t : trades) {
            if (day != null && !day.equals(t.date)) {
                continue;
            }
            n++;
            wins += t.pnl > 0 ? 1 : 0;
            inv += t.invested;
            pnl += t.pnl;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trades", n);
        m.put("wins", wins);
        m.put("invested", inv);
        m.put("pnl", pnl);
        m.put("ret", inv > 0 ? (Object) (Math.round((double) pnl / inv * 10000) / 100.0) : null);
        return m;
    }

    /** 한 번 돌린 결과 {days, params, total, perDay, trades} */
    static Map<String, Object> simulate(Map<String, List<Stock>> days, Params p) {
        List<Trade> trades = run(days, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", new ArrayList<>(days.keySet()));
        out.put("params", params(p));
        out.put("total", summary(trades, null));
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (String d : days.keySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d);
            m.putAll(summary(trades, d));
            perDay.add(m);
        }
        out.put("perDay", perDay);
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Trade t : trades) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", t.date);
            m.put("code", t.code);
            m.put("name", t.name);
            m.put("signalAt", hm(t.signalAt));
            m.put("buyAt", hm(t.buyAt));
            m.put("buyPrice", t.buyPrice);
            m.put("sellAt", hm(t.sellAt));
            m.put("sellPrice", Math.round(t.sellPrice * 100) / 100.0);
            m.put("reason", t.reason);
            m.put("qty", t.qty);
            m.put("pnl", t.pnl);
            ts.add(m);
        }
        out.put("trades", ts);
        return out;
    }

    private static Map<String, Object> params(Params p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("volX", p.volX);
        m.put("up", p.up);
        m.put("minValue", p.minValue);
        m.put("tp", p.tp);
        m.put("sl", p.sl);
        m.put("hold", p.hold);
        return m;
    }

    // 격자 탐색 값. ponytail: 고정 목록, 넓히려면 여기만 고침
    static final double[] GRID_VOLX = {3, 5, 10, 20};
    static final double[] GRID_UP = {0.5, 1, 2, 3};
    static final double[] GRID_MIN_VALUE = {50, 200, 1000};
    static final double[] GRID_TP = {0.5, 1, 1.5, 2, 3};
    static final double[] GRID_SL = {0.5, 1, 1.5, 2};
    static final int[] GRID_HOLD = {1, 2};

    /**
     * 격자 탐색: GRID_* 조합마다 돌려 거래가 minTrades 이상인 것을 손익 큰 순으로 top개.
     * 날짜마다 손익(perDay)을 같이 줘서 하루만 맞은 값(과적합)을 거를 수 있게 함. allDaysUp = 모든 날 손익 > 0.
     * base: cost·amount·lookback·start·until은 이 값 그대로.
     */
    static Map<String, Object> grid(Map<String, List<Stock>> days, Params base, int minTrades, int top) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int combos = 0;
        for (double volX : GRID_VOLX) {
            for (double up : GRID_UP) {
                for (double minValue : GRID_MIN_VALUE) {
                    for (double tp : GRID_TP) {
                        for (double sl : GRID_SL) {
                            for (int hold : GRID_HOLD) {
                                combos++;
                                Params p = new Params();
                                p.volX = volX;
                                p.up = up;
                                p.minValue = minValue;
                                p.tp = tp;
                                p.sl = sl;
                                p.hold = hold;
                                p.lookback = base.lookback;
                                p.cost = base.cost;
                                p.amount = base.amount;
                                p.start = base.start;
                                p.until = base.until;
                                p.marketFrom = base.marketFrom;
                                p.wick15 = base.wick15;
                                p.big = base.big;
                                p.prevVol = base.prevVol;
                                p.maSell = base.maSell;
                                p.maBelow = base.maBelow;
                                p.ride = base.ride;
                                p.dip = base.dip;
                                p.aligned = base.aligned;
                                p.maxGap = base.maxGap;
                                p.dayStop = base.dayStop;
                                p.dayLoss = base.dayLoss;
                                p.budget = base.budget;
                                p.maxDayUp = base.maxDayUp;
                                p.vwap = base.vwap;
                                List<Trade> trades = run(days, p);
                                if (trades.size() < minTrades) {
                                    continue;
                                }
                                Map<String, Object> m = params(p);
                                m.putAll(summary(trades, null));
                                List<Long> perDay = new ArrayList<>();
                                boolean allUp = true;
                                for (String d : days.keySet()) {
                                    long x = (long) summary(trades, d).get("pnl");
                                    perDay.add(x);
                                    allUp &= x > 0;
                                }
                                m.put("perDay", perDay);
                                m.put("allDaysUp", allUp);
                                rows.add(m);
                            }
                        }
                    }
                }
            }
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("pnl")).reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", new ArrayList<>(days.keySet()));
        out.put("combos", combos);
        out.put("kept", rows.size());
        out.put("top", rows.subList(0, Math.min(top, rows.size())));
        return out;
    }
}
