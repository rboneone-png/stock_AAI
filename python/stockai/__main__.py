"""命令列介面。

  python -m stockai analyze 2330 AAPL          # 個股深入分析
  python -m stockai scan -w watchlist.txt       # 觀察清單排行 + HTML 報告
  python -m stockai watch -w watchlist.txt -i 30  # 每 30 分鐘主動監控並警示
  python -m stockai sources                     # 測試各資料來源是否可用
  --source auto（預設，自動備援）/ yahoo / twse / tpex / finmind / nasdaq / alphavantage / yfinance
  加上 --demo 可使用離線模擬資料
"""
from __future__ import annotations

import argparse
import logging
import sys
from datetime import datetime
from pathlib import Path

from . import engine, monitor, report


def read_watchlist(path: str) -> list[str]:
    lines = Path(path).read_text("utf-8").splitlines()
    return [l.split("#")[0].strip() for l in lines if l.split("#")[0].strip()]


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(prog="stockai", description="股票投資主動分析程式")
    sub = p.add_subparsers(dest="cmd", required=True)

    def common(sp):
        sp.add_argument("symbols", nargs="*", help="股票代號，如 2330、2330.TW、AAPL")
        sp.add_argument("-w", "--watchlist", help="觀察清單檔案")
        sp.add_argument("--period", default="2y", help="歷史區間（1y/2y/5y…）")
        sp.add_argument("--capital", type=float, default=1_000_000, help="可投入資金")
        sp.add_argument("--risk", type=float, default=0.01, help="單筆可承受虧損比例")
        sp.add_argument("--source", default="auto",
                        help="資料來源：auto 或 yahoo,twse,tpex,finmind,nasdaq,alphavantage,yfinance（可逗號指定順序）")
        sp.add_argument("--demo", action="store_true", help="使用離線模擬資料")
        sp.add_argument("--csv", help="改用本機 CSV（僅限單檔）")
        sp.add_argument("-v", "--verbose", action="store_true")

    for name, h in (("analyze", "個股詳細分析"), ("scan", "批次掃描並排行")):
        sp = sub.add_parser(name, help=h)
        common(sp)
        sp.add_argument("--html", nargs="?", const="auto", help="輸出 HTML 報告路徑")
    sw = sub.add_parser("watch", help="主動監控與警示")
    common(sw)
    sw.add_argument("-i", "--interval", type=float, default=30, help="掃描間隔（分鐘）")
    sw.add_argument("--webhook", help="Discord/Slack Webhook URL")
    sw.add_argument("--once", action="store_true", help="只執行一次（適合排程）")
    sw.add_argument("--state", default="reports/watch_state.json")
    sc = sub.add_parser("sources", help="測試各資料來源是否可用")
    sc.add_argument("symbols", nargs="*", default=["2330", "6488", "AAPL"])
    sc.add_argument("-v", "--verbose", action="store_true")

    a = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO if a.verbose else logging.WARNING,
                        format="%(levelname)s %(message)s")
    if a.cmd == "sources":
        from . import sources
        rows = sources.check_all(a.symbols or ["2330", "6488", "AAPL"])
        print(report.table([list(r) for r in rows], ["代號", "來源", "結果"]))
        print("\n提示：FinMind 可設 FINMIND_TOKEN、Alpha Vantage 需設 ALPHAVANTAGE_API_KEY 環境變數")
        return 0
    symbols = list(a.symbols)
    if a.watchlist:
        symbols += read_watchlist(a.watchlist)
    if not symbols:
        p.error("請提供股票代號或 --watchlist")
    source = "demo" if a.demo else f"csv:{a.csv}" if a.csv else a.source
    kw = dict(period=a.period, source=source, capital=a.capital, risk_pct=a.risk)

    if a.cmd == "watch":
        monitor.run(symbols, a.interval, Path(a.state), a.webhook, a.once, **kw)
        return 0

    results, frames, errors = engine.scan(symbols, **kw)
    if a.cmd == "analyze":
        for r in results:
            print(report.text_detail(r), "\n")
    print(report.text_summary(results))
    for e in errors:
        print(f"⚠️ {e}")
    if a.html or a.cmd == "scan":
        path = Path(a.html if a.html and a.html != "auto"
                    else f"reports/report_{datetime.now():%Y%m%d_%H%M}.html")
        report.html_report(results, frames, path, errors)
        print(f"\n📄 HTML 報告：{path.resolve()}")
    print(f"\n⚠️ {report.DISCLAIMER}")
    return 0 if results else 1


if __name__ == "__main__":
    sys.exit(main())
