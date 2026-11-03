"""輸出：終端機文字報告 + 互動式 HTML 報告（Plotly via CDN）。"""
from __future__ import annotations

import html
import json
import math
import unicodedata
from datetime import datetime
from pathlib import Path

import pandas as pd

from .signals import WEIGHTS, Analysis

DISCLAIMER = "本程式僅供研究與教育用途，不構成任何投資建議。投資有風險，請自行判斷並承擔損益。"


# ---------------------------------------------------------------- 終端機
def _w(s: str) -> int:
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in str(s))


def _pad(s, width: int, right: bool = False) -> str:
    s = str(s)
    fill = " " * max(0, width - _w(s))
    return fill + s if right else s + fill


def _fmt(v, nd=2) -> str:
    if isinstance(v, str):
        return v
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return "—"
    if isinstance(v, (int,)) and not isinstance(v, bool):
        return f"{v:,}"
    return f"{v:,.{nd}f}"


def table(rows: list[list], headers: list[str], right: set[int] = frozenset()) -> str:
    cols = list(zip(*([headers] + rows))) if rows else [[h] for h in headers]
    widths = [max(_w(x) for x in col) for col in cols]
    line = "─┼─".join("─" * w for w in widths)
    out = [" │ ".join(_pad(h, widths[i], i in right) for i, h in enumerate(headers)), line]
    for r in rows:
        out.append(" │ ".join(_pad(c, widths[i], i in right) for i, c in enumerate(r)))
    return "\n".join(out)


def bar(v: float, cap: float, width: int = 10) -> str:
    n = round(abs(v) / cap * width) if cap else 0
    return ("+" if v >= 0 else "-") * n


def text_detail(a: Analysis) -> str:
    L = []
    L.append("=" * 64)
    name = a.fundamentals.get("名稱", "")
    L.append(f" {a.symbol} {name}  {a.date:%Y-%m-%d}  收盤 {a.price:,.2f} ({a.change_pct:+.2f}%)  [資料來源：{a.source}]")
    L.append(f" 綜合評分 {a.score:+.0f} / 100   →   {a.icon} {a.rating}")
    L.append("=" * 64)
    L.append("【因子分數】")
    for k, v in a.factors.items():
        L.append(f"  {_pad(k, 8)} {v:+6.1f} / ±{WEIGHTS[k]:<3} {bar(v, WEIGHTS[k])}")
    L.append("【訊號解讀】")
    L += [f"  {r}" for r in a.reasons]
    L.append("【關鍵價位】")
    L.append("  " + "  ".join(f"{k} {v:,.2f}" for k, v in a.levels.items()))
    p = a.plan
    L.append("【交易計畫（ATR 停損法）】")
    L.append(f"  {p['狀態']}")
    L.append(f"  進場 {p['進場參考']:,.2f}  停損 {p['停損']:,.2f} ({p['停損幅度%']:.1f}%)  "
             f"目標 {p['目標一']:,.2f} / {p['目標二']:,.2f}")
    L.append(f"  建議股數 {p['建議股數']:,}  部位 {p['部位金額']:,.0f}  最大虧損 {p['最大虧損']:,.0f}")
    if a.fundamentals:
        L.append("【估值 / 基本面】")
        L.append("  " + "  ".join(f"{k} {_fmt(v)}" for k, v in a.fundamentals.items() if k != "名稱"))
    L.append("【風險指標（近一年）】")
    L.append("  " + "  ".join(f"{k} {_fmt(v)}" for k, v in a.risk.items()))
    L.append("【策略回測（評分 ≥20 進場，≤-10 或移動停損出場，含交易成本）】")
    L.append("  " + "  ".join(f"{k} {_fmt(v, 1)}" for k, v in a.backtest.items()))
    return "\n".join(L)


def text_summary(results: list[Analysis]) -> str:
    rows = [[i + 1, a.symbol, a.fundamentals.get("名稱", ""), a.source, f"{a.price:,.2f}", f"{a.change_pct:+.2f}%",
             f"{a.score:+.0f}", f"{a.icon} {a.rating}", f"{a.risk.get('年化波動%', float('nan')):.1f}%",
             f"{a.backtest['策略總報酬%']:+.1f}%", f"{a.backtest['買進持有%']:+.1f}%"]
            for i, a in enumerate(results)]
    return table(rows, ["#", "代號", "名稱", "來源", "收盤", "漲跌", "評分", "建議", "波動", "策略回測", "買進持有"],
                 right={0, 4, 5, 6, 8, 9, 10})


# ---------------------------------------------------------------- HTML
def _series(s: pd.Series) -> list:
    return [None if (isinstance(v, float) and math.isnan(v)) else round(float(v), 4) for v in s]


def _chart_spec(d: pd.DataFrame) -> dict:
    d = d.tail(250)
    x = [t.strftime("%Y-%m-%d") for t in d.index]
    up = (d["Close"] >= d["Open"]).tolist()
    tr = [
        {"type": "candlestick", "x": x, "open": _series(d["Open"]), "high": _series(d["High"]),
         "low": _series(d["Low"]), "close": _series(d["Close"]), "name": "K線",
         "increasing": {"line": {"color": "#e5484d"}}, "decreasing": {"line": {"color": "#30a46c"}},
         "yaxis": "y"},
    ]
    for col, color in (("MA5", "#f5a524"), ("MA20", "#3e63dd"), ("MA60", "#8e4ec6")):
        tr.append({"type": "scatter", "mode": "lines", "x": x, "y": _series(d[col]), "name": col,
                   "line": {"width": 1.2, "color": color}, "yaxis": "y"})
    for col in ("BB_UP", "BB_LOW"):
        tr.append({"type": "scatter", "mode": "lines", "x": x, "y": _series(d[col]),
                   "name": "布林" if col == "BB_UP" else "", "showlegend": col == "BB_UP",
                   "legendgroup": "bb", "line": {"width": 0.8, "dash": "dot", "color": "#8b8d98"},
                   "yaxis": "y"})
    tr.append({"type": "bar", "x": x, "y": _series(d["Volume"]), "name": "成交量", "yaxis": "y2",
               "marker": {"color": ["#e5484d" if u else "#30a46c" for u in up]}, "opacity": 0.6})
    tr.append({"type": "bar", "x": x, "y": _series(d["MACD_H"]), "name": "MACD柱", "yaxis": "y3",
               "marker": {"color": ["#e5484d" if (v or 0) >= 0 else "#30a46c" for v in _series(d["MACD_H"])]}})
    tr.append({"type": "scatter", "mode": "lines", "x": x, "y": _series(d["DIF"]), "name": "DIF",
               "line": {"width": 1, "color": "#f5a524"}, "yaxis": "y3"})
    tr.append({"type": "scatter", "mode": "lines", "x": x, "y": _series(d["DEA"]), "name": "DEA",
               "line": {"width": 1, "color": "#3e63dd"}, "yaxis": "y3"})
    tr.append({"type": "scatter", "mode": "lines", "x": x, "y": _series(d["RSI"]), "name": "RSI",
               "line": {"width": 1.2, "color": "#8e4ec6"}, "yaxis": "y4"})
    tr.append({"type": "scatter", "mode": "lines", "x": x, "y": _series(d["K"]), "name": "K",
               "line": {"width": 1, "color": "#f5a524"}, "yaxis": "y4"})
    tr.append({"type": "scatter", "mode": "lines", "x": x, "y": _series(d["D"]), "name": "D",
               "line": {"width": 1, "color": "#3e63dd"}, "yaxis": "y4"})
    tr.append({"type": "scatter", "mode": "lines", "x": x, "y": _series(d["SCORE"]), "name": "綜合評分",
               "fill": "tozeroy", "line": {"width": 1.2, "color": "#12a594"}, "yaxis": "y5"})
    base = d["Close"].iloc[0]
    tr.append({"type": "scatter", "mode": "lines", "x": x,
               "y": _series(d["EQUITY"] / d["EQUITY"].iloc[0] * base), "name": "策略淨值(換算)",
               "line": {"width": 1.2, "color": "#e54666", "dash": "dash"}, "yaxis": "y", "visible": "legendonly"})
    layout = {
        "height": 860, "margin": {"l": 50, "r": 20, "t": 10, "b": 30},
        "xaxis": {"rangeslider": {"visible": False}, "type": "category", "nticks": 10, "anchor": "y5"},
        "yaxis": {"domain": [0.52, 1], "title": "價格"},
        "yaxis2": {"domain": [0.41, 0.50], "title": "量"},
        "yaxis3": {"domain": [0.28, 0.39], "title": "MACD"},
        "yaxis4": {"domain": [0.14, 0.26], "title": "RSI/KD", "range": [0, 100]},
        "yaxis5": {"domain": [0, 0.12], "title": "評分", "range": [-100, 100]},
        "legend": {"orientation": "h", "y": 1.04},
        "shapes": [
            {"type": "line", "xref": "paper", "x0": 0, "x1": 1, "yref": "y4", "y0": v, "y1": v,
             "line": {"dash": "dot", "width": 0.8, "color": "#8b8d98"}} for v in (20, 80)] + [
            {"type": "line", "xref": "paper", "x0": 0, "x1": 1, "yref": "y5", "y0": v, "y1": v,
             "line": {"dash": "dot", "width": 0.8, "color": c}} for v, c in ((20, "#e5484d"), (-10, "#30a46c"))],
        "hovermode": "x unified",
    }
    return {"data": tr, "layout": layout}


def _kv(d: dict, nd=2) -> str:
    return "".join(f"<div class='kv'><span>{html.escape(k)}</span><b>{_fmt(v, nd)}</b></div>" for k, v in d.items())


def _rating_cls(score: float) -> str:
    return "pos" if score >= 20 else "neg" if score <= -20 else "neu"


def html_report(results: list[Analysis], frames: dict, path: Path, errors: list[str] = ()) -> Path:
    rows = "".join(
        f"<tr><td>{i+1}</td><td><a href='#{html.escape(a.symbol)}'>{html.escape(a.symbol)}</a> "
        f"<span class='muted'>{html.escape(str(a.fundamentals.get('名稱', '')))}</span></td>"
        f"<td class='muted'>{html.escape(a.source)}</td>"
        f"<td class='num'>{a.price:,.2f}</td>"
        f"<td class='num {'up' if a.change_pct >= 0 else 'dn'}'>{a.change_pct:+.2f}%</td>"
        f"<td class='num'><span class='pill {_rating_cls(a.score)}'>{a.score:+.0f}</span></td>"
        f"<td>{a.icon} {a.rating}</td>"
        f"<td class='num'>{_fmt(a.risk.get('年化波動%'), 1)}%</td>"
        f"<td class='num'>{a.backtest['策略總報酬%']:+.1f}%</td>"
        f"<td class='num'>{a.backtest['買進持有%']:+.1f}%</td></tr>"
        for i, a in enumerate(results))
    sections, specs = [], {}
    for a in results:
        sid = html.escape(a.symbol)
        specs[a.symbol] = _chart_spec(frames[a.symbol])
        factors = "".join(
            f"<div class='fac'><span>{k}</span><div class='track'><div class='fill {'p' if v >= 0 else 'n'}' "
            f"style='width:{abs(v)/WEIGHTS[k]*50:.1f}%;{'left' if v >= 0 else 'right'}:50%'></div></div>"
            f"<b>{v:+.1f}</b></div>" for k, v in a.factors.items())
        sections.append(f"""
<section id="{sid}">
  <header><h2>{sid} {html.escape(str(a.fundamentals.get('名稱', '')))}</h2><span class="muted">資料來源 {html.escape(a.source)} · {a.date:%Y-%m-%d} 收盤 {a.price:,.2f}
  <span class="{'up' if a.change_pct >= 0 else 'dn'}">({a.change_pct:+.2f}%)</span></span>
  <span class="pill big {_rating_cls(a.score)}">{a.icon} {a.rating} {a.score:+.0f}</span></header>
  <div class="grid">
    <div class="card"><h3>因子分數</h3>{factors}</div>
    <div class="card"><h3>訊號解讀</h3><ul>{''.join(f'<li>{html.escape(r)}</li>' for r in a.reasons)}</ul></div>
    <div class="card"><h3>交易計畫</h3>{_kv(a.plan)}</div>
    <div class="card"><h3>關鍵價位</h3>{_kv(a.levels)}</div>
    <div class="card"><h3>風險指標（近一年）</h3>{_kv(a.risk)}</div>
    {f'<div class="card"><h3>估值 / 基本面</h3>{_kv({k: v for k, v in a.fundamentals.items() if k != "名稱"})}</div>' if a.fundamentals else ''}
    <div class="card"><h3>策略回測</h3>{_kv(a.backtest, 1)}</div>
  </div>
  <div class="chart" id="chart-{sid}"></div>
</section>""")
    err_html = ("<div class='card err'><h3>無法分析</h3><ul>"
                + "".join(f"<li>{html.escape(e)}</li>" for e in errors) + "</ul></div>") if errors else ""
    page = f"""<!doctype html><html lang="zh-Hant"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>股票主動分析報告</title>
<script src="https://cdn.jsdelivr.net/npm/plotly.js-dist-min@2.35.2/plotly.min.js"></script>
<style>
:root{{--bg:#fbfbfc;--card:#fff;--fg:#1c2024;--muted:#60646c;--line:#e0e1e6;--up:#e5484d;--dn:#30a46c;--neu:#8b8d98}}
@media (prefers-color-scheme:dark){{:root:not([data-theme=light]){{--bg:#111113;--card:#18191b;--fg:#edeef0;--muted:#b0b4ba;--line:#2e3135}}}}
:root[data-theme=dark]{{--bg:#111113;--card:#18191b;--fg:#edeef0;--muted:#b0b4ba;--line:#2e3135}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--fg);font:14px/1.6 system-ui,"PingFang TC","Noto Sans TC","Microsoft JhengHei",sans-serif}}
main{{max-width:1200px;margin:auto;padding:24px 16px}}h1{{margin:0 0 4px}}.muted{{color:var(--muted)}}
table{{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:8px;overflow:hidden}}
th,td{{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}}th{{color:var(--muted);font-weight:500}}
.num{{text-align:right;font-variant-numeric:tabular-nums}}.up{{color:var(--up)}}.dn{{color:var(--dn)}}
.tw{{overflow-x:auto}}a{{color:inherit}}
.pill{{display:inline-block;padding:1px 8px;border-radius:99px;font-weight:600}}.pill.big{{font-size:16px;padding:4px 12px}}
.pos{{background:color-mix(in srgb,var(--up) 15%,transparent);color:var(--up)}}.neg{{background:color-mix(in srgb,var(--dn) 15%,transparent);color:var(--dn)}}.neu{{background:color-mix(in srgb,var(--neu) 15%,transparent)}}
section{{margin-top:40px}}section header{{display:flex;gap:12px;align-items:center;flex-wrap:wrap}}section h2{{margin:0}}
.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:12px;margin:12px 0}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 14px}}.card h3{{margin:0 0 8px;font-size:14px;color:var(--muted)}}
.card ul{{margin:0;padding-left:4px;list-style:none}}.err{{border-color:var(--up);margin-top:12px}}
.kv{{display:flex;justify-content:space-between;border-bottom:1px dashed var(--line);padding:2px 0}}.kv b{{font-variant-numeric:tabular-nums}}
.fac{{display:grid;grid-template-columns:64px 1fr 48px;gap:8px;align-items:center;margin:4px 0}}.fac b{{text-align:right}}
.track{{position:relative;height:8px;background:var(--line);border-radius:4px}}.fill{{position:absolute;top:0;bottom:0;border-radius:4px}}.fill.p{{background:var(--up)}}.fill.n{{background:var(--dn)}}
.chart{{background:var(--card);border:1px solid var(--line);border-radius:8px}}footer{{margin:40px 0 0;color:var(--muted);font-size:12px}}
</style></head><body><main>
<h1>📈 股票主動分析報告</h1><div class="muted">產生時間 {datetime.now():%Y-%m-%d %H:%M} · 共 {len(results)} 檔 · 台股慣例：紅漲綠跌</div>
<h2>排行榜</h2><div class="tw"><table><thead><tr><th>#</th><th>代號</th><th>來源</th><th class="num">收盤</th><th class="num">漲跌</th>
<th class="num">評分</th><th>建議</th><th class="num">年化波動</th><th class="num">策略回測</th><th class="num">買進持有</th></tr></thead>
<tbody>{rows}</tbody></table></div>{err_html}
{''.join(sections)}
<footer>⚠️ {DISCLAIMER}</footer></main>
<script>
const SPECS={json.dumps(specs, ensure_ascii=False)};
const dark=()=>matchMedia('(prefers-color-scheme: dark)').matches;
for(const [sym,s] of Object.entries(SPECS)){{
  const el=document.getElementById('chart-'+sym); if(!el||!window.Plotly) continue;
  const fg=getComputedStyle(document.documentElement).getPropertyValue('--fg');
  Object.assign(s.layout,{{paper_bgcolor:'rgba(0,0,0,0)',plot_bgcolor:'rgba(0,0,0,0)',font:{{color:fg}}}});
  for(const k of Object.keys(s.layout)) if(k.startsWith('yaxis')||k==='xaxis') s.layout[k].gridcolor=dark()?'#2e3135':'#e0e1e6';
  Plotly.newPlot(el,s.data,s.layout,{{responsive:true,displaylogo:false}});
}}
</script></body></html>"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(page, encoding="utf-8")
    return path
