"""風險指標與策略回測。"""
from __future__ import annotations

import numpy as np
import pandas as pd

TRADING_DAYS = 252


def max_drawdown(equity: pd.Series) -> float:
    peak = equity.cummax()
    return float((equity / peak - 1).min())


def risk_metrics(df: pd.DataFrame, bench: pd.Series | None = None,
                 rf: float = 0.015) -> dict:
    r = df["Close"].pct_change().dropna().tail(TRADING_DAYS)
    vol = r.std() * np.sqrt(TRADING_DAYS)
    ann = (1 + r).prod() ** (TRADING_DAYS / max(len(r), 1)) - 1
    out = {
        "一年報酬%": ann * 100,
        "年化波動%": vol * 100,
        "夏普值": (ann - rf) / vol if vol else np.nan,
        "最大回撤%": max_drawdown((1 + r).cumprod()) * 100,
        "單日VaR95%": float(np.percentile(r, 5)) * 100 if len(r) else np.nan,
    }
    if bench is not None:
        br = bench.pct_change().reindex(r.index).dropna()
        j = pd.concat([r, br], axis=1, join="inner").dropna()
        if len(j) > 30 and j.iloc[:, 1].var() > 0:
            out["Beta"] = float(j.cov().iloc[0, 1] / j.iloc[:, 1].var())
            out["相關係數"] = float(j.corr().iloc[0, 1])
    return out


def backtest(df: pd.DataFrame, score: pd.Series, entry: float = 20,
             exit_: float = -10, cost: float = 0.004, atr_stop: float = 2.5) -> dict:
    """訊號回測：分數 >= entry 進場、<= exit_ 或觸發 ATR 移動停損出場。

    在 t 日收盤判斷，t+1 日起承擔報酬（避免未來函數）。cost 為一次來回交易成本。
    """
    close = df["Close"].to_numpy()
    atr = df["ATR"].to_numpy()
    sc = score.to_numpy()
    n = len(close)
    pos = np.zeros(n)
    holding, trail, entry_px = False, 0.0, 0.0
    trades = []
    for i in range(n):
        if np.isnan(atr[i]):
            continue
        if holding:
            trail = max(trail, close[i] - atr_stop * atr[i])
            if sc[i] <= exit_ or close[i] < trail:
                holding = False
                trades.append(close[i] / entry_px - 1 - cost)
        elif sc[i] >= entry:
            holding, entry_px = True, close[i]
            trail = close[i] - atr_stop * atr[i]
        pos[i] = 1.0 if holding else 0.0
    if holding:
        trades.append(close[-1] / entry_px - 1 - cost)

    ret = df["Close"].pct_change().fillna(0).to_numpy()
    held = np.concatenate([[0], pos[:-1]])
    switches = np.abs(np.diff(np.concatenate([[0], held])))
    strat = held * ret - switches * cost / 2
    eq = pd.Series(np.cumprod(1 + strat), index=df.index)
    bh = pd.Series(np.cumprod(1 + ret), index=df.index)
    years = max(n / TRADING_DAYS, 1e-9)
    sr = pd.Series(strat)
    return {
        "策略總報酬%": (eq.iloc[-1] - 1) * 100,
        "買進持有%": (bh.iloc[-1] - 1) * 100,
        "策略年化%": (eq.iloc[-1] ** (1 / years) - 1) * 100,
        "策略最大回撤%": max_drawdown(eq) * 100,
        "買進持有回撤%": max_drawdown(bh) * 100,
        "策略夏普": sr.mean() / sr.std() * np.sqrt(TRADING_DAYS) if sr.std() else np.nan,
        "交易次數": len(trades),
        "勝率%": (np.mean([t > 0 for t in trades]) * 100) if trades else np.nan,
        "持倉比例%": held.mean() * 100,
        "_equity": eq, "_bh": bh,
    }
