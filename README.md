# 股票主動分析（Android App）

台股 / 美股技術面多因子評分、ATR 停損計畫、風險指標與回測；可自訂自選股，並在背景定期重新分析、偵測事件後推播通知。
分析引擎由 Python 版（`stock_analyzer`）逐項移植為 Kotlin，並以 Python 的輸出做對照測試。

## 取得 APK
到 GitHub 的 **Actions → Build APK → 最新一次執行 → Artifacts → StockAI-apk** 下載；推送 `v*` 標籤（例如 `v1.0.0`）則會自動發佈到 **Releases**。

## 安裝
將 APK 傳到手機 → 點開安裝（需允許「安裝未知來源應用程式」）。Android 8.0 以上。

## 功能
- 自選股：用代號或名稱搜尋新增（台股上市/上櫃/ETF、美股/ETF），長按移除
- 手動排序：右上角 ⋮ →「調整順序」，按住 ≡ 上下拖曳，或用 ▲▼ 逐格移動；順序會保存
- 匯出：⋮ → 匯出 JSON（可含設定，**不含 API 金鑰**）或文字清單 CSV，由系統檔案選擇器決定存放位置
- 匯入：⋮ → 匯入…，支援本程式匯出的 JSON，以及一行一檔的文字 / CSV（`2330 台積電`、`AAPL`、`0050,元大台灣50`）；可選「合併」或「取代（含順序）」，並可選擇是否同時套用設定
- 個股頁：K 線 + 均線 + 成交量 + 評分走勢圖、因子分數、訊號解讀、交易計畫、風險、回測、估值
- 背景監控：每 15 分鐘～每天（WorkManager）；評等變化、跌破停損/突破目標、站上/跌破季線、創 60 日新高低、爆量、大幅波動
- 資料來源自動備援：Yahoo → 證交所 / 櫃買 / Nasdaq → FinMind → Alpha Vantage；可在設定關閉來源、填入金鑰、檢查來源

## 建置
```bash
gradle :app:assembleRelease        # 輸出 app/build/outputs/apk/release/app-release.apk
gradle :app:testDebugUnitTest      # 單元測試（對照 Python 結果、資料來源、UI 渲染）
```
簽章：本機以 `keystore.properties`（不在版控內）簽章；沒有時退回 debug 金鑰。
CI 預設用 debug 金鑰；若要固定簽章，請在 Settings → Secrets and variables → Actions 新增
`KEYSTORE_BASE64`（`base64 -w0 your.jks`）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
可用 `scripts/set-signing-secrets.sh <金鑰.jks> <keystore.properties>` 一次設定（需 `gh auth login`）。每次編譯的摘要頁會顯示 APK 的簽章 SHA-256，固定簽章時應該每次都相同。
**金鑰請自行備份**，遺失後無法以同一簽章更新已安裝的 App。

## 目錄
- `app/`：Android App（Kotlin + Jetpack Compose）
- `python/`：原始 Python 版分析程式（CLI、HTML 報告），Android 版的分析引擎由此逐項移植

> 本程式僅供研究與教育用途，不構成任何投資建議。投資有風險，請自行判斷並承擔損益。
