# 자동매매(Trader)·매수 시뮬레이션 코드 설명 (C 개발자용)

`spring-app/src/main/java/kb/app/`의 다음 파일을 C에 빗대어 설명합니다.

- `Trader.java`: 토스 자동매매. 실제 주문을 냅니다.
- 매수 시뮬레이션: `Ticks.java`(엔진, `simulate`·`live`)와 `SimModules.java`(매수 조건·매도 모듈)

C 코드 블록은 **이해를 돕기 위한 의사 코드**이며, 실제로 컴파일되는 코드가 아닙니다.
실제 Java 코드도 같은 모양으로 씁니다.

- 자동매매: `Trader.Position` + `enum St`, `Trader.Cfg`(`Mkt`·`Pair`·`Etf`·`Sim`), `Trader.Cand`
- 시뮬레이션: `Ticks.Params`·`Row`·`Trade`, `SimModules.Rule`·`Cond`, 값 번호(`PRICE` …)로 찾는 `double[]`, 매도 함수 테이블 `SELL_FN`

그래서 아래 함수 이름과 필드 이름을 코드에서 그대로 찾을 수 있습니다.
함수 이름은 Java 메서드 이름을 `파일_메서드`로 바꿨습니다. 예: `Trader.tickMarket` → `trader_tick_market`.
오류 처리는 대부분 생략했습니다. Java에서 예외(`throw`)로 빠져나가는 곳은 `/* 예외 */`로만 표시합니다.

---

## 1. 이 두 코드에서 쓰는 Java·Spring ↔ C 대응

공통 대응표(`class` → `struct` + 함수, `Map` → 해시 테이블 등)는 [java-app-for-c.md](java-app-for-c.md) 1장을 보세요.
여기서는 이 두 코드에 새로 나오는 것만 정리합니다.

| Java / Spring | C로 생각하면 |
|---|---|
| `@Scheduled(fixedDelay = 5, timeUnit = SECONDS)` | `while (1) { f(); sleep(5); }`를 도는 타이머 스레드. 앞 실행이 끝나야 다음 대기를 시작 |
| `@Scheduled(cron = "30 */5 9-15 * * MON-FRI", zone = "Asia/Seoul")` | crontab 한 줄: 서울 시간 평일 9~15시, 5분마다 30초에 `f()` |
| `@EventListener void on(Alerts.Signal s)` | 시작할 때 `register_callback(SIGNAL, trader_on)`. 알림 쪽이 `publish(SIGNAL, &s)`하면 불림 |
| `JdbcTemplate db; db.update(sql, a, b)` | `sqlite3_prepare` + `sqlite3_bind_*` + `sqlite3_step` + `sqlite3_finalize`를 한 번에 하는 도우미 |
| `db.queryForList(sql, ...)` | `SELECT` 결과 행마다 `{열 이름: 값}` 해시 테이블을 만들어 배열로 돌려줌 |
| `interface Api { get(...); post(...); }` | 함수 포인터 struct. 운영은 토스 HTTP, 테스트는 가짜 함수를 넣음 |
| `ConcurrentHashMap` | 내부에 락이 있는 해시 테이블 (여러 스레드가 같이 써도 됨) |
| `volatile` 필드 | 다른 스레드가 바꾼 값을 바로 보게 하는 변수 (`_Atomic`과 비슷) |
| `Supplier<ZonedDateTime> clock` | `time_t (*now)(void)` 함수 포인터. 테스트는 고정 시각을 돌려줌 |

---

## 2. 전체 그림

```
 [타이머 스레드, 5초마다]                       [시그널 알림 Alerts, 장중 5분마다]
 trader_scheduled_tick()                        새 상승 시그널 → publish(SIGNAL)
   └ trader_tick()                                         │
       ├ 임대(lease) 확인: 주문은 한 프로세스만             ▼
       └ for 시장 in {KR, US}:                  trader_on() → trader_on_signal()
           trader_tick_market(시장)                 → trader_signal 표에 "오늘 살 후보" 한 줄
             ├ refresh_pending: 낸 주문 체결 확인
             ├ 장 시간 판단 (토스 장 운영 정보)
             ├ 익절·손절·장마감 매도
             ├ buy_watch: 미장 연동 매수 (KR만)
             ├ sim_tick: 시뮬레이션 전략 ──▶ strategy_live() ──▶ ticks_live()  ← 3장
             └ 내가 고른 종목·시그널 종목 매수
                         │
                         ▼
              토스 OpenAPI (/api/v1/orders …)         trader.db (SQLite): 설정·포지션·기록
```

| 파일 | C로 치면 |
|---|---|
| `Trader.java` | `trader.c`: 5초 루프 + 포지션 상태 기계 + 토스 주문 |
| `Ticks.java` | `ticks.c`: 5분 기록 저장소 + 시뮬레이션 엔진 (`ticks_run`) |
| `SimModules.java` | `sim_modules.c`: 매수 조건 해석기 + 매도 함수 테이블 |
| `AppConfig.simStrategy` | 두 쪽을 잇는 함수 포인터 하나 (`trader->strategy = sim_strategy;`) |

---

## 3. 자동매매 `Trader.java`

### 3.1 데이터: 포지션 한 건 = `trader_position` 표의 한 행

자바 코드에는 구조체가 없습니다. DB 행을 그대로 `Map`으로 다룹니다. C 구조체로 쓰면 다음과 같습니다.

```c
enum pos_status { BUYING, OPEN, SELLING, CLOSED, FAILED };

struct position {              /* trader_position 한 행 */
    int64_t id;
    char    market[3];         /* "KR" | "US" */
    char    day[11];           /* 그 시장의 거래일 "2026-10-23" */
    char    symbol[16], name[64];
    char    source[32];        /* "직접 고름" | "시그널" | "미장 연동" | "시뮬레이션" */
    enum pos_status status;
    double  order_qty, est;    /* 주문 수량, 예상 금액 */
    double  qty, avg, amount;  /* 체결 수량, 평균가, 체결 금액 */
    char    buy_client[32];    /* 멱등성 키: 같은 키로 다시 보내도 한 번만 체결 */
    char    buy_order[64];     /* 토스 주문번호 (응답을 받아야 생김) */
    int64_t buy_sent;          /* 처음 보낸 시각 ms */
    char    sell_client[32], sell_order[64];
    int64_t sell_sent;
    double  sell_qty, sell_avg;
    char    reason[64], note[128];
    /* UNIQUE (market, day, symbol): 같은 종목은 시장별로 하루 한 번만 */
};
```

`trader.db`에는 이 표 말고도 다음 표가 있습니다.

| 표 | 내용 |
|---|---|
| `trader_config` | `"config"` = 설정 JSON, `"halt:KR:2026-10-23"` 같은 깃발 |
| `trader_pick` | 내가 고른 종목 |
| `trader_signal` | 시그널 알림으로 들어온 오늘의 후보 |
| `trader_log` | 화면 '기록' (최근 2,000줄) |
| `trader_lease` | 주문 임대 |
| `trader_us_watch` | 미장 연동 미국 티커 |

### 3.2 상태 기계 (포지션 하나의 일생)

```
              buy(): 주문 전에 먼저 INSERT
  (없음) ───────────────────────────────▶ BUYING ──체결 확인(FILLED…)──▶ OPEN
                                           │  └─ 거절(4xx) / 0주 체결 ──▶ FAILED
                                           └─ 10분 동안 결과 모름 ──────▶ FAILED (토스 앱 확인)
  OPEN ──sell(): 익절·손절·장마감·전날 미청산·시뮬레이션 신호──▶ SELLING
  SELLING ──전부 체결──▶ CLOSED
          ├─ 일부만 체결 ──▶ OPEN (남은 수량으로 다시 판단)
          └─ 거절 ────────▶ OPEN
  OPEN ──판매 가능 수량 0 (사용자가 직접 팜)──▶ CLOSED
```

핵심 안전장치는 **"주문 전에 기록, 같은 키로 재전송"**입니다.
응답을 못 받아도 DB에는 BUYING과 `buy_client`가 남습니다. 다음 확인 때 같은 `clientOrderId`로 다시 보냅니다.
토스는 같은 키를 10분 동안 한 주문으로 보므로 두 번 체결되지 않습니다.

### 3.3 5초 루프

```c
/* @Scheduled(fixedDelay = 5초, 시작 5초 뒤부터) */
void trader_scheduled_tick(struct trader *t) {
    if (setjmp(t->err) == 0) trader_tick(t);
    else trader_log(t, "ALL", "오류: ...");      /* 한 번 실패해도 다음 5초에 계속 */
}

void trader_tick(struct trader *t) {
    if (t->api == NULL) return;                   /* 토스 키 없음 = 아무것도 안 함 */
    if (now_ms() - t->account_at >= 30000)
        trader_refresh_account(t);                /* 화면의 토스 계좌 금액 (임대와 상관없이) */

    t->leader = trader_lease(t);                  /* 같은 trader.db를 쓰는 서버가 여럿이어도 주문은 하나만 */
    if (!t->leader) return;

    struct config cfg = trader_config(t);         /* 매번 DB에서 읽음: 화면에서 바꾼 설정이 5초 안에 반영 */
    for (int i = 0; i < 2; i++) {                 /* "KR", "US" */
        if (setjmp(t->err) == 0) trader_tick_market(t, MARKETS[i], &cfg);
        else log_once(t, MARKETS[i], "오류: ...");   /* 같은 오류는 바뀔 때만 한 줄 */
    }
}
```

임대(lease)는 SQL 한 줄로 하는 "30초짜리 뮤텍스"입니다. 5초마다 연장합니다.

```c
bool trader_lease(struct trader *t) {
    int64_t now = now_ms();
    /* 비어 있거나, 내가 주인이거나, 주인의 임대가 끝났으면 내 것으로 */
    sql_exec(t->db,
        "INSERT INTO trader_lease (name, owner, until) VALUES ('trader', ?, ?)"
        " ON CONFLICT (name) DO UPDATE SET owner = excluded.owner, until = excluded.until"
        " WHERE trader_lease.owner = excluded.owner OR trader_lease.until < ?",
        t->owner_uuid, now + 30000, now);
    return strcmp(sql_one(t->db, "SELECT owner FROM trader_lease WHERE name = 'trader'"), t->owner_uuid) == 0;
}
```

서버를 다시 켜면 새 프로세스의 UUID가 달라집니다. 그래서 이전 임대가 끝날 때까지 최대 30초 동안 기다린 뒤 주문을 맡습니다.

### 3.4 시장 하나 처리 `trader_tick_market`

순서가 곧 우선순위입니다. 체결 확인 → 장 시간 → 매도 → 매수 순입니다.

```c
void trader_tick_market(struct trader *t, const char *m, struct config *cfg) {
    trader_refresh_pending(t, m);                 /* 3.6: 낸 주문의 체결 확인·재전송 */

    struct session s = trader_session(t, m);      /* 토스 /market-calendar, 10분 캐시 */
    if (s.open == 0) { set_phase(t, m, "휴장"); return; }
    if (strcmp(m, "KR") == 0) trader_watch_checks(t);     /* 미장 종가 미리 받기 (화면용) */
    time_t now = t->clock();
    if (now <  s.open)  { set_phase(t, m, "장 시작 전"); return; }
    if (now >= s.close) { set_phase(t, m, "장 마감");    return; }

    struct position *open = positions(t, m, "status = 'OPEN'");
    bool hold_pairs = !cfg->pair.close_sell;      /* 미장 연동 종목은 장마감에 안 팔 수도 있음 */

    if (now >= s.close - 10 * 60) {               /* 마감 10분 전: 정리만 */
        for_each(p, positions(t, m, "status = 'BUYING' AND buy_order IS NOT NULL")) trader_cancel(t, m, p);
        for_each(p, open) if (!(hold_pairs && is_pair(p))) trader_sell(t, m, p, "장마감");
        return;
    }
    if (get_flag(t, "halt:%s:%s", m, s.day)) {    /* 화면의 '전량 매도' 버튼 */
        /* 미체결 매수 취소 + 보유분 전부 매도, 그날 새 매수 없음 */
        ...; return;
    }

    /* 익절·손절. 시세는 한 번에 받음 */
    double *px = prices(t, m, open);
    for_each(p, open) {
        if (strcmp(p->day, s.day) != 0 && !(hold_pairs && is_pair(p))) {
            trader_sell(t, m, p, "전날 미청산"); continue;     /* 서버가 꺼져 못 판 종목 */
        }
        double r = px[p] / p->avg - 1;
        double lim[2]; limits(p, cfg, lim);       /* 출처별 %: 기본 / 미장 연동 종목별 / 시뮬레이션 화면 값 */
        if (r >=  lim[0]) trader_sell(t, m, p, "익절");
        else if (r <= -lim[1]) trader_sell(t, m, p, "손절");
    }

    if (strcmp(m, "KR") == 0) trader_buy_watch(t, &s, now, &cfg->pair);   /* 3.7 */
    trader_sim_tick(t, m, &s, cfg);                                       /* 3.8 */
    if (!cfg->enabled) return;                    /* 꺼져 있으면 새 매수 없음 (매도는 위에서 계속) */

    /* 새 매수 후보: 내가 고른 종목, 그다음 시그널 종목 (알림 순서) */
    struct cand *c = picks(t, m);
    if (cfg->market[m].auto_buy) append(c, signals_today(t, m, s.day));
    int  up = -1;                                 /* 지수 상승 여부는 실제로 살 때만 한 번 확인 */
    for_each(x, c) {
        if (already_today(t, m, s.day, x->symbol)) continue;      /* 하루 한 번 */
        double spent; int n = today_spent(t, m, s.day, &spent);   /* 미장 연동·시뮬레이션은 따로 셈 */
        if (n >= cfg->max_stocks || spent >= cfg->budget) break;
        if (up < 0) up = index_up(t, m, s.day);   /* 코스닥150·IWM ETF가 전일 대비 상승? */
        if (!up) break;                           /* 5초 뒤 다시: 오르면 그때 삼 */
        double amount = fmin(cfg->budget / cfg->max_stocks, cfg->budget - spent);
        double price  = price_of(t, m, x->symbol);
        int64_t qty   = (int64_t) floor(amount / price);
        if (qty < 1) { trader_failed(t, m, s.day, x, "1주가 더 비쌈"); continue; }
        trader_buy(t, m, s.day, x, qty, qty * price);
    }
}
```

`set_phase()`는 "왜 지금 주문이 없는지"를 화면과 로그에 남깁니다. 문구가 바뀔 때만 한 줄 씁니다.

### 3.5 주문 보내기

```c
void trader_buy(struct trader *t, const char *m, const char *day, struct cand *c, int64_t qty, double est) {
    char key[32]; random_key(key);                /* "kb-" + 24자: clientOrderId */
    sql_exec(t->db, "INSERT INTO trader_position (..., status, order_qty, est, buy_client, buy_sent, ...)"
                    " VALUES (..., 'BUYING', ?, ?, ?, ?, ...)", qty, est, key, now_ms());  /* ① 먼저 기록 */
    struct position *p = select_by_client(t, key);
    trader_send_buy(t, m, p, false);              /* ② 그다음 전송 */
}

void trader_send_buy(struct trader *t, const char *m, struct position *p, bool resend) {
    struct resp r;
    int st = order(t, m, p->buy_client, p->symbol, "BUY", p->order_qty, resend, &r);
    if (st == OK)                    update(p, "buy_order", r.order_id);
    else if (st == 409 || st >= 500) return;                         /* 처리 중·서버 오류: 다음에 같은 키로 */
    else if (st == NO_RESPONSE)      return;                         /* 응답 못 받음: 다음에 같은 키로 */
    else { update(p, "status", "FAILED"); update(p, "note", r.message); }  /* 4xx 거절 */
}

void trader_sell(struct trader *t, const char *m, struct position *p, const char *reason) {
    double sellable = toss_sellable_quantity(t, p->symbol);
    if (sellable <= 0) { /* 사용자가 직접 판 것으로 보고 CLOSED로 정리만 */ return; }
    random_key(p->sell_client);
    update(p, "status", "SELLING");               /* 먼저 기록 (매수와 같은 방식) */
    update(p, "sell_qty", fmin(p->qty, sellable));
    trader_send_sell(t, m, p, false);             /* 거절되면 OPEN으로 되돌려 다음에 다시 판단 */
}
```

`order()`는 토스 `POST /api/v1/orders`로 시장가 주문을 보냅니다.
body는 `{clientOrderId, symbol, side, orderType: "MARKET", quantity}`입니다.
보내기 전후 내용을 `trader.log`에 자세히 남깁니다.

### 3.6 체결 확인 `trader_refresh_pending`

```c
void trader_refresh_pending(struct trader *t, const char *m) {
    for_each(p, positions(t, m, "status IN ('BUYING', 'SELLING')")) {
        bool buying = p->status == BUYING;
        const char *order_id = buying ? p->buy_order : p->sell_order;
        if (order_id[0] == '\0') {                /* 주문번호가 없다 = 응답을 못 받았음 */
            if (now_ms() - (buying ? p->buy_sent : p->sell_sent) > 9 * 60 * 1000)
                give_up(p);                       /* 멱등성 10분이 끝나기 전에 포기: 토스 앱에서 확인 */
            else if (buying) trader_send_buy(t, m, p, true);    /* 같은 키로 다시 */
            else             trader_send_sell(t, m, p, true);
            continue;
        }
        struct order o = toss_get_order(t, order_id);          /* 실패하면 다음 5초에 */
        bool done = o.status is FILLED | CANCELED | REJECTED | REPLACED;
        if (buying) {
            update(p, "qty", o.filled);
            if (done) {
                if (o.filled > 0) { update(p, "avg", o.avg); update(p, "status", "OPEN"); }
                else update(p, "status", "FAILED");
            }
        } else if (done) {
            double left = p->qty - o.filled;
            if (left > 1e-9 && o.status != FILLED) { update(p, "qty", left); update(p, "status", "OPEN"); }
            else { update(p, "status", "CLOSED"); /* 손익 % 로그 */ }
        }
    }
}
```

### 3.7 미장 연동 `trader_buy_watch` (KR만)

```c
/* 등록한 미국 티커가 직전 미장 종가 기준 '모두 상승'이면 KODEX 200, '모두 하락'이면 KODEX 인버스를
   코스피 장 시작 후 30분 안에 지정 수량만큼 산다. 섞이거나 보합이면 안 삼. */
void trader_buy_watch(struct trader *t, struct session *s, time_t now, struct pair_cfg *pc) {
    if (!pc->enabled || n_tickers == 0 || now >= s->open + 30 * 60) return;
    int up = 0, down = 0;
    for_each(sym, tickers) {
        struct pair_check *c = pair_check(t, sym);   /* 토스 일봉 5개, 미장 날짜·마감 여부별 캐시 */
        if (c == NULL) return;                       /* 종가가 아직 부족: 5초 뒤 다시 */
        up += c->change > 0; down += c->change < 0;
    }
    const char *k = up == n_tickers ? "up" : down == n_tickers ? "inverse" : NULL;
    if (k && !already_today(t, "KR", s->day, pc->etf[k].symbol))
        trader_buy(t, "KR", s->day, &pc->etf[k], pc->etf[k].qty, qty * price);
}
```

### 3.8 시뮬레이션 전략 `trader_sim_tick`

화면 '매수 시뮬레이션'의 규칙을 실제로 매매합니다. 계산은 4장의 엔진이 하고, Trader는 그 결과대로 주문만 냅니다.

```c
void trader_sim_tick(struct trader *t, const char *m, struct session *s, struct config *cfg) {
    struct sim_cfg *sc = &cfg->sim;
    if (!sc->enabled || strcmp(sc->market, m) != 0) return;
    if (now_ms() - t->sim_checked_at < 30000) return;      /* 30초마다만 (새 기록은 5분·30분마다) */
    t->sim_checked_at = now_ms();

    sc->params.amount = sc->budget / sc->params.max_buys;   /* 종목당 금액 */
    struct signal sig = t->strategy(m, &sc->params);        /* = ticks_live(오늘 기록) */
    if (strcmp(sig.date, s->day) != 0) return;              /* 오늘 기록이 아직 없음 */
    if (minutes_since(sig.at) > sig.every + 10) return;     /* 기록이 멈췄으면 주문 안 함 */
    if (same_record(t, m, s->day, sig.at)) return;          /* 같은 기록은 한 번만 */

    for_each(p, positions(t, m, "status = 'OPEN' AND source = '시뮬레이션'"))
        if (sig.sell[p->symbol]) trader_sell(t, m, p, sig.sell[p->symbol]);   /* 엔진이 판다고 한 보유분 */
    for_each(b, sig.buy) {                                  /* 엔진이 이 시각에 산다고 한 종목 */
        if (already_today(...)) continue;
        if (오늘 시뮬레이션 매수 수 >= max_buys || 쓴 금액 >= budget) break;
        int64_t qty = floor(amount / price_of(t, m, b->code));
        if (qty < 1) { trader_failed(...); continue; }
        trader_buy(t, m, s->day, b, qty, qty * price);
    }
}
```

익절·손절 %는 3.4의 5초 루프(`limits`)에서도 확인합니다. 5분 기록을 기다리지 않고 가격이 닿으면 바로 팝니다.

### 3.9 시그널 알림 받기 (이벤트)

```c
/* Alerts가 새 알림마다 publish(SIGNAL, {market, alert}) → Spring이 이 함수를 부름 (@EventListener) */
void trader_on(struct trader *t, struct alerts_signal *e) {
    const char *m = strcmp(e->market, "kosdaq") == 0 ? "KR" : "US";
    /* 바로 사지 않고 후보 표에만 넣음. 실제 매수는 다음 5초 루프가 예산·지수·장 시간을 보고 판단 */
    sql_exec(t->db, "INSERT OR IGNORE INTO trader_signal (market, day, symbol, name, at) VALUES (?, ?, ?, ?, ?)", ...);
}
```

---

## 4. 매수 시뮬레이션 `Ticks.java` + `SimModules.java`

### 4.1 입력 데이터: 5분 기록

`kosdaq.db`의 `kosdaq_tick` 표에는 장중 5분마다 한 행이 쌓입니다(러셀 2000은 `russell.db`에 30분마다).

```c
/* 한 행 = 한 시각의 전 종목 */
struct tick_row {              /* payload JSON의 한 원소 [code, name, price, change, vol, value] */
    char   code[8], name[64];
    double price;              /* 현재가 */
    double change;             /* 전일 대비 % */
    double vol;                /* 그 시각까지 누적 거래량 */
    double value;              /* 그 시각까지 누적 거래대금 (없으면 0) */
};
struct tick_record { char at[17]; /* "2026-10-22T09:05" */ struct tick_row *rows; size_t n; };
```

### 4.2 매수 조건 = 작은 해석기 (`SimModules.buy`)

매수 조건은 코드가 아니라 **데이터**입니다. 조건은 `sim_buy_rules.json`에 있고, 없으면 `DEFAULT_BUY`를 씁니다.
화면에서 추가·수정할 수 있습니다. C로 쓰면 "조건 배열을 순서대로 검사하는 함수"입니다.

```c
enum field { PRICE, CHANGE, RATIO, OPEN, VWAP, VALUE };
enum op    { GT, GE, LT, LE };
struct operand { enum { NUM, FIELD, MIN } kind; double num; enum field f; };
struct cond    { enum field left; enum op op; struct operand right; };
struct rule    { char id[41]; char label[61]; struct cond all[10]; int n; };   /* 최대 10개, 모두 만족해야 */

/* 예: surge300 = ratio >= 300 && change >= 5 && change < 15 */

/* s[] = 그 시각 그 종목의 값, opt_min = 화면의 '거래량 기준 %' */
bool rule_test(const struct rule *r, const double s[6], double opt_min) {
    for (int i = 0; i < r->n; i++) {
        double left  = s[r->all[i].left];
        double right = r->all[i].right.kind == NUM ? r->all[i].right.num
                     : r->all[i].right.kind == MIN ? opt_min
                     : s[r->all[i].right.f];
        bool ok;
        switch (r->all[i].op) {
            case GT: ok = left >  right; break;
            case GE: ok = left >= right; break;
            case LT: ok = left <  right; break;
            default: ok = left <= right;
        }
        if (!ok) return false;
    }
    return true;
}
```

`validRules()`는 화면에서 저장할 때 `id` 모양, 필드·연산자 이름, 개수(규칙 30개, 조건 10개)를 검사합니다.
파일은 임시 파일에 쓴 뒤 `rename`해서 통째로 바꿉니다. 반쯤 쓴 파일을 읽는 일을 막기 위해서입니다.

### 4.3 매도 모듈 = 함수 포인터 테이블 (`SimModules.SELL`)

```c
/* NULL이면 계속 보유, 문자열이면 그 이유로 지금 가격에 팜 */
typedef const char *(*sell_fn)(struct trade *tr, double px, double tp, double sl);

const char *tp_sl(struct trade *tr, double px, double tp, double sl) {
    if (tp > 0 && px >= tr->buy_price * (1 + tp / 100)) return "익절";
    if (sl > 0 && px <= tr->buy_price * (1 - sl / 100)) return "손절";
    return NULL;
}
const char *trailing(struct trade *tr, double px, double tp, double sl) {
    tr->peak = fmax(tr->peak ? tr->peak : tr->buy_price, px);   /* 산 뒤 최고가를 거래에 기억 */
    if (tp > 0 && px >= tr->buy_price * (1 + tp / 100)) return "익절";
    if (sl > 0 && px <= tr->peak * (1 - sl / 100)) return "고점 대비 손절";
    return NULL;
}
struct { const char *id; sell_fn f; } SELL[] = { {"tp-sl", tp_sl}, {"trailing", trailing} };
```

### 4.4 엔진 `ticks_run` (`simulate`와 `live`가 같이 씀)

하루씩, 그날의 5분 기록을 시각순으로 훑습니다. 거래량 비교 기준은 **전날의 같은 시각 이전 마지막 기록**입니다.

```c
struct result ticks_run(struct ticks *tk, struct sim_params *p, struct allowed *allowed, bool live) {
    struct rule *buy  = sim_buy(p->buy);          /* 이름으로 고름 (없으면 예외 → 화면 400) */
    sell_fn      sell = sim_sell(p->sell);
    char **days = distinct_days(tk);              /* 기록이 있는 날, 오래된 순 */

    for (int di = live ? max(1, ndays - 1) : 1; di < ndays; di++) {   /* live면 오늘 하루만 */
        const char *prev = days[di - 1], *d = days[di];
        char **slots = slots_of(tk, d);           /* "09:00", "09:05", ... */
        clean(tk, d, slots);                      /* 4.5: 잘못 받은 현재가 빼기 */

        struct held  held   = {0};                /* 지금 들고 있는 거래 */
        struct set   bought = {0};                /* 오늘 이미 산 종목 (하루 한 번) */
        struct map   since  = {0};                /* 종목 -> 조건을 연속으로 만족하기 시작한 시각 */
        struct map   opens  = first_record_prices(tk, d);   /* 첫 기록 현재가 = 시가로 봄 */

        for (int i = 0; i < nslots; i++) {
            const char *t = slots[i];
            struct tick_record *now = load(tk, d, t);

            /* ① 매도: 들고 있는 것마다 매도 모듈, 아니면 보유 시간(maxHold) */
            for_each(tr, held) {
                double px = price_in(now, tr->code);
                const char *why = px > 0 ? sell(tr, px, p->tp, p->sl) : NULL;
                if (!why && px > 0 && p->max_hold > 0 && mins(t) - mins(tr->buy_at) >= p->max_hold) why = "보유 시간";
                if (why) { tr->sell_at = t; tr->sell_price = px; tr->reason = why; remove(held, tr); }
            }

            /* ② 매수할 수 있는 시각인가 */
            const char *before = last_slot_not_after(prev, t);     /* 전날 같은 시각 이전 마지막 기록 */
            if (strcmp(t, p->until) > 0 || strcmp(t, p->start) < 0 || before == NULL
                || (!live && i == nslots - 1)) continue;           /* 마지막 기록에는 안 삼 (그 값에 바로 팔게 되므로) */
            struct tick_record *pv = load(tk, prev, before);

            /* ③ 종목마다 조건 검사 → hold분 이어지면 '살 차례' */
            struct ready *ready = NULL;
            for_each(r, now->rows) {
                double pvol = vol_in(pv, r->code);
                double s[6] = { [PRICE] = r->price, [CHANGE] = r->change,
                                [RATIO] = (r->vol / pvol - 1) * 100,          /* 전일 같은 시각 대비 거래량 증가 % */
                                [OPEN]  = opens[r->code] ? opens[r->code] : r->price,
                                [VALUE] = r->value,
                                [VWAP]  = r->vol > 0 && r->value > 0 ? r->value / r->vol : r->price };
                if (contains(bought, r->code) || pvol <= 0 || r->price <= 0
                    || r->change >= 29.5                                     /* 상한가 근처는 못 산다고 봄 */
                    || (p->spac && strstr(r->name, "스팩"))
                    || p->amount < r->price                                  /* 1주도 못 삼 */
                    || (allowed && strcmp(t, allowed_time(allowed, d, r->code)) < 0)  /* 알림 종목만: 알림 시각부터 */
                    || !rule_test(buy, s, p->min)) {
                    erase(since, r->code);                                   /* 조건이 깨지면 처음부터 다시 */
                    continue;
                }
                const char *began = get_or_put(since, r->code, t);
                if (mins(t) - mins(began) < p->hold) continue;               /* hold분(기본 30) 이어져야 */
                push(ready, s[RATIO], r, began);
            }
            sort_desc_by_ratio(ready);                                       /* 하루 최대 매수 수가 있으면 증가율 큰 순 */
            for_each(x, ready) {
                if (p->max_buys > 0 && size(bought) >= p->max_buys) break;
                struct trade *tr = new_trade(d, x->r, t, /*buy_price*/ x->r->price,
                                             /*qty*/ floor(p->amount / x->r->price));
                put(bought, x->r->code); add(held, tr); add(trades, tr);
            }
        }
        if (live) { live_at = last(slots); continue; }
        /* ④ 그날 끝까지 못 판 것은 마지막 기록 가격에 팖 */
        for_each(tr, held) { tr->sell_at = last(slots); tr->sell_price = price_in(last_record, tr->code); tr->reason = "마지막 기록"; }
    }
    return live ? live_signal(trades, live_at) : summarize(trades, p->cost);
}
```

`simulate`와 `live`는 마지막만 다릅니다.

| | `simulate` (GET /api/kosdaq-sim, 화면) | `live` (자동매매 시뮬레이션 전략, 3.8) |
|---|---|---|
| 기간 | 저장된 전체 날짜 (최대 14일) | 마지막 두 기록일 (어제 = 비교 기준, 오늘) |
| 마지막 기록 | 거기서는 사지 않고, 남은 보유는 그 가격에 팖 | 장중이라 마지막 기록에서도 삼, '마지막 기록' 매도 없음 |
| 결과 | 거래마다 투자금·손익(`cost%` 수수료 뺌), 날짜별·전체 합계 | 오늘 마지막 기록 시각에 **새로** 살 종목과 팔 종목만 `{date, at, every, buy[], sell{}}` |

### 4.5 깨진 값 거르기 `clean`

KB가 가끔 자릿수가 빠진 현재가를 줍니다(6220 → 709 → 6510). 이때 등락률은 그대로입니다.
그래서 `현재가 / (1 + 등락률/100)`로 전일 종가를 거꾸로 계산하고, 그 종목의 그날 중앙값과 2% 넘게 다른 행은 버립니다.

```c
for_each(rec, today) for_each(r, rec->rows) if (r->price > 0) push(base[r->code], r->price / (1 + r->change / 100));
for_each(code, base) mid[code] = median(base[code]);
for_each(rec, today) remove_if(rec->rows, r->price > 0 && fabs(r->price / (1 + r->change / 100) / mid[r->code] - 1) > 0.02);
```

### 4.6 메모리

14일 치 기록을 한꺼번에 올리면 수백 MB가 됩니다. 그래서 `cache`에는 **전날과 오늘 기록만** 둡니다.
날짜가 넘어갈 때마다 그 이전 날 기록을 지웁니다(`cache.keySet().removeIf(...)`).

---

## 5. 두 코드가 만나는 곳

```c
/* AppConfig.simStrategy: 서버가 시작할 때 한 번 */
trader->strategy = lambda(m, params) {
    struct ticks *tk = strcmp(m, "KR") == 0 ? volume->ticks : russell->ticks;
    struct allowed *a = params->alerts ? alerts_first_times(m == "KR" ? "kosdaq" : "russell") : NULL;
    return ticks_live(tk, params, a);            /* 화면 시뮬레이션과 같은 엔진·같은 매수 조건 파일 */
};
```

매수 조건 파일(`sim_buy_rules.json`)은 화면 시뮬레이션과 자동매매가 같이 씁니다.
유지·익절·손절 같은 값은 자동매매 설정(`trader_config`의 `sim.params`)에 저장된 시뮬레이션 화면 값을 씁니다.
시뮬레이션 전략(`sim.enabled`)이 꺼져 있으면 주문은 나가지 않습니다. 기본값에서는 꺼져 있습니다.
