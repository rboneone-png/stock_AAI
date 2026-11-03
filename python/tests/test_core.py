import unittest
import numpy as np
import pandas as pd
from stockai import data, indicators, signals, risk, engine, report, monitor


class TestCore(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = data.synthetic("T", 400, seed=1)
        cls.d = indicators.add_all(cls.raw)

    def test_symbol(self):
        self.assertEqual(data.normalize_symbol(" 2330 "), "2330")
        self.assertEqual(data.normalize_symbol("aapl"), "AAPL")
        self.assertEqual(data.normalize_symbol("6488.TWO"), "6488.TWO")

    def test_rsi_bounds_and_trend(self):
        r = self.d["RSI"].dropna()
        self.assertTrue(((r >= 0) & (r <= 100)).all())
        up = pd.Series(np.arange(1, 60, dtype=float))
        self.assertGreater(indicators.rsi(up).iloc[-1], 99)

    def test_kd_bounds(self):
        k = self.d["K"].dropna()
        self.assertTrue(((k >= 0) & (k <= 100)).all())

    def test_score_range(self):
        s = signals.composite(self.d)
        self.assertTrue(((s >= -100) & (s <= 100)).all())

    def test_ratings(self):
        self.assertEqual(signals.rating_of(60)[0], "強力買進")
        self.assertEqual(signals.rating_of(0)[0], "觀望")
        self.assertEqual(signals.rating_of(-100)[0], "強力賣出")

    def test_backtest_no_lookahead(self):
        # 只在最後一天給出買進訊號 → 不應產生任何報酬
        s = pd.Series(-50.0, index=self.d.index); s.iloc[-1] = 90
        bt = risk.backtest(self.d, s, cost=0)
        self.assertAlmostEqual(bt["策略總報酬%"], 0.0, places=9)

    def test_drawdown(self):
        self.assertAlmostEqual(risk.max_drawdown(pd.Series([1, 2, 1, 3])), -0.5)

    def test_trade_plan(self):
        p = signals.trade_plan(self.d, 1_000_000, 0.01, lot=1000, score=50)
        self.assertLess(p["停損"], p["進場參考"])
        self.assertEqual(p["建議股數"] % 1000, 0)
        self.assertLessEqual(p["最大虧損"], 10_000 + 1e-6)
        self.assertEqual(signals.trade_plan(self.d, 1e6, score=0)["建議股數"], 0)

    def test_engine_and_reports(self):
        import tempfile, pathlib
        res, frames, err = engine.scan(["A", "B"], source="demo")
        self.assertEqual(len(res), 2); self.assertFalse(err)
        self.assertIn("綜合評分", report.text_detail(res[0]))
        with tempfile.TemporaryDirectory() as t:
            p = report.html_report(res, frames, pathlib.Path(t) / "r.html")
            self.assertIn("Plotly.newPlot", p.read_text("utf-8"))
        ev = monitor.detect_events(res[0], {"rating": "X", "score": 0, "stop": 1e9}, frames[res[0].symbol])
        self.assertTrue(any("評等變化" in e for e in ev))
        self.assertTrue(any("停損" in e for e in ev))


if __name__ == "__main__":
    unittest.main()


class TestSources(unittest.TestCase):
    def test_fallback_order(self):
        from stockai import sources as s
        calls = []

        def bad(sym, days):
            calls.append("bad"); raise s.SourceError("被擋")

        def good(sym, days):
            calls.append("good"); return data.synthetic(sym, 300, seed=2), sym

        orig = dict(s.SOURCES)
        try:
            s.SOURCES.update(yahoo=bad, twse=good)
            df, sym, used = s.fetch("9999", "2y", "yahoo,twse", cache_ttl=0)
            self.assertEqual(used, "twse"); self.assertEqual(calls, ["bad", "good"])
            # 美股不會去試台股專用來源
            s.SOURCES.update(nasdaq=good)
            _, _, used = s.fetch("ZZZ", "2y", "twse,nasdaq", cache_ttl=0)
            self.assertEqual(used, "nasdaq")
            with self.assertRaises(s.SourceError):
                s.fetch("ZZZ", "2y", "yahoo", cache_ttl=0)
        finally:
            s.SOURCES.clear(); s.SOURCES.update(orig)

    def test_parsers(self):
        from stockai import sources as s
        self.assertEqual(s._roc_date("115/10/01"), pd.Timestamp(2026, 10, 1))
        self.assertEqual(s._num("1,234.50"), 1234.5)
        self.assertEqual(s._num("$0.31%"), 0.31)
        self.assertTrue(np.isnan(s._num("--")))
        self.assertEqual(s.market_of("6488"), "tw"); self.assertEqual(s.market_of("AAPL"), "us")


class TestSplit(unittest.TestCase):
    def test_adjust_split(self):
        from stockai import sources as s
        idx = pd.bdate_range("2025-01-01", periods=10)
        c = [100, 101, 102, 103, 104, 26, 26.5, 27, 26.8, 27.2]
        df = pd.DataFrame({"Open": c, "High": c, "Low": c, "Close": c, "Volume": [10] * 10}, index=idx)
        out, notes = s.adjust_splits(df, "tw")
        self.assertEqual(len(notes), 1)
        self.assertAlmostEqual(out["Close"].iloc[0], 25.0)
        self.assertEqual(out["Volume"].iloc[0], 40)
        self.assertAlmostEqual(out["Close"].iloc[-1], 27.2)
        # 台股 10% 以內的正常漲跌不應被當成分割
        df2 = df.copy(); df2[["Open", "High", "Low", "Close"]] = 100.0; df2.iloc[5:, :4] = 91.0
        self.assertEqual(len(s.adjust_splits(df2, "tw")[1]), 0)
