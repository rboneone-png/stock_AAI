"""多重資料來源與自動備援。

價格來源（日線 OHLCV）
  yahoo      Yahoo Finance chart API 直連（不需 yfinance、不需 crumb）       台/美
  twse       臺灣證券交易所官方 STOCK_DAY（上市）                          台
  tpex       證券櫃檯買賣中心官方（上櫃）                                   台
  finmind    FinMind 開放資料（可設 FINMIND_TOKEN 提高額度）                台/美
  nasdaq     Nasdaq.com 歷史報價 API                                      美
  alphavantage  Alpha Vantage（需 ALPHAVANTAGE_API_KEY）                    美
  yfinance   yfinance 套件                                                台/美

auto 模式會依市場別依序嘗試，前一個失敗或資料不足就自動換下一個。
"""
from __future__ import annotations

import json
import logging
import os
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path

import pandas as pd

log = logging.getLogger(__name__)

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
CACHE_DIR = Path(os.environ.get("STOCKAI_CACHE", Path.home() / ".cache" / "stockai"))
PERIOD_DAYS = {"6mo": 183, "1y": 366, "2y": 731, "3y": 1096, "5y": 1827, "10y": 3653}
MIN_ROWS = 130

PRICE_ORDER = {
    "tw": ["yahoo", "twse", "tpex", "finmind", "yfinance"],
    "us": ["yahoo", "nasdaq", "finmind", "alphavantage", "yfinance"],
}


SUPPORTS = {"yahoo": {"tw", "us"}, "twse": {"tw"}, "tpex": {"tw"}, "finmind": {"tw", "us"},
            "nasdaq": {"us"}, "alphavantage": {"us"}, "yfinance": {"tw", "us"}}
# 可用環境變數停用某些來源，例如 STOCKAI_DISABLE=yahoo,yfinance
DISABLED = {x.strip() for x in os.environ.get("STOCKAI_DISABLE", "").split(",") if x.strip()}


class SourceError(Exception):
    pass


# ------------------------------------------------------------------ 共用工具
def _ssl_ctx():
    for f in (os.environ.get("SSL_CERT_FILE"), os.environ.get("REQUESTS_CA_BUNDLE"),
              "/root/.ccr/ca-bundle.crt"):
        if f and Path(f).exists():
            return ssl.create_default_context(cafile=f)
    return ssl.create_default_context()


_last_hit: dict[str, float] = {}
# 各網域最小請求間隔（秒）；TWSE 超過約每 5 秒 3 次會封鎖 IP
THROTTLE = {"www.twse.com.tw": 3.5, "www.tpex.org.tw": 1.2, "www.alphavantage.co": 12.5}


def http_get(url: str, params: dict | None = None, timeout: float = 15) -> bytes:
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"
    host = urllib.parse.urlparse(url).netloc
    wait = THROTTLE.get(host, 0) - (time.time() - _last_hit.get(host, 0))
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json, text/plain, */*"})
    err = None
    for attempt in range(3):  # 307/429/5xx 多為暫時性限流，退避後重試
        try:
            with urllib.request.urlopen(req, timeout=timeout, context=_ssl_ctx()) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            err = e
            if e.code not in (307, 429, 500, 502, 503, 504):
                break
        except Exception as e:
            err = e
        finally:
            _last_hit[host] = time.time()
        time.sleep(5 * (attempt + 1))
    raise SourceError(f"{host} 連線失敗：{err}") from err


def get_json(url: str, params: dict | None = None, timeout: float = 15):
    raw = http_get(url, params, timeout)
    try:
        return json.loads(raw)
    except ValueError as e:
        raise SourceError(f"回應不是 JSON（可能被擋）：{raw[:80]!r}") from e


def _num(x) -> float:
    if x is None:
        return float("nan")
    s = str(x).replace(",", "").replace("$", "").replace("+", "").replace("%", "").strip()
    if s in ("", "--", "---", "N/A", "X0.00"):
        return float("nan")
    try:
        return float(s.lstrip("X"))
    except ValueError:
        return float("nan")


def _roc_date(s: str) -> pd.Timestamp:
    """民國日期 115/10/01 → 2026-10-01"""
    y, m, d = s.strip().replace("＊", "").split("/")
    return pd.Timestamp(int(y) + 1911, int(m), int(d))


def _frame(rows: list[dict]) -> pd.DataFrame:
    df = pd.DataFrame(rows)
    if df.empty:
        raise SourceError("沒有資料")
    df = df.set_index("Date").sort_index()
    df = df[~df.index.duplicated(keep="last")]
    for c in ("Open", "High", "Low", "Close", "Volume"):
        df[c] = pd.to_numeric(df.get(c), errors="coerce")
    df["Volume"] = df["Volume"].fillna(0)
    # 無成交日（開高低為空）用收盤價補
    for c in ("Open", "High", "Low"):
        df[c] = df[c].fillna(df["Close"])
    df = df.dropna(subset=["Close"])
    df = df[df["Close"] > 0]  # 無成交日部分來源填 0
    return df[["Open", "High", "Low", "Close", "Volume"]]


def _months(start: date, end: date):
    y, m = start.year, start.month
    while (y, m) <= (end.year, end.month):
        yield y, m
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)


def _monthly_cached(key: str, y: int, m: int, fetch):
    """官方月資料：已結束的月份永久快取，當月只快取 10 分鐘。"""
    f = CACHE_DIR / "monthly" / f"{key}_{y}{m:02d}.json"
    today = date.today()
    current = (y, m) == (today.year, today.month)
    if f.exists() and (not current or time.time() - f.stat().st_mtime < 600):
        return json.loads(f.read_text("utf-8"))
    rows = fetch()
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(rows, ensure_ascii=False), "utf-8")
    return rows


# ------------------------------------------------------------------ 市場判斷
def market_of(symbol: str) -> str:
    s = symbol.upper()
    if s.endswith((".TW", ".TWO")) or s.split(".")[0].isdigit() or s == "^TWII":
        return "tw"
    return "us"


def tw_code(symbol: str) -> str:
    return symbol.upper().split(".")[0]


# ------------------------------------------------------------------ 各來源
def src_yahoo(symbol: str, days: int) -> tuple[pd.DataFrame, str]:
    s = symbol.upper()
    cands = [s] if "." in s or s.startswith("^") or market_of(s) == "us" else [f"{s}.TW", f"{s}.TWO"]
    rng = "10y" if days > 1827 else "5y" if days > 1096 else "2y" if days > 366 else "1y"
    last = None
    for sym in cands:
        for host in ("query1", "query2"):
            try:
                j = get_json(f"https://{host}.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(sym)}",
                             {"range": rng, "interval": "1d", "events": "div,splits"})
                res = (j.get("chart") or {}).get("result")
                if not res:
                    raise SourceError(str((j.get("chart") or {}).get("error")))
                r = res[0]
                q = r["indicators"]["quote"][0]
                adj = (r["indicators"].get("adjclose") or [{}])[0].get("adjclose")
                idx = pd.to_datetime(r["timestamp"], unit="s") + pd.Timedelta(
                    seconds=r["meta"].get("gmtoffset", 0))
                df = pd.DataFrame({"Open": q["open"], "High": q["high"], "Low": q["low"],
                                   "Close": q["close"], "Volume": q["volume"]},
                                  index=idx.normalize(), dtype="float64")
                if adj:  # 還原權息：依調整係數縮放 OHLC
                    f = pd.Series(adj, index=df.index, dtype="float64") / df["Close"]
                    for c in ("Open", "High", "Low", "Close"):
                        df[c] = df[c] * f
                df.index.name = "Date"
                return _frame(df.reset_index().to_dict("records")), sym
            except SourceError as e:
                last = e
    raise SourceError(f"Yahoo 無資料：{last}")


def _official_months(key: str, days: int, fetch_month) -> pd.DataFrame:
    """由新到舊逐月抓取官方資料；最近兩個月都沒資料視為代號不存在，遇到空月（上市前）即停止。"""
    end, start = date.today(), date.today() - timedelta(days=days)
    months = list(_months(start, end))[::-1]
    rows = []
    for i, (y, m) in enumerate(months):
        try:
            got = _monthly_cached(key, y, m, lambda y=y, m=m: fetch_month(y, m))
        except SourceError as e:
            if len(rows) >= MIN_ROWS:  # 已有足夠連續的近期資料，較舊月份失敗就截斷
                log.warning("%s %d-%02d 抓取失敗，改用較短歷史：%s", key, y, m, e)
                break
            raise
        if got:
            rows += got
            continue
        if rows or i >= 1:
            if not rows:
                raise SourceError("查無此代號資料")
            break
    if not rows:
        raise SourceError("查無此代號資料")
    return _frame([{**r, "Date": pd.Timestamp(r["Date"])} for r in rows])


def src_twse(symbol: str, days: int) -> tuple[pd.DataFrame, str]:
    code = tw_code(symbol)
    if symbol.upper().endswith(".TWO"):
        raise SourceError("上櫃股票請用 tpex")

    def fetch_month(y, m):
        if code == "^TWII":
            j = get_json("https://www.twse.com.tw/rwd/zh/TAIEX/MI_5MINS_HIST",
                         {"date": f"{y}{m:02d}01", "response": "json"})
            return [{"Date": str(_roc_date(r[0]).date()), "Open": _num(r[1]), "High": _num(r[2]),
                     "Low": _num(r[3]), "Close": _num(r[4]), "Volume": 0} for r in j.get("data") or []]
        j = get_json("https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY",
                     {"date": f"{y}{m:02d}01", "stockNo": code, "response": "json"})
        if j.get("stat") != "OK":
            return []
        return [{"Date": str(_roc_date(r[0]).date()), "Open": _num(r[3]), "High": _num(r[4]),
                 "Low": _num(r[5]), "Close": _num(r[6]), "Volume": _num(r[1])} for r in j["data"]]

    df = _official_months(f"twse_{code.replace('^', '_')}", days, fetch_month)
    return df, "^TWII" if code == "^TWII" else f"{code}.TW"


def src_tpex(symbol: str, days: int) -> tuple[pd.DataFrame, str]:
    code = tw_code(symbol)
    if symbol.upper().endswith(".TW") or code == "^TWII":
        raise SourceError("上市股票請用 twse")

    def fetch_month(y, m):
        j = get_json("https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingStock",
                     {"code": code, "date": f"{y}/{m:02d}/01", "response": "json"})
        data = (j.get("tables") or [{}])[0].get("data") or []
        # 成交張數 × 1000 = 股數
        return [{"Date": str(_roc_date(r[0]).date()), "Open": _num(r[3]), "High": _num(r[4]),
                 "Low": _num(r[5]), "Close": _num(r[6]), "Volume": _num(r[1]) * 1000} for r in data]

    return _official_months(f"tpex_{code}", days, fetch_month), f"{code}.TWO"


def src_finmind(symbol: str, days: int) -> tuple[pd.DataFrame, str]:
    tw = market_of(symbol) == "tw"
    if tw and symbol.upper() == "^TWII":
        data_id, dataset = "TAIEX", "TaiwanStockPrice"
    elif tw:
        data_id, dataset = tw_code(symbol), "TaiwanStockPrice"
    else:
        data_id, dataset = symbol.upper(), "USStockPrice"
    params = {"dataset": dataset, "data_id": data_id,
              "start_date": str(date.today() - timedelta(days=days))}
    if os.environ.get("FINMIND_TOKEN"):
        params["token"] = os.environ["FINMIND_TOKEN"]
    j = get_json("https://api.finmindtrade.com/api/v4/data", params, timeout=30)
    if j.get("status") != 200:
        raise SourceError(f"FinMind：{j.get('msg')}（可設定 FINMIND_TOKEN）")
    if tw:
        rows = [{"Date": pd.Timestamp(r["date"]), "Open": r["open"], "High": r["max"], "Low": r["min"],
                 "Close": r["close"], "Volume": r["Trading_Volume"]} for r in j["data"]]
        return _frame(rows), symbol.upper() if "." in symbol or symbol.startswith("^") else f"{data_id}.TW"
    rows = [{"Date": pd.Timestamp(r["date"]), "Open": r["Open"], "High": r["High"], "Low": r["Low"],
             "Close": r["Close"], "Volume": r["Volume"]} for r in j["data"]]
    return _frame(rows), symbol.upper()


def src_nasdaq(symbol: str, days: int) -> tuple[pd.DataFrame, str]:
    sym = symbol.upper()
    if sym == "^GSPC":
        sym = "SPY"  # Nasdaq API 不提供 S&P 500 指數歷史，以 SPY ETF 代替
    start, end = date.today() - timedelta(days=days), date.today()
    last = None
    for ac in ("stocks", "etf"):
        try:
            j = get_json(f"https://api.nasdaq.com/api/quote/{urllib.parse.quote(sym)}/historical",
                         {"assetclass": ac, "fromdate": str(start), "todate": str(end), "limit": 9999})
            t = ((j.get("data") or {}).get("tradesTable") or {}).get("rows")
            if not t:
                raise SourceError(str((j.get("status") or {}).get("bCodeMessage")))
            rows = [{"Date": pd.Timestamp(datetime.strptime(r["date"], "%m/%d/%Y")),
                     "Open": _num(r["open"]), "High": _num(r["high"]), "Low": _num(r["low"]),
                     "Close": _num(r["close"]), "Volume": _num(r["volume"])} for r in t]
            return _frame(rows), symbol.upper()
        except SourceError as e:
            last = e
    raise SourceError(f"Nasdaq 無資料：{last}")


def src_alphavantage(symbol: str, days: int) -> tuple[pd.DataFrame, str]:
    key = os.environ.get("ALPHAVANTAGE_API_KEY")
    if not key:
        raise SourceError("未設定 ALPHAVANTAGE_API_KEY")
    j = get_json("https://www.alphavantage.co/query",
                 {"function": "TIME_SERIES_DAILY", "symbol": symbol.upper(),
                  "outputsize": "full" if days > 140 else "compact", "apikey": key}, timeout=30)
    ts = j.get("Time Series (Daily)")
    if not ts:  # 免費方案不支援 full 時退回 compact
        j = get_json("https://www.alphavantage.co/query",
                     {"function": "TIME_SERIES_DAILY", "symbol": symbol.upper(), "apikey": key}, timeout=30)
        ts = j.get("Time Series (Daily)")
    if not ts:
        raise SourceError(f"Alpha Vantage：{j.get('Note') or j.get('Information') or j.get('Error Message')}")
    rows = [{"Date": pd.Timestamp(d), "Open": v["1. open"], "High": v["2. high"], "Low": v["3. low"],
             "Close": v["4. close"], "Volume": v["5. volume"]} for d, v in ts.items()]
    return _frame(rows), symbol.upper()


def src_yfinance(symbol: str, days: int) -> tuple[pd.DataFrame, str]:
    try:
        import yfinance as yf
        logging.getLogger("yfinance").setLevel(logging.CRITICAL)
    except ImportError as e:
        raise SourceError("未安裝 yfinance") from e
    s = symbol.upper()
    cands = [s] if "." in s or s.startswith("^") or market_of(s) == "us" else [f"{s}.TW", f"{s}.TWO"]
    for sym in cands:
        raw = yf.download(sym, start=str(date.today() - timedelta(days=days)), interval="1d",
                          auto_adjust=True, progress=False, threads=False)
        if raw is not None and not raw.empty:
            if isinstance(raw.columns, pd.MultiIndex):
                raw.columns = raw.columns.get_level_values(0)
            raw.index = pd.to_datetime(raw.index).tz_localize(None) if raw.index.tz else raw.index
            raw.index.name = "Date"
            return _frame(raw.reset_index().to_dict("records")), sym
    raise SourceError("yfinance 無資料")


SOURCES = {"yahoo": src_yahoo, "twse": src_twse, "tpex": src_tpex, "finmind": src_finmind,
           "nasdaq": src_nasdaq, "alphavantage": src_alphavantage, "yfinance": src_yfinance}
OFFICIAL_ADJUST_NOTE = {"twse", "tpex", "finmind", "nasdaq", "alphavantage"}


# ------------------------------------------------------------------ 分割偵測
COMMON_RATIOS = (2, 3, 4, 5, 6, 8, 10, 15, 20, 25, 30, 50)


def adjust_splits(df: pd.DataFrame, mkt: str) -> tuple[pd.DataFrame, list[str]]:
    """還原未調整來源中的股票分割/反分割。

    台股漲跌幅限制 10%，單日變動超過 15% 必為分割或減資；美股則要求比率接近常見分割比例。
    """
    ratio = (df["Close"].shift() / df["Close"])
    notes, df = [], df.copy()
    for dt, r in ratio.items():
        if pd.isna(r):
            continue
        k = None
        if r > 1.6 or r < 0.625:
            inv = r if r > 1 else 1 / r
            near = min(COMMON_RATIOS, key=lambda c: abs(c - inv))
            if abs(near - inv) / near < 0.06:
                k = near if r > 1 else 1 / near
            elif mkt == "tw" and (r > 1.15 or r < 0.85):
                k = r  # 減資等非整數比例
        if k:
            mask = df.index < dt
            df.loc[mask, ["Open", "High", "Low", "Close"]] /= k
            df.loc[mask, "Volume"] *= k
            notes.append(f"{dt:%Y-%m-%d} 偵測到{'分割' if k > 1 else '反分割/減資'}（×{k:.4g}），已還原")
    return df, notes


# ------------------------------------------------------------------ 對外介面
def fetch(symbol: str, period: str = "2y", source: str = "auto",
          cache_ttl: float = 600) -> tuple[pd.DataFrame, str, str]:
    """回傳 (資料, 實際代號, 使用的來源)。source 可為 auto、單一來源或以逗號分隔的順序。"""
    days = PERIOD_DAYS.get(period, 731)
    mkt = market_of(symbol)
    order = PRICE_ORDER[mkt] if source == "auto" else [s.strip() for s in source.split(",")]
    order = [s for s in order if mkt in SUPPORTS.get(s, {mkt}) and s not in DISABLED]
    # 確認是上櫃股就不必再試上市
    if symbol.upper().endswith(".TWO"):
        order = [s for s in order if s != "twse"]
    if symbol.upper().endswith(".TW"):
        order = [s for s in order if s != "tpex"]

    errors = []
    for name in order:
        fn = SOURCES.get(name)
        if fn is None:
            errors.append(f"{name}: 未知來源")
            continue
        cf = CACHE_DIR / "prices" / f"{name}_{symbol.upper().replace('^', '_')}_{period}.pkl"
        try:
            if cache_ttl and cf.exists() and time.time() - cf.stat().st_mtime < cache_ttl:
                df, sym = pd.read_pickle(cf)
            else:
                df, sym = fn(symbol, days)
                cf.parent.mkdir(parents=True, exist_ok=True)
                pd.to_pickle((df, sym), cf)
            df = df[df.index >= pd.Timestamp(date.today() - timedelta(days=days))]
            if len(df) < MIN_ROWS and not symbol.startswith("^"):
                raise SourceError(f"資料只有 {len(df)} 筆")
            if name in OFFICIAL_ADJUST_NOTE and not symbol.startswith("^"):
                df, notes = adjust_splits(df, mkt)
                df.attrs["notes"] = notes
            short = (df.index[0] - pd.Timestamp(date.today() - timedelta(days=days))).days
            if short > 45 and not symbol.startswith("^"):
                df.attrs["notes"] = df.attrs.get("notes", []) + [
                    f"歷史只從 {df.index[0]:%Y-%m-%d} 起（少於要求的 {period}），回測與風險指標的期間較短，不宜直接與其他來源比較"]
            log.info("%s 使用來源 %s（%d 筆）", symbol, name, len(df))
            return df, sym, name
        except Exception as e:  # 任何錯誤都換下一個來源
            log.info("%s 來源 %s 失敗：%s", symbol, name, e)
            errors.append(f"{name}: {e}")
    raise SourceError(f"{symbol} 所有來源都失敗 → " + " | ".join(errors))


def check_all(symbols=("2330", "6488", "AAPL")) -> list[tuple[str, str, str]]:
    """測試每個來源是否可用。"""
    out = []
    for sym in symbols:
        mkt = market_of(sym)
        for name in [n for n in PRICE_ORDER[mkt] if n not in DISABLED]:
            t0 = time.time()
            try:
                df, s, _ = fetch(sym, "1y", name, cache_ttl=0)
                out.append((sym, name, f"✅ {len(df)} 筆，最新 {df.index[-1]:%Y-%m-%d} 收 {df['Close'].iloc[-1]:,.2f}"
                                       f"（{time.time() - t0:.1f}s）"))
            except Exception as e:
                out.append((sym, name, f"❌ {str(e).split('→')[-1].strip()[:90]}"))
    return out
