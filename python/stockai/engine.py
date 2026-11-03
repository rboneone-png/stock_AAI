"""分析引擎：整合資料、指標、評分、風險、回測。"""
from __future__ import annotations

import logging
from functools import lru_cache

import pandas as pd

from . import data, fundamentals, indicators, risk, signals

log = logging.getLogger(__name__)


def benchmark_for(symbol: str) -> str:
    s = symbol.upper()
    return "^TWII" if s.endswith((".TW", ".TWO")) else "^GSPC"


@lru_cache(maxsize=8)
def _bench(sym: str, period: str, source: str) -> pd.Series | None:
    # 指定的個股來源未必支援指數（例如 tpex），大盤一律走 auto 備援
    src = source if source in ("demo",) or source.startswith("csv:") else "auto"
    if src.startswith("csv:"):
        return None
    try:
        return data.get_history(sym, period, src)["Close"]
    except Exception as e:  # 大盤資料抓不到不影響個股分析
        log.warning("大盤 %s 取得失敗：%s", sym, e)
        return None


def analyze(symbol: str, period: str = "2y", source: str = "auto",
            capital: float = 1_000_000, risk_pct: float = 0.01,
            df: pd.DataFrame | None = None) -> tuple[signals.Analysis, pd.DataFrame]:
    online = source not in ("demo",) and not source.startswith("csv:")
    if df is not None:
        raw, sym, used = df, symbol, "custom"
    else:
        raw, sym, used = data.get_history_ex(data.normalize_symbol(symbol), period, source)
    if len(raw) < 130:
        raise ValueError(f"{sym} 歷史資料只有 {len(raw)} 筆，至少需要 130 筆")
    bench = _bench(benchmark_for(sym) if online else "BENCH", period, source)

    d = indicators.add_all(raw)
    fs = signals.factor_scores(d, bench)
    d["SCORE"] = fs.sum(axis=1).clip(-100, 100)
    score = float(d["SCORE"].iloc[-1])
    name, icon = signals.rating_of(score)
    lot = 1000 if sym.endswith((".TW", ".TWO")) else 1
    bt = risk.backtest(d, d["SCORE"])
    d["EQUITY"], d["BH"] = bt.pop("_equity"), bt.pop("_bh")

    a = signals.Analysis(
        symbol=sym, date=d.index[-1], price=float(d["Close"].iloc[-1]),
        change_pct=float(d["RET"].iloc[-1] * 100), score=score, rating=name, icon=icon,
        factors={k: float(v) for k, v in fs.iloc[-1].items()},
        reasons=signals.explain(d),
        plan=signals.trade_plan(d, capital, risk_pct, lot, score),
        risk=risk.risk_metrics(d, bench),
        backtest=bt,
        levels=signals.key_levels(d),
        source=used,
        fundamentals=fundamentals.get(sym, float(d["Close"].iloc[-1])) if online else {},
    )
    a.reasons += fundamentals.notes(a.fundamentals)
    for n in raw.attrs.get("notes", []):
        a.reasons.append(f"🔧 {n}")
    lag = (pd.Timestamp.today().normalize() - d.index[-1]).days
    if online and lag > 4:
        a.reasons.append(f"⚠️ 資料最新只到 {d.index[-1]:%Y-%m-%d}（落後 {lag} 天），來源「{used}」可能延遲")
    return a, d


def scan(symbols: list[str], **kw) -> tuple[list[signals.Analysis], dict, list[str]]:
    results, frames, errors = [], {}, []
    for s in symbols:
        try:
            a, d = analyze(s, **kw)
            results.append(a)
            frames[a.symbol] = d
        except Exception as e:
            errors.append(f"{s}: {e}")
            log.error("分析 %s 失敗：%s", s, e)
    results.sort(key=lambda x: x.score, reverse=True)
    return results, frames, errors
