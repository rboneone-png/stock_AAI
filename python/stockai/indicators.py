"""技術指標（純 pandas 實作，不依賴 TA-Lib）。"""
from __future__ import annotations

import numpy as np
import pandas as pd


def sma(s: pd.Series, n: int) -> pd.Series:
    return s.rolling(n, min_periods=n).mean()


def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False).mean()


def rsi(close: pd.Series, n: int = 14) -> pd.Series:
    delta = close.diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    rs = gain / loss.replace(0, np.nan)
    out = 100 - 100 / (1 + rs)
    return out.where(loss != 0, 100.0).where(gain.notna())


def macd(close: pd.Series, fast: int = 12, slow: int = 26, signal: int = 9):
    dif = ema(close, fast) - ema(close, slow)
    dea = ema(dif, signal)
    return dif, dea, dif - dea


def bollinger(close: pd.Series, n: int = 20, k: float = 2.0):
    mid = sma(close, n)
    std = close.rolling(n, min_periods=n).std(ddof=0)
    return mid + k * std, mid, mid - k * std


def atr(df: pd.DataFrame, n: int = 14) -> pd.Series:
    prev = df["Close"].shift()
    tr = pd.concat([df["High"] - df["Low"],
                    (df["High"] - prev).abs(),
                    (df["Low"] - prev).abs()], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / n, adjust=False, min_periods=n).mean()


def kd(df: pd.DataFrame, n: int = 9):
    """台股常用 KD 隨機指標（RSV 以 1/3 平滑）。"""
    low_n = df["Low"].rolling(n, min_periods=n).min()
    high_n = df["High"].rolling(n, min_periods=n).max()
    rsv = ((df["Close"] - low_n) / (high_n - low_n).replace(0, np.nan) * 100).fillna(50)
    k = rsv.ewm(alpha=1 / 3, adjust=False).mean()
    d = k.ewm(alpha=1 / 3, adjust=False).mean()
    k[low_n.isna()] = np.nan
    d[low_n.isna()] = np.nan
    return k, d


def obv(df: pd.DataFrame) -> pd.Series:
    direction = np.sign(df["Close"].diff()).fillna(0)
    return (direction * df["Volume"]).cumsum()


def add_all(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    c = out["Close"]
    for n in (5, 20, 60, 120):
        out[f"MA{n}"] = sma(c, n)
    out["RSI"] = rsi(c)
    out["DIF"], out["DEA"], out["MACD_H"] = macd(c)
    out["BB_UP"], out["BB_MID"], out["BB_LOW"] = bollinger(c)
    out["ATR"] = atr(out)
    out["K"], out["D"] = kd(out)
    out["OBV"] = obv(out)
    out["VOL_MA20"] = sma(out["Volume"], 20)
    out["RET"] = c.pct_change()
    return out
