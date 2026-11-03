"""估值/基本面資料（僅供參考，不計入技術評分）。

台股：證交所 / 櫃買中心 OpenAPI（本益比、殖利率、股價淨值比），每日快取。
美股：Nasdaq.com summary（產業、市值、殖利率、分析師一年目標價、52 週高低）。
"""
from __future__ import annotations

import json
import logging
import time

from .sources import CACHE_DIR, SourceError, _num, get_json, market_of, tw_code

log = logging.getLogger(__name__)


def _daily_cached(name: str, fetch):
    f = CACHE_DIR / "fund" / f"{name}.json"
    if f.exists() and time.time() - f.stat().st_mtime < 6 * 3600:
        return json.loads(f.read_text("utf-8"))
    data = fetch()
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(data, ensure_ascii=False), "utf-8")
    return data


def _tw_table() -> dict:
    def fetch():
        out = {}
        for r in get_json("https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL", timeout=30):
            out[r["Code"]] = {"名稱": r["Name"], "本益比": _num(r["PEratio"]),
                              "殖利率%": _num(r["DividendYield"]), "股價淨值比": _num(r["PBratio"]),
                              "資料日": r["Date"], "市場": "上市"}
        for r in get_json("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_peratio_analysis", timeout=30):
            out[r["SecuritiesCompanyCode"]] = {
                "名稱": r["CompanyName"], "本益比": _num(r["PriceEarningRatio"]),
                "殖利率%": _num(r["YieldRatio"]), "股價淨值比": _num(r["PriceBookRatio"]),
                "每股股利": _num(r["DividendPerShare"]), "資料日": r["Date"], "市場": "上櫃"}
        return out
    return _daily_cached("tw_valuation", fetch)


def _us(symbol: str) -> dict:
    def fetch():
        for ac in ("stocks", "etf"):
            j = get_json(f"https://api.nasdaq.com/api/quote/{symbol}/summary", {"assetclass": ac})
            sd = (j.get("data") or {}).get("summaryData")
            if sd:
                g = lambda k: (sd.get(k) or {}).get("value")
                hl = (g("FiftTwoWeekHighLow") or "/").split("/")
                return {"產業": g("Sector") or g("Category"), "細產業": g("Industry"),
                        "市值(億美元)": _num(g("MarketCap")) / 1e8, "殖利率%": _num(g("Yield")),
                        "本益比": _num(g("PERatio")), "一年目標價": _num(g("OneYrTarget")),
                        "52週高": _num(hl[0]), "52週低": _num(hl[-1])}
        raise SourceError("Nasdaq summary 無資料")
    return _daily_cached(f"us_{symbol}", fetch)


def get(symbol: str, price: float | None = None) -> dict:
    try:
        if market_of(symbol) == "tw":
            d = dict(_tw_table().get(tw_code(symbol)) or {})
        else:
            d = _us(symbol.upper())
    except Exception as e:
        log.info("基本面 %s 取得失敗：%s", symbol, e)
        return {}
    d = {k: v for k, v in d.items() if v is not None and v == v}  # 去掉 NaN
    if price and d.get("一年目標價"):
        d["目標價空間%"] = (d["一年目標價"] / price - 1) * 100
    return d


def notes(f: dict) -> list[str]:
    """簡單估值解讀。"""
    out = []
    pe = f.get("本益比")
    if pe:
        out.append("💡 本益比偏低（<12）" if pe < 12 else "⚠️ 本益比偏高（>35）" if pe > 35 else f"➖ 本益比 {pe:.1f} 合理區間")
    y = f.get("殖利率%")
    if y and y >= 5:
        out.append(f"💰 高殖利率 {y:.2f}%")
    pb = f.get("股價淨值比")
    if pb and pb < 1:
        out.append(f"💡 股價低於淨值（PB {pb:.2f}）")
    up = f.get("目標價空間%")
    if up is not None:
        out.append(f"{'✅' if up > 10 else '⚠️' if up < 0 else '➖'} 分析師目標價空間 {up:+.1f}%")
    return out
