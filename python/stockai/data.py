"""資料入口：多重線上來源（見 sources.py）、本機 CSV、離線示範資料。"""
from __future__ import annotations

import logging
from pathlib import Path

import numpy as np
import pandas as pd

log = logging.getLogger(__name__)

COLUMNS = ["Open", "High", "Low", "Close", "Volume"]


def normalize_symbol(symbol: str) -> str:
    """統一大寫；純數字台股代號保留原樣，由資料來源自動判斷上市(.TW)或上櫃(.TWO)。"""
    return symbol.strip().upper()


def _clean(df: pd.DataFrame) -> pd.DataFrame:
    df = df[[c for c in COLUMNS if c in df.columns]].copy()
    df.index = pd.to_datetime(df.index)
    if getattr(df.index, "tz", None) is not None:
        df.index = df.index.tz_localize(None)
    df = df.sort_index()
    df = df[~df.index.duplicated(keep="last")]
    df = df.dropna(subset=["Close"])
    df["Volume"] = df["Volume"].fillna(0)
    return df


def load_csv(path: str | Path) -> pd.DataFrame:
    """CSV 需包含 Date, Open, High, Low, Close, Volume 欄位。"""
    df = pd.read_csv(path)
    date_col = next(c for c in df.columns if c.lower() in ("date", "datetime", "日期"))
    df = df.set_index(date_col)
    df.columns = [c.capitalize() for c in df.columns]
    return _clean(df)


def synthetic(symbol: str, days: int = 500, seed: int | None = None) -> pd.DataFrame:
    """產生具趨勢與波動切換的模擬 K 線，用於離線示範與測試。"""
    rng = np.random.default_rng(seed if seed is not None else abs(hash(symbol)) % 2**32)
    regime = np.repeat(rng.normal(0.0004, 0.0015, days // 60 + 1), 60)[:days]
    rets = regime + rng.normal(0, 0.018, days)
    close = 100 * np.exp(np.cumsum(rets))
    spread = np.abs(rng.normal(0, 0.012, days)) * close
    open_ = close * (1 + rng.normal(0, 0.006, days))
    high = np.maximum(open_, close) + spread / 2
    low = np.minimum(open_, close) - spread / 2
    vol = rng.lognormal(15, 0.4, days) * (1 + 5 * np.abs(rets))
    idx = pd.bdate_range(end=pd.Timestamp.today().normalize(), periods=days)
    return pd.DataFrame({"Open": open_, "High": high, "Low": low,
                         "Close": close, "Volume": vol.round()}, index=idx)


def get_history(symbol: str, period: str = "2y", source: str = "auto") -> pd.DataFrame:
    return get_history_ex(symbol, period, source)[0]


def get_history_ex(symbol: str, period: str = "2y",
                   source: str = "auto") -> tuple[pd.DataFrame, str, str]:
    """回傳 (資料, 實際代號, 來源名稱)。"""
    if source == "demo":
        return synthetic(symbol), symbol, "demo"
    if source.startswith("csv:"):
        return load_csv(source[4:]), symbol, "csv"
    from . import sources
    df, sym, src = sources.fetch(symbol.strip(), period, source)
    notes = list(df.attrs.get("notes", []))
    df = _clean(df)
    df.attrs["notes"] = notes
    return df, sym, src
