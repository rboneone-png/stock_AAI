# 📈 股票投資主動分析程式（stockai）

支援台股（`2330`、`6488`，自動判斷上市/上櫃）與美股（`AAPL`），從**多個資料來源自動備援**抓取日線資料，
用多因子模型評分，輸出交易計畫、風險指標、策略回測，並能**定時主動監控、發出警示**。

## 安裝
```bash
pip install -r requirements.txt
```

## 使用方式
```bash
python -m stockai analyze 2330 AAPL           # 個股詳細分析（終端機）
python -m stockai analyze 2330 --html         # 另外產生 HTML 互動圖表報告
python -m stockai scan -w watchlist.txt       # 觀察清單排行 + HTML 報告（reports/）
python -m stockai watch -w watchlist.txt -i 30                 # 每 30 分鐘主動監控
python -m stockai watch -w watchlist.txt --once --webhook URL  # 搭配排程 + Discord/Slack 推播
python -m stockai scan A B C --demo           # 離線模擬資料示範
```
常用參數：`--period 5y`、`--capital 500000`（資金）、`--risk 0.02`（單筆可承受虧損 2%）、`--csv 檔案.csv`。

## 資料來源與自動備援
`--source auto`（預設）會依序嘗試，某來源被擋、無資料或資料不足就自動換下一個：

| 市場 | 順序 | 說明 |
|---|---|---|
| 台股 | yahoo → **twse** → **tpex** → finmind → yfinance |  證交所 / 櫃買中心為官方免金鑰來源 |
| 美股 | yahoo → **nasdaq** → finmind → alphavantage → yfinance | Nasdaq.com 免金鑰 |

| 來源 | 需金鑰 | 備註 |
|---|---|---|
| `yahoo` | 否 | 直接呼叫 chart API（不經 yfinance），已還原權息 |
| `twse` | 否 | 證交所官方，逐月抓取；已結束月份永久快取，限速約每 3.5 秒一次，遇 307 自動退避重試以免被封 IP |
| `tpex` | 否 | 櫃買中心官方（上櫃股） |
| `finmind` | 選用 | 未登入有共用額度限制，設 `FINMIND_TOKEN` 可提高；資料約晚一天 |
| `nasdaq` | 否 | 美股/ETF；S&P 500 指數以 SPY 代替 |
| `alphavantage` | 是 | 設 `ALPHAVANTAGE_API_KEY`，免費版每分鐘 5 次 |
| `yfinance` | 否 | 原套件，作為最後備援 |

```bash
python -m stockai sources 2330 6488 AAPL                # 檢查每個來源是否可用
python -m stockai analyze 2330 --source twse,finmind    # 指定來源與順序
STOCKAI_DISABLE=yahoo,yfinance python -m stockai scan -w watchlist.txt   # 停用某些來源
```
- 快取位置：`~/.cache/stockai`（可用 `STOCKAI_CACHE` 改），價格快取 10 分鐘。
- 官方來源（twse/tpex/finmind/nasdaq）為**未還原權息**價格，除權息日會出現跳空，可能影響均線訊號。

**估值資料**（顯示於報告，不計入評分）：台股取自證交所/櫃買 OpenAPI（本益比、殖利率、股價淨值比）；美股取自 Nasdaq（產業、市值、殖利率、分析師目標價、52 週高低）。

## 評分模型（-100 ~ +100）
| 因子 | 權重 | 內容 |
|---|---|---|
| 趨勢 | ±30 | 股價 vs 月線/季線、均線排列、月線斜率 |
| 動能 | ±25 | MACD 柱狀體方向、DIF 正負、RSI 區間（過熱扣分、超賣加分） |
| KD | ±15 | K/D 相對位置、高低檔黃金/死亡交叉 |
| 布林 | ±10 | %B 位置（均值回歸） |
| 量能 | ±10 | OBV 趨勢、帶量漲跌 |
| 相對強弱 | ±10 | 60 日報酬 vs 大盤（台股 ^TWII、美股 ^GSPC） |

評等：≥50 強力買進、≥20 買進、>-20 觀望、>-50 賣出、其餘強力賣出。

## 其他輸出
- **交易計畫**：停損 = 收盤 − 2×ATR，目標 = +2/+4×ATR；依「資金 × 風險比例」計算股數（台股以張為單位）。評分 < 20 時不建議建倉。
- **風險**：一年報酬、年化波動、夏普值、最大回撤、VaR95、Beta。
- **回測**：評分 ≥20 進場、≤-10 或 2.5×ATR 移動停損出場，扣除 0.4% 來回成本，t 日訊號 t+1 日起計報酬（無未來函數），並與買進持有比較。
- **監控事件**：評等變化、跌破停損/突破目標、站上/跌破季線、60 日新高/新低、單日 ±5%、爆量 2.5 倍。

## 測試
```bash
python -m unittest discover -s tests -v
```

> ⚠️ 本程式僅供研究與教育用途，不構成任何投資建議。投資有風險，請自行判斷並承擔損益。
