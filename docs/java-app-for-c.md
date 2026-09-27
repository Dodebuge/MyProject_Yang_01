# java-app 코드 설명 (C 개발자용)

`java-app/src/main/java/kb/app/`의 Java 코드 6개 파일을 C에 빗대어 설명합니다.
C 코드 블록은 **이해를 돕기 위한 의사 코드**이며, 실제로 컴파일되는 코드가 아닙니다.

---

## 1. 먼저 알아둘 Java ↔ C 대응표

| Java | C로 생각하면 | 설명 |
|---|---|---|
| `class Foo { 필드; 메서드(); }` | `struct Foo` + `foo_xxx(struct Foo *self, ...)` 함수들 | 메서드는 첫 인자로 `self`(Java에선 `this`)를 암묵적으로 받는 함수 |
| `new Foo(a, b)` | `Foo *p = malloc(...); foo_init(p, a, b);` | 생성자 = 초기화 함수 |
| (없음) | `free(p)` | **free가 없습니다.** 가비지 컬렉터(GC)가 안 쓰는 메모리를 알아서 회수 |
| `static` 필드 | 전역 변수 (파일 `static` 전역) | 객체마다가 아니라 프로그램에 하나 |
| `static` 메서드 | 일반 함수 (`self` 없음) | `Values.num(x)` = `values_num(x)` |
| `static { ... }` 블록 | 프로그램 시작 시 한 번 도는 초기화 함수 | 전역 테이블 채우기 |
| `final` | `const` | 한 번 정하면 못 바꿈 |
| `private` / `public` | 파일 `static` 함수 / 헤더에 공개한 함수 | 접근 범위 |
| `String` | `const char *` (불변) | 수정하면 새 문자열이 만들어짐. 한글(UTF-16) 기본 지원 |
| `null` | `NULL` | |
| `long`, `double`, `int`, `boolean` | `int64_t`, `double`, `int32_t`, `bool` | 크기가 플랫폼과 무관하게 고정 |
| `Long`, `Double` (대문자) | 힙에 있는 숫자 하나 (`int64_t *`) | NULL이 될 수 있는 숫자 |
| `Object` | `void *` + 타입 태그 | 무엇이든 담는 포인터. `instanceof`로 실제 타입 확인 |
| `List<T>` (`ArrayList`) | 동적 배열 `T **items; size_t len, cap;` | `add()`는 `realloc`해 가며 뒤에 붙임 |
| `Map<K,V>` (`HashMap`) | 해시 테이블 | `get(k)`, `put(k, v)` |
| `LinkedHashMap` | 해시 테이블 + 넣은 순서를 기억하는 연결 리스트 | JSON 필드 순서를 유지하려고 사용 |
| `TreeMap` | 정렬된 이진 트리 (레드블랙 트리) | 키 순서대로 순회 |
| `Set<T>` (`HashSet`) | 값 없이 키만 있는 해시 테이블 | "이미 봤나?" 검사 |
| `Map<String,Object>` | JSON 객체 `{ "키": 값 }` | 이 프로젝트는 구조체 대신 대부분 이걸 씀 |
| `throw new XxxException(...)` | 오류 코드를 `return`하거나 `longjmp` | 호출 스택을 거슬러 올라가 `catch`에서 잡힘 |
| `try { } catch (E e) { }` | `if (setjmp(env) == 0) { ... } else { 오류 처리 }` | |
| `try (자원) { }` | 블록 끝에서 자동으로 `fclose()` | 파일·스트림 자동 닫기 |
| `interface` | 함수 포인터가 들어 있는 struct (vtable) | |
| 람다 `x -> x + 1` | 함수 포인터 + 캡처한 변수를 담은 struct | 이름 없는 작은 함수 |
| `synchronized` 메서드 | 함수 앞뒤로 `pthread_mutex_lock/unlock` | 여러 스레드가 동시에 못 들어옴 |
| `ExecutorService` (스레드 풀) | `pthread_create` N개 + 작업 큐 | |
| `import` | `#include` | |
| `package kb.app` | 네임스페이스 (폴더 이름) | |
| `@Override` | 주석 수준 표시 | "부모 함수를 덮어씀" 확인용 |

### 이 코드에서 특히 알아둘 점

- **구조체를 거의 안 씁니다.** KB 응답도, 브라우저로 보낼 결과도 전부 `Map<String, Object>`(JSON 객체와 같은 모양)로 다룹니다.
  C로 치면 `struct stock { char *code; int64_t cap; }` 대신 `json_set(obj, "code", ...)`을 쓰는 방식입니다.
- **`main()`이 없습니다.** Tomcat(웹 서버)이 `main`이고, 이 코드는 Tomcat이 부르는 **콜백 함수**만 제공합니다.
  - 서버 시작 시 한 번: `init()`
  - HTTP GET 요청이 올 때마다: `doGet()`

---

## 2. 전체 구조

```
          ┌─────────────── Tomcat (main 루프, 스레드 풀) ───────────────┐
 브라우저 ─▶ doGet(req, resp)            ← AppServlet.java (라우터)
                 │
                 ├─▶ heatmap_query_heatmap() / _quarters() / _holdings()   ← Heatmap.java
                 ├─▶ dividends_query()                                      ← Dividends.java
                 │        │
                 │        ▼
                 │   kbclient_call(tr_code, body)   ← KBClient.java (HTTP + 토큰 + 파싱)
                 │        │
                 │        ▼
                 │   KB증권 OpenAPI 서버 (https://developer.kbsec.com:32484)
                 │
                 └─ 공용 도우미: Values.java (숫자 변환, 병렬 실행)
                    오류 타입:  KBApiException.java
```

| 파일 | 줄 수 | C로 치면 |
|---|---|---|
| `AppServlet.java` | 141 | `main.c`의 요청 디스패처 (URL → 함수 테이블) |
| `KBClient.java` | 261 | `kbclient.c`: libcurl로 POST, 토큰 캐시, JSON 파싱 |
| `Heatmap.java` | 609 | `heatmap.c`: 화면 데이터 대부분을 계산 |
| `Dividends.java` | 121 | `dividends.c`: 배당 내역 필터링 |
| `Values.java` | 79 | `util.c`: `atof` 같은 변환 함수, 스레드 풀 도우미 |
| `KBApiException.java` | 14 | `enum kb_error` + 에러 메시지 struct |

---

## 3. 파일별 설명

### 3.1 `KBApiException.java`: 오류 타입

KB 서버가 HTTP 200을 보냈더라도 응답 안의 `processFlag`가 `"A"`(정상)가 아니면 이 오류를 던집니다.

```c
/* C로 치면 */
struct kb_api_error {
    const char *tr_code;   /* 어떤 TR에서 났는지, 예: "SSQM2952" */
    const char *code;      /* KB가 준 processCode */
    const char *message;   /* "[SSQM2952] 0011 조회할 내역이 없습니다" */
};
```

`RuntimeException`을 상속했다는 것은 "함수 선언에 이 오류를 던진다고 적지 않아도 되는 오류"라는 뜻입니다.

---

### 3.2 `Values.java`: 공용 도우미 함수

KB 응답 값은 숫자, 문자열, NULL이 섞여 있어서 이를 안전하게 숫자로 바꾸는 함수들입니다.

```c
const char *values_str(void *v);   /* NULL이면 "" */
double      values_num(void *v);   /* 숫자면 그대로, "1,073,000"이면 쉼표 빼고 atof, 실패하면 0 */
double     *values_num_or_null(void *v); /* 숫자가 아니면 NULL (깨진 데이터 거르기용) */
int64_t     values_lng(void *v);   /* round(values_num(v)) */
```

**`parallel(items, workers, fn)`**: 스레드 풀로 `fn`을 병렬 실행하고 결과를 **입력 순서대로** 모아 돌려줍니다.

```c
/* C로 치면 */
void **values_parallel(void **items, size_t n, int workers, void *(*fn)(void *item)) {
    pool = thread_pool_create(workers);           /* Executors.newFixedThreadPool */
    for (i = 0; i < n; i++)
        futures[i] = thread_pool_submit(pool, fn, items[i]);
    for (i = 0; i < n; i++)
        results[i] = future_wait(futures[i]);     /* f.get(): 끝날 때까지 기다림 */
    thread_pool_shutdown(pool);                   /* finally: 성공하든 실패하든 반드시 실행 */
    return results;
}
```

KB에 종목 200개를 하나씩 조회하면 오래 걸리므로, 8개씩 동시에 요청할 때 씁니다.

---

### 3.3 `KBClient.java`: KB OpenAPI 호출 (핵심)

KB 서버와 통신하는 모든 일을 맡습니다. 객체는 서버 전체에서 **하나만** 만들어 공유합니다.

```c
struct kbclient {
    const char *app_key, *app_secret;   /* .env 또는 환경변수에서 읽음 */
    const char *base_url;               /* https://developer.kbsec.com:32484 */
    const char *ip_addr, *mac_addr;     /* 모든 요청 dataHeader에 넣는 값 */
    int         timeout_ms;             /* 10000 */
    char       *token;                  /* 캐시된 접근 토큰 */
    int64_t     token_expires_at;       /* 만료 시각 (ms) */
};
```

#### (1) `fromEnv(envFile)`: 설정 읽기

```c
struct kbclient *kbclient_from_env(const char *env_file) {
    map env = read_env_file(env_file);   /* "KEY=VALUE" 줄 파싱, '#' 주석 무시, 따옴표 제거 */
    map_put_all(env, getenv_all());      /* 환경변수로 덮어씀 → 환경변수가 우선 */
    if (!key || !secret) error("KB_OPENAPI_APP_KEY / SECRET 를 설정하세요");
    return kbclient_new(key, secret, base_url 기본값, "127.0.0.1", "00:00:00:00:00:00");
}
```

#### (2) `accessToken()`: 토큰 캐시

```c
/* synchronized: 여러 스레드가 동시에 불러도 한 번에 하나만 들어옴 */
const char *kbclient_access_token(struct kbclient *c) {
    mutex_lock(&c->lock);
    if (c->token[0] == '\0' || now_ms() >= c->token_expires_at - 60000) {  /* 만료 60초 전이면 재발급 */
        json resp = post(c, "/oauth2/token", NULL,
                         { dataHeader: {ipAddr, macAddr},
                           dataBody:   {appKey, appSecret, grantType: "client_credentials"} });
        if (!has(resp.dataBody, "access_token")) throw IOException("토큰 발급 실패");
        c->token = resp.dataBody.access_token;
        c->token_expires_at = now_ms() + resp.dataBody.expires_in * 1000;  /* 없으면 86400초 */
    }
    mutex_unlock(&c->lock);
    return c->token;
}
```

#### (3) `call(trCode, dataBody)`: TR 한 번 호출

KB API는 기능마다 "TR 코드"(예: `SSQM2952` = 잔고 조회)가 있고, 주소는 `/api/v1/{소문자 TR코드}`입니다.

```c
map kbclient_call(struct kbclient *c, const char *tr_code, map data_body) {
    json req  = { dataHeader: {ipAddr, macAddr}, dataBody: data_body };
    char auth[512]; sprintf(auth, "bearer %s", kbclient_access_token(c));
    json resp = post(c, strcat("/api/v1/", tolower(tr_code)), auth, req);
    return parse_response(tr_code, resp);    /* processFlag 검사 + 값 정리 */
}
```

#### (4) `callPages(...)`: 다음 페이지 반복 조회

결과가 많으면 KB는 한 번에 다 주지 않고 `nxt_key`를 줍니다. 이 값이 빌 때까지 반복합니다.

```c
list kbclient_call_pages(c, tr_code, body, record_name, max_pages) {
    list rows = {}; set seen = {}; const char *key = "";
    for (page = 0; page < max_pages; page++) {
        body.nxt_key = key;
        map out = kbclient_call(c, tr_code, body);
        list_add_all(rows, out[record_name]);       /* 예: out["Record1"] 배열 */
        key = trim(out.nxt_key);
        if (key[0] == '\0' || !set_add(seen, key))  /* 비었거나 같은 키가 또 나오면 끝 */
            return rows;
    }
    throw IOException("max_pages 페이지를 넘었습니다");  /* 무한 루프 방지 */
}
```

#### (5) `post(path, authorization, body)`: HTTP 요청 + 5xx 재시도

```c
json post(c, path, authorization, body) {
    for (attempt = 0; ; attempt++) {
        conn = http_open(c->base_url + path);              /* HttpURLConnection = libcurl 핸들 */
        set_timeout(conn, 10000);
        set_header(conn, "Content-Type", "application/json");
        if (authorization) { set_header(conn, "appKey", c->app_key);
                             set_header(conn, "Authorization", authorization); }
        write_body(conn, json_encode(body));
        int status = http_status(conn);
        if (status >= 500 && attempt < 3) {                /* KB 서버가 가끔 500을 연달아 줌 */
            http_close(conn);
            sleep_ms(500 << attempt);                      /* 500ms, 1000ms, 2000ms */
            continue;
        }
        if (status >= 400) throw IOException("HTTP %d", status);
        return json_decode(read_all(conn));                /* Gson.fromJson */
    }
}
```

#### (6) `parseResponse` + `clean`: 응답 검사와 값 정리

KB 응답 값은 고정 길이 문자열(`"   368500"`, `"0000000039024.00"`)로 오므로 숫자로 바꿉니다.
단, 코드·날짜처럼 **앞의 0이 의미 있는 값**은 문자열로 남깁니다(`"005930"`을 숫자로 바꾸면 `5930`이 되어 버리기 때문).

```c
map parse_response(tr_code, json data) {
    map out = clean(data.dataBody, "");
    if (strcmp(data.dataHeader.processFlag, "A") != 0)       /* HTTP 200이어도 업무 오류일 수 있음 */
        throw KBApiException(tr_code, processCode, o_msg 또는 msg 또는 processMessage);
    return out;
}

/* 재귀: 객체/배열이면 안으로 들어가고, 문자열이면 숫자 변환 시도 */
void *clean(json v, const char *key) {
    if (is_object(v)) { for each (k, x) in v: out[k] = clean(x, k); return out; }
    if (is_array(v))  { for each x in v: add(out, clean(x, key));  return out; }
    char *s = trim(v);
    if (key가 "dt","tm","wd" 등이거나 "is_"로 시작하거나
        "_cd","_no","_dt","_tm","_ccd","_clsf","_f","_id" 등으로 끝나면) return s;   /* 문자열 유지 */
    if (strchr(s, '.')) return 가능하면 atof(s);   /* Double */
    else                return 가능하면 atoll(s);  /* Long  */
    /* 변환 실패하면 문자열 그대로 */
}
```

---

### 3.4 `Dividends.java`: 배당 내역

계좌 거래내역 TR `SWQA2301`을 전부 받아서 배당 관련 4종류만 골라냅니다.

```c
map dividends_query(int year) {
    if (year < 2000 || year > 올해) throw IllegalArgumentException;   /* → 400 응답 */
    start = "YYYY0101";  end = (올해면 오늘, 아니면 "YYYY1231");
    rows  = kbclient_call_pages(client, "SWQA2301",
                                {strt_dt: start, end_dt: end, is_no: "", srt_clsf: ""},
                                "Record1", 1000);
    for each t in rows:
        e = to_entry(t);            /* 배당 관련이 아니면 NULL */
        if (e) add(entries, e);
    sort(entries, by date);
    return { year, start, end, fetchedAt, entries };
}
```

`toEntry(t)`는 거래 종류(`smry_nm`)에 따라 `switch` 합니다.

| `smry_nm` (거래 종류) | 계산 |
|---|---|
| `배당금 입금` | 원화면 `gross = dl_amt`, `tax = tx` / 외화면 `gross = 외화금액 × 환율` |
| `해외원천세 출금` | `tax = 외화금액 × 환율` |
| `해외원천세 환급 입금` | `tax = -(외화금액 × 환율)` (환급이라 음수) |
| `배당세금추징 출금` | `tax = tx` (0이면 `dl_amt`) |
| 그 밖 | `return NULL` (무시) |

결과 한 줄: `{date, name, code, kind, currency, gross, tax, fx_amount, fx_rate, month, net = gross - tax}`

---

### 3.5 `Heatmap.java`: 화면 데이터 대부분

가장 큰 파일이며, API 3개(`/api/heatmap`, `/api/quarters`, `/api/holdings`)를 처리합니다.

#### 전역 데이터 (`static` 블록 = 시작 시 한 번 채우는 전역 테이블)

```c
/* 미국 대형주 목록: {티커, 거래소, 섹터}. 미국은 순위 TR이 없어서 손으로 적어 둠 */
static const char *US_STOCKS[][3] = {
    {"NVDA", "NAS", "정보기술"}, {"AAPL", "NAS", "정보기술"}, ... {"SPG", "NYS", "부동산"},
};

/* ETF 운용사 브랜드. 종목명 첫 단어가 이 중 하나면 ETF */
static const char *ETF_BRANDS[] = {"KODEX", "TIGER", "RISE", "ACE", ...};

/* ETF 분류 규칙: 위에서부터 처음 맞는 정규식 규칙을 씀 */
static const struct rule { regex_t pattern; const char *kr; const char *us; } ETF_RULES[] = {
    {"채권|국고채|...", "채권·금리·자산배분", NULL},
    {"반도체|HBM|...",  "전기 전자",          "정보기술"},
    ...
};
```

#### 캐시 (서버 메모리)

```c
/* ConcurrentHashMap: 여러 스레드가 동시에 읽고 써도 안전한 해시 테이블 */
static hashmap cache;   /* key → { saved_at_ms, payload } */

map cached(key, seconds) {
    entry *hit = hashmap_get(cache, key);
    return (hit && now_ms() - hit->saved_at < seconds * 1000) ? hit->payload : NULL;
}
map store(key, payload) { hashmap_put(cache, key, {now_ms(), payload}); return payload; }
```

- 히트맵과 보유 종목은 60초, 분기 데이터는 600초 동안 캐시합니다.

#### `orNull(call)`: 한 종목 실패로 전체가 멈추지 않게

```c
void *or_null(void *(*fn)(void)) {
    if (setjmp(env) == 0) return fn();
    else                  return NULL;   /* KBApiException, IOException이면 NULL */
}
```

#### `queryHeatmap(market)`: `GET /api/heatmap?market=kr|us`

```c
map heatmap_query_heatmap(const char *market) {
    if (strcmp(market, "kr") && strcmp(market, "us")) throw IllegalArgumentException;  /* → 400 */
    if ((hit = cached(market, 60))) return hit;                  /* 60초 안이면 KB 안 부름 */
    kbclient_access_token(client);                               /* 병렬 호출 전에 토큰 먼저 받아 둠 */
    stocks = strcmp(market, "kr") == 0 ? fetch_kr() : fetch_us();
    return store(market, { market, currency, fetchedAt, stocks, etf: etf_summary() });
}
```

**`fetchKr()`: 국내 종목**
1. `IVS10920`(시가총액 순위 200개)와 `IVU10210`(거래대금 순위 100개)을 합칩니다. 종목코드가 키이므로 중복은 한 번만 들어갑니다.
2. 각 종목에 대해 `IVM10050`(투자지표)을 **8 스레드 병렬**로 호출해 업종, 시가총액, 거래대금을 얻습니다.
3. 업종이 비어 있으면 ETF나 ETN이므로 뺍니다. 시가총액은 억원 단위라 `× 100,000,000`으로 원 단위로 바꿉니다.

**`fetchUs()`: 미국 종목**: `US_STOCKS` 표의 종목마다 `GSS10030`(해외 현재가)을 병렬로 호출합니다.

**`etfSummary()`**
1. `IVS10920`을 9999개 요청해 상장 종목 전체를 받고, 브랜드로 ETF만 고른 뒤 `classifyEtf(이름)`로 분류합니다.
2. `updateSnapshot()`은 `synchronized`라서 파일 쓰기가 동시에 일어나지 않습니다.
   - `etf_snapshot.json`(`{날짜: [코드...]}`)을 읽고 오늘 목록을 추가합니다. 최근 60일치만 남깁니다.
   - 직전 날짜와 비교해 새로 상장된 코드(`listed`)와 상장폐지된 코드(`delisted`)를 계산합니다.
   - 파일이 깨져 있으면 무시하고 새로 시작합니다.

#### `queryQuarters(market)`: `GET /api/quarters`, 분기별 일평균 거래대금

```c
map heatmap_query_quarters(market) {
    if ((hit = cached("quarters:" + market, 600))) return hit;
    stocks = heatmap_query_heatmap(market).stocks;             /* 히트맵 종목을 그대로 사용 */

    rows = values_parallel(stocks, 4, stock -> {               /* 4 스레드 */
        days = or_null(daily_values(stock, market));           /* 일봉 약 400일: IVS11560 / GSC10060 */
        for each (day, value) in days:
            acc = by_q[quarter_of(day)];                       /* "2026Q3" 같은 키 */
            acc.sum += value; acc.count += 1;                  /* double[2] = {합계, 일수} */
        return { code, name, sector, _last: 가장 최근 날짜, _byQ: by_q };
    });

    /* 가장 최근 날짜 기준으로 최근 5개 분기 목록 만들기 */
    quarters = ["2025Q3", "2025Q4", "2026Q1", "2026Q2", "2026Q3"];
    for each r in rows:
        r.values[i] = sum / count;                             /* 종목별 분기 일평균 */
        sectors[r.sector][i] += r.values[i];                   /* 섹터 값 = 소속 종목 평균의 합 */
    return store(..., { quarters, asOf, sectors, failed: 실패 종목 수, stocks: rows });
}
```

- `dailyValues()`는 날짜 자리에 엉뚱한 숫자가 온 깨진 행을 `validDay()`로 거릅니다. KB 차트 응답에 가끔 필드가 밀린 행이 섞여 오기 때문입니다.
- 국내 차트의 거래대금은 10원 단위라서 `× 10`을 합니다.

#### `queryHoldings()`: `GET /api/holdings`, 내 보유 종목과 현금

1. `SSQM2952`(국내 잔고)를 조회합니다.
   - 현금: `nxt2_dy_tfnd`(D+2 예수금), 외화 환산액, 당일 예수금
   - 원화 종목만 담고, 수량은 결제 후 잔량(`ec_q`) 기준입니다. 해외 종목 행의 원화 평가금액은 따로 모아 둡니다.
2. `SPQM2226`(해외 잔고)를 조회합니다. 소수점 주식 때문에 같은 종목 행이 여러 개일 수 있어 수량을 합칩니다.
3. 종목마다 8 스레드 병렬로 현재가와 등락률을 붙입니다.
   - 국내: `IVU10140`, 업종이 없으면 `IVM10050`
   - 해외: `GSS10030`, 수익률 = (수량 × 현재가 ÷ 매입금액 − 1) × 100
4. 평가금액이 0 이하인 종목은 빼고 `{stocks, cash}`를 돌려줍니다.

---

### 3.6 `AppServlet.java`: 요청 라우터 (진입점)

Tomcat이 부르는 콜백 두 개가 전부입니다.

#### `init()`: 서버 시작 시 한 번

```c
static struct heatmap   *heatmap;
static struct dividends *dividends;
static hashmap apis;    /* "/api/heatmap" → 처리 함수 포인터 */

void app_init(void) {
    dir = getenv("KB_DATA_DIR");
    if (!dir) dir = web_xml_param("dataDir");              /* web.xml의 설정값 */
    if (!dir) dir = "/opt/kb_openapi_sample";
    client    = kbclient_from_env(path_join(dir, ".env")); /* 실패하면 서버가 뜨지 않음 */
    heatmap   = heatmap_new(client, path_join(dir, "etf_snapshot.json"));
    dividends = dividends_new(client);

    /* 람다 = 이름 없는 함수를 테이블에 등록 */
    hashmap_put(apis, "/api/heatmap",   handle_heatmap);   /* market 기본값 "kr" */
    hashmap_put(apis, "/api/quarters",  handle_quarters);
    hashmap_put(apis, "/api/holdings",  handle_holdings);
    hashmap_put(apis, "/api/dividends", handle_dividends); /* year 기본값 올해, 숫자가 아니면 400 */
}
```

#### `doGet(req, resp)`: GET 요청마다 (Tomcat 스레드 여러 개에서 동시에 불림)

```c
void app_do_get(request *req, response *resp) {
    path = req->path_info ? req->path_info : "/";
    set_header(resp, "Cache-Control", "no-store");

    /* 1) 화면 주소면 HTML 파일을 그대로 보냄 */
    if ((file = PAGES[path])) {             /* "/"→home.html, "/heatmap"→heatmap.html, "/me"→me.html */
        if (!exists(file)) return send_error(resp, 404);
        set_content_type(resp, "text/html; charset=utf-8");
        copy_stream(open(file), resp->out); /* 16KB 버퍼로 복사 */
        return;
    }

    /* 2) API 주소면 등록된 함수 호출 */
    handler fn = hashmap_get(apis, path);
    if (!fn) return send_error(resp, 404);

    int status; map payload;
    TRY {
        payload = fn(req);             status = 200;
    } CATCH (IllegalArgumentException, KBApiException) {
        payload = {error: e.message};  status = 400;   /* 잘못된 입력, KB 업무 오류 */
    } CATCH (Exception) {
        payload = {error: e.type + ": " + e.message};  status = 502;   /* 네트워크 오류 등 */
    }
    body = json_encode(payload);       /* Gson: Map → JSON 문자열 */
    resp->status = status;
    set_content_type(resp, "application/json; charset=utf-8");
    write(resp->out, body);
}
```

---

## 4. 요청 한 건의 전체 흐름 (C 스타일)

`GET /api/heatmap?market=kr`가 들어왔을 때:

```c
/* Tomcat 스레드 */
app_do_get(req, resp)
 └─ handle_heatmap(req)                            /* market = "kr" */
     └─ heatmap_query_heatmap("kr")
         ├─ cached("kr", 60)  ─ 있으면 바로 return  ─────────────┐
         ├─ kbclient_access_token()  (토큰 없거나 만료 임박 시 발급) │
         ├─ fetch_kr()                                             │
         │   ├─ kbclient_call("IVS10920")  시총 순위                │
         │   ├─ kbclient_call("IVU10210")  거래대금 순위             │
         │   └─ values_parallel(8 스레드) → 종목마다               │
         │        or_null(kbclient_call("IVM10050"))               │
         │          └─ post() → 5xx면 0.5/1/2초 쉬고 재시도         │
         │          └─ parse_response() → processFlag 검사, clean() │
         ├─ etf_summary() → etf_snapshot.json 갱신                  │
         └─ store("kr", payload)                                   │
 json_encode(payload) → HTTP 200  ◀────────────────────────────────┘
```

---

## 5. 오류 처리 정리

| 어디서 | 무엇 | 결과 |
|---|---|---|
| `init()` | `.env`에 키가 없음 | `ServletException`: 서버(앱)가 시작되지 않음 |
| `queryHeatmap` | `market`이 `kr`/`us`가 아님 | `IllegalArgumentException` → **400** |
| `/api/dividends` | `year`가 숫자가 아니거나 범위 밖 | `IllegalArgumentException` → **400** |
| `parseResponse` | `processFlag != "A"` | `KBApiException` → **400** |
| `post` | 5xx | 최대 3번 재시도 → 그래도 실패하면 `IOException` → **502** |
| `post` | 4xx, 네트워크 끊김, 타임아웃(10초) | `IOException` → **502** |
| 병렬 조회 안 | 종목 하나 실패 | `orNull()`이 NULL로 바꿔 **그 종목만 빠짐** (전체는 성공) |
| `updateSnapshot` | 스냅숏 파일이 깨짐 | 무시하고 새로 시작 |

---

## 6. C 개발자가 헷갈리기 쉬운 부분

- **메모리 해제 코드가 없는 이유**: GC가 회수합니다. `new`만 하고 `free`는 하지 않습니다.
- **`Map<String, Object>`에서 꺼낸 값의 타입**: `Object`(= `void *`)로 나오므로 `(String) m.get("code")`처럼 캐스팅합니다. C의 `(char *)ptr`과 같지만, 타입이 틀리면 크래시 대신 `ClassCastException`이 납니다.
- **`==`와 `.equals()`**: 문자열 비교에 `==`를 쓰면 주소 비교(C의 포인터 비교)가 됩니다. 내용 비교(`strcmp == 0`)는 `"kr".equals(market)`로 씁니다.
- **`h.put("qty", (Double) h.get("qty") + qty)`**: `Double`은 불변 객체라서 값을 바꾸려면 새 값을 계산해 다시 `put`합니다.
- **스레드 안전**: `doGet`은 여러 Tomcat 스레드에서 동시에 불립니다. 그래서 공유 자원에 다음 장치를 씁니다.
  - 토큰 발급: `synchronized` (뮤텍스)
  - 캐시: `ConcurrentHashMap` (락이 내장된 해시 테이블)
  - 스냅숏 파일 쓰기: `synchronized`
- **`500L << attempt`**: C와 같은 비트 시프트입니다. `500 × 2^attempt` = 500, 1000, 2000ms.
- **`computeIfAbsent(key, k -> new double[2])`**: "키가 없으면 만들어 넣고, 그 값을 돌려줘"입니다. C로 치면 `if (!get(m,k)) put(m,k,calloc(2,sizeof(double))); return get(m,k);`
