"""多因子評分系統：把各項技術面訊號整合成 -100 ~ +100 的綜合分數。

每個因子都是「向量化」計算，因此同一套分數可以直接拿來回測。
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

# 因子權重上限（加總 = 100）
WEIGHTS = {"趨勢": 30, "動能": 25, "KD": 15, "布林": 10, "量能": 10, "相對強弱": 10}

RATINGS = [(50, "強力買進", "🟢🟢"), (20, "買進", "🟢"), (-20, "觀望", "⚪"),
           (-50, "賣出", "🔴"), (-101, "強力賣出", "🔴🔴")]


def rating_of(score: float) -> tuple[str, str]:
    for th, name, icon in RATINGS:
        if score >= th:
            return name, icon
    return RATINGS[-1][1], RATINGS[-1][2]


def factor_scores(df: pd.DataFrame, bench: pd.Series | None = None) -> pd.DataFrame:
    """回傳每日各因子分數，df 必須已經過 indicators.add_all。"""
    c = df["Close"]
    f = pd.DataFrame(index=df.index)

    # 1. 趨勢：價格相對均線 + 均線排列 + MA20 斜率
    t = (np.sign(c - df["MA20"]) * 8 + np.sign(c - df["MA60"]) * 8
         + np.sign(df["MA20"] - df["MA60"]) * 8
         + np.sign(df["MA20"].diff(5)) * 6)
    f["趨勢"] = t

    # 2. 動能：MACD 柱狀體方向 + RSI 區間
    h = df["MACD_H"]
    m = np.sign(h) * 7 + np.sign(h.diff()) * 5 + np.sign(df["DIF"]) * 3
    r = df["RSI"]
    rsi_pts = pd.Series(0.0, index=df.index)
    rsi_pts[(r > 50) & (r <= 70)] = 6
    rsi_pts[(r < 50) & (r >= 30)] = -6
    rsi_pts[r > 80] = -10          # 過熱
    rsi_pts[(r > 70) & (r <= 80)] = 0
    rsi_pts[r < 20] = 10           # 超賣反彈機會
    rsi_pts[(r < 30) & (r >= 20)] = 0
    f["動能"] = m + rsi_pts

    # 3. KD：黃金/死亡交叉 + 高低檔
    k, d = df["K"], df["D"]
    cross_up = (k > d) & (k.shift() <= d.shift())
    cross_dn = (k < d) & (k.shift() >= d.shift())
    kd_pts = np.sign(k - d) * 5
    kd_pts = kd_pts + cross_up * np.where(k < 30, 10, 5) - cross_dn * np.where(k > 70, 10, 5)
    kd_pts = kd_pts.clip(-15, 15)
    f["KD"] = kd_pts

    # 4. 布林通道位置 %B：跌破下軌偏多（均值回歸），突破上軌偏空
    pb = (c - df["BB_LOW"]) / (df["BB_UP"] - df["BB_LOW"]).replace(0, np.nan)
    f["布林"] = (-(pb - 0.5) * 20).clip(-10, 10)

    # 5. 量能：OBV 趨勢 + 帶量上漲/下跌
    obv_trend = np.sign(df["OBV"] - df["OBV"].rolling(20).mean()) * 5
    vol_ratio = df["Volume"] / df["VOL_MA20"]
    surge = np.where(vol_ratio > 1.5, np.sign(df["RET"]) * 5, 0)
    f["量能"] = obv_trend + surge

    # 6. 相對強弱：60 日報酬 vs 大盤
    if bench is not None and len(bench.dropna()) > 60:
        b = bench.reindex(df.index).ffill()
        rel = c.pct_change(60) - b.pct_change(60)
        f["相對強弱"] = (rel * 100).clip(-10, 10)
    else:
        mom = c.pct_change(60)
        f["相對強弱"] = (mom * 50).clip(-10, 10)

    return f.fillna(0)


def composite(df: pd.DataFrame, bench: pd.Series | None = None) -> pd.Series:
    return factor_scores(df, bench).sum(axis=1).clip(-100, 100)


@dataclass
class Analysis:
    symbol: str
    date: pd.Timestamp
    price: float
    change_pct: float
    score: float
    rating: str
    icon: str
    factors: dict
    reasons: list[str] = field(default_factory=list)
    plan: dict = field(default_factory=dict)
    risk: dict = field(default_factory=dict)
    backtest: dict = field(default_factory=dict)
    levels: dict = field(default_factory=dict)
    source: str = ""
    fundamentals: dict = field(default_factory=dict)


def explain(df: pd.DataFrame) -> list[str]:
    """把最新一天的指標狀態翻成文字。"""
    last, prev = df.iloc[-1], df.iloc[-2]
    c = last["Close"]
    out = []
    if c > last["MA20"] > last["MA60"]:
        out.append("✅ 多頭排列：股價 > 月線 > 季線")
    elif c < last["MA20"] < last["MA60"]:
        out.append("❌ 空頭排列：股價 < 月線 < 季線")
    else:
        out.append("➖ 均線糾結，趨勢不明")
    if prev["MA5"] <= prev["MA20"] and last["MA5"] > last["MA20"]:
        out.append("✅ 5 日線黃金交叉月線")
    if prev["MA5"] >= prev["MA20"] and last["MA5"] < last["MA20"]:
        out.append("❌ 5 日線死亡交叉月線")
    if prev["DIF"] <= prev["DEA"] and last["DIF"] > last["DEA"]:
        out.append("✅ MACD 黃金交叉")
    elif prev["DIF"] >= prev["DEA"] and last["DIF"] < last["DEA"]:
        out.append("❌ MACD 死亡交叉")
    else:
        out.append(f"{'✅' if last['MACD_H'] > 0 else '❌'} MACD 柱狀體"
                   f"{'為正' if last['MACD_H'] > 0 else '為負'}且"
                   f"{'擴大' if abs(last['MACD_H']) > abs(prev['MACD_H']) else '收斂'}")
    r = last["RSI"]
    if r > 80:
        out.append(f"⚠️ RSI {r:.0f} 嚴重過熱，留意回檔")
    elif r > 70:
        out.append(f"⚠️ RSI {r:.0f} 偏熱")
    elif r < 20:
        out.append(f"💡 RSI {r:.0f} 嚴重超賣，可能醞釀反彈")
    elif r < 30:
        out.append(f"💡 RSI {r:.0f} 超賣區")
    else:
        out.append(f"➖ RSI {r:.0f} 中性")
    if prev["K"] <= prev["D"] and last["K"] > last["D"]:
        out.append(f"✅ KD 黃金交叉（K={last['K']:.0f}{'，低檔' if last['K'] < 30 else ''}）")
    elif prev["K"] >= prev["D"] and last["K"] < last["D"]:
        out.append(f"❌ KD 死亡交叉（K={last['K']:.0f}{'，高檔' if last['K'] > 70 else ''}）")
    if c > last["BB_UP"]:
        out.append("⚠️ 突破布林上軌（強勢但易過熱）")
    elif c < last["BB_LOW"]:
        out.append("💡 跌破布林下軌（超跌）")
    vr = last["Volume"] / last["VOL_MA20"] if last["VOL_MA20"] else 1
    if vr > 1.5:
        out.append(f"{'✅ 帶量上漲' if last['RET'] > 0 else '❌ 帶量下跌'}（量能 {vr:.1f} 倍均量）")
    elif vr < 0.5:
        out.append(f"➖ 量縮（{vr:.1f} 倍均量）")
    return out


def trade_plan(df: pd.DataFrame, capital: float, risk_pct: float = 0.01,
               lot: int = 1, score: float | None = None) -> dict:
    """以 ATR 設定停損/目標價，並依可承受風險計算部位大小。"""
    last = df.iloc[-1]
    c, a = float(last["Close"]), float(last["ATR"])
    stop = c - 2 * a
    target1, target2 = c + 2 * a, c + 4 * a
    risk_per_share = c - stop
    shares = int(capital * risk_pct / risk_per_share) if risk_per_share > 0 else 0
    shares = max(0, min(shares, int(capital // c)))
    shares -= shares % lot
    status = "可依計畫進場"
    if score is not None and score < 20:
        shares, status = 0, "評分未達進場門檻（≥20），暫不建倉"
    return {"狀態": status, "進場參考": c, "停損": stop, "目標一": target1, "目標二": target2,
            "停損幅度%": -risk_per_share / c * 100, "建議股數": shares,
            "部位金額": shares * c, "最大虧損": shares * risk_per_share}


def key_levels(df: pd.DataFrame) -> dict:
    tail20, tail60 = df.tail(20), df.tail(60)
    return {"20日支撐": float(tail20["Low"].min()), "20日壓力": float(tail20["High"].max()),
            "60日支撐": float(tail60["Low"].min()), "60日壓力": float(tail60["High"].max()),
            "月線": float(df["MA20"].iloc[-1]), "季線": float(df["MA60"].iloc[-1])}
