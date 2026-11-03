"""主動監控：定期重新分析觀察清單，偵測重要事件並發出警示。"""
from __future__ import annotations

import json
import logging
import time
import urllib.request
from datetime import datetime
from pathlib import Path

from . import engine
from .signals import Analysis

log = logging.getLogger(__name__)


def detect_events(a: Analysis, prev: dict | None, df) -> list[str]:
    ev = []
    if prev:
        if prev["rating"] != a.rating:
            ev.append(f"評等變化：{prev['rating']} → {a.icon} {a.rating}（{prev['score']:+.0f} → {a.score:+.0f}）")
        if prev.get("stop") and a.price < prev["stop"]:
            ev.append(f"⛔ 跌破前次停損價 {prev['stop']:,.2f}")
        if prev.get("target") and a.price > prev["target"]:
            ev.append(f"🎯 突破前次目標價 {prev['target']:,.2f}")
    last, p = df.iloc[-1], df.iloc[-2]
    if p["Close"] <= p["MA60"] and last["Close"] > last["MA60"]:
        ev.append("📈 站上季線")
    if p["Close"] >= p["MA60"] and last["Close"] < last["MA60"]:
        ev.append("📉 跌破季線")
    if last["Close"] >= df["High"].iloc[-61:-1].max():
        ev.append("🚀 創 60 日新高")
    if last["Close"] <= df["Low"].iloc[-61:-1].min():
        ev.append("🕳️ 創 60 日新低")
    if abs(a.change_pct) >= 5:
        ev.append(f"⚡ 單日大幅波動 {a.change_pct:+.2f}%")
    if last["VOL_MA20"] and last["Volume"] > 2.5 * last["VOL_MA20"]:
        ev.append(f"🔊 爆量 {last['Volume'] / last['VOL_MA20']:.1f} 倍")
    return ev


def notify(msg: str, webhook: str | None) -> None:
    print(msg, flush=True)
    if not webhook:
        return
    # 相容 Discord（content）與 Slack（text）webhook
    body = json.dumps({"content": msg[:1900], "text": msg}).encode()
    req = urllib.request.Request(webhook, data=body, headers={"Content-Type": "application/json"})
    try:
        urllib.request.urlopen(req, timeout=10).read()
    except Exception as e:
        log.warning("Webhook 傳送失敗：%s", e)


def run(symbols: list[str], interval_min: float, state_file: Path, webhook: str | None = None,
        once: bool = False, **kw) -> None:
    state = json.loads(state_file.read_text("utf-8")) if state_file.exists() else {}
    while True:
        stamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        print(f"\n[{stamp}] 掃描 {len(symbols)} 檔 ...", flush=True)
        engine._bench.cache_clear()
        results, frames, errors = engine.scan(symbols, **kw)
        alerts = []
        for a in results:
            ev = detect_events(a, state.get(a.symbol), frames[a.symbol])
            if ev:
                alerts.append(f"【{a.symbol}】{a.price:,.2f} ({a.change_pct:+.2f}%) 評分 {a.score:+.0f}\n  "
                              + "\n  ".join(ev))
            state[a.symbol] = {"rating": a.rating, "score": a.score, "price": a.price,
                               "stop": a.plan["停損"], "target": a.plan["目標一"], "time": stamp}
        state_file.parent.mkdir(parents=True, exist_ok=True)
        state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), "utf-8")
        if alerts:
            notify(f"🔔 股票警示 {stamp}\n" + "\n".join(alerts), webhook)
        else:
            print("  無新事件。", flush=True)
        for e in errors:
            print(f"  ⚠️ {e}", flush=True)
        if once:
            return
        time.sleep(interval_min * 60)
