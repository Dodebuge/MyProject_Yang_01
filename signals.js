// 차트 보조지표와 기술적 시그널 (chart.html, volume.html이 함께 씀). 서버가 /signals.js 로 제공합니다.
// 페이지의 다른 스크립트보다 먼저 불러와야 합니다. 일봉 cs = [{d: "20261016", c: 종가}, ...] (오래된 날부터).

// ---------------------------------------------------------------- 보조지표: 이동평균선, 볼린저밴드(20일, ±2σ), RSI, MACD
const MAS = [5, 20, 60, 120];
const BB = { n: 20, k: 2 };
const RSI_N = 14;
const MACD = { fast: 12, slow: 26, signal: 9 };

// n일 단순이동평균. 앞쪽 n-1일은 null
const sma = (xs, n) => {
  let s = 0;
  return xs.map((v, i) => { s += v - (i >= n ? xs[i - n] : 0); return i >= n - 1 ? s / n : null; });
};
// 볼린저밴드: 중심 = n일 이동평균, 상·하단 = 중심 ± k × n일 표준편차(모집단)
// n일 지수이동평균. 앞쪽 null은 건너뛰고, 첫 값은 처음 n개의 단순평균으로 시작
const ema = (xs, n) => {
  const k = 2 / (n + 1), start = xs.findIndex(v => v != null), out = xs.map(() => null);
  if (start < 0 || start + n > xs.length) return out;
  let e = xs.slice(start, start + n).reduce((a, v) => a + v, 0) / n;
  out[start + n - 1] = e;
  for (let i = start + n; i < xs.length; i++) out[i] = e = xs[i] * k + e * (1 - k);
  return out;
};
// RSI(n): 상승폭 평균 / (상승폭 평균 + 하락폭 평균) × 100. 평균은 와일더 방식 (이전 평균 × (n-1) + 오늘) / n
const rsi = (xs, n) => {
  const out = xs.map(() => null);
  let up = 0, down = 0;
  for (let i = 1; i < xs.length; i++) {
    const d = xs[i] - xs[i - 1], u = Math.max(d, 0), w = Math.max(-d, 0);
    if (i <= n) { up += u / n; down += w / n; } else { up = (up * (n - 1) + u) / n; down = (down * (n - 1) + w) / n; }
    if (i >= n) out[i] = up + down ? up / (up + down) * 100 : 50;
  }
  return out;
};
// MACD = 빠른 EMA - 느린 EMA, 시그널 = MACD의 EMA, 히스토그램 = MACD - 시그널
const macd = (xs, f, sl, sg) => {
  const a = ema(xs, f), b = ema(xs, sl), m = a.map((v, i) => v == null || b[i] == null ? null : v - b[i]), s = ema(m, sg);
  return m.map((v, i) => v == null ? null : { m: v, s: s[i], h: s[i] == null ? null : v - s[i] });
};
const bollinger = (xs, n, k) => sma(xs, n).map((m, i) => {
  if (m == null) return null;
  const sd = Math.sqrt(xs.slice(i - n + 1, i + 1).reduce((a, v) => a + (v - m) ** 2, 0) / n);
  return { mid: m, up: m + k * sd, lo: m - k * sd };
});

// ---------------------------------------------------------------- 기술적 시그널
// 마지막 날 기준. 교차는 최근 SIG_DAYS 거래일 안에 일어난 것만. dir: 1 매수 쪽, -1 매도 쪽
const SIG_DAYS = 5;
function signalsOf(cs) {
  const xs = cs.map(c => c.c), last = xs.length - 1, out = [];
  if (xs.length < 30) return out;
  // a가 b를 위로 뚫으면 1, 아래로 뚫으면 -1 (가장 최근 교차)
  const cross = (a, b) => {
    for (let i = last; i > Math.max(0, last - SIG_DAYS); i--) {
      if ([a[i], b[i], a[i - 1], b[i - 1]].includes(null)) return null;
      if (a[i - 1] <= b[i - 1] && a[i] > b[i]) return { dir: 1, d: cs[i].d };
      if (a[i - 1] >= b[i - 1] && a[i] < b[i]) return { dir: -1, d: cs[i].d };
    }
    return null;
  };
  const ma = Object.fromEntries(MAS.map(n => [n, sma(xs, n)]));
  for (const [f, sl] of [[5, 20], [20, 60]]) {
    const c = cross(ma[f], ma[sl]);
    if (c) out.push({ ...c, text: `${f}·${sl}일선 ${c.dir > 0 ? "골든" : "데드"}크로스` });
  }
  const m = macd(xs, MACD.fast, MACD.slow, MACD.signal), mc = cross(m.map(v => v?.m ?? null), m.map(v => v?.s ?? null));
  if (mc) out.push({ ...mc, text: `MACD 시그널 ${mc.dir > 0 ? "상향" : "하향"} 돌파` });
  const r = rsi(xs, RSI_N)[last];
  if (r >= 70) out.push({ dir: -1, text: `RSI 과매수 ${Math.round(r)}` });
  else if (r <= 30) out.push({ dir: 1, text: `RSI 과매도 ${Math.round(r)}` });
  const b = bollinger(xs, BB.n, BB.k)[last];
  if (b && xs[last] > b.up) out.push({ dir: -1, text: "볼린저 상단 돌파 (과열)" });
  else if (b && xs[last] < b.lo) out.push({ dir: 1, text: "볼린저 하단 이탈 (침체)" });
  const [a5, a20, a60, a120] = MAS.map(n => ma[n][last]);
  if (a120 != null && a5 > a20 && a20 > a60 && a60 > a120) out.push({ dir: 1, text: "이평선 정배열" });
  else if (a120 != null && a5 < a20 && a20 < a60 && a60 < a120) out.push({ dir: -1, text: "이평선 역배열" });
  return out;
}
// 시그널 태그 HTML (.sig, .sig.buy, .sig.sell 스타일은 페이지에서)
const sigBadges = list => list.length
  ? list.map(g => `<span class="sig ${g.dir > 0 ? "buy" : "sell"}" title="${g.d ? `${g.d.slice(0, 4)}-${g.d.slice(4, 6)}-${g.d.slice(6)} 발생` : "마지막 거래일 기준"}">${g.dir > 0 ? "▲" : "▼"} ${g.text.replace(/[&<>"]/g, c => `&#${c.charCodeAt(0)};`)}${g.d ? ` ${+g.d.slice(4, 6)}/${+g.d.slice(6)}` : ""}</span>`).join("")
  : `<span class="sig">특이 시그널 없음</span>`;
const sigScore = list => {
  const n = list.reduce((a, g) => a + g.dir, 0);
  return n > 0 ? `<b class="up">매수 우위 +${n}</b>` : n < 0 ? `<b class="down">매도 우위 ${n}</b>` : `<span class="sub">중립</span>`;
};
