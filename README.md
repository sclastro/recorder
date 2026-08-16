# Recorder

一個為 Android（開發／測試機：Samsung Galaxy S23）而寫嘅專業錄音 App。

淺色暖白介面，柔和紅錄音色，錄音質素完全可調，剪輯無損。介面文字係英文。

## 功能

**錄音**
- 撳一下即錄；前景服務，熄螢幕／切走 App 都繼續
- 取樣率 8k–96kHz、16 / 24 / 32-bit float、單聲道／立體聲（部機唔支援嘅組合會灰咗）
- 格式：WAV（無損 PCM）、M4A（AAC）、OGG（Opus）
- 音源：預設咪 / 人聲優先 / 原始訊號（無 AGC 降噪，錄音樂用）/ 指向性
- AEC / NS / AGC 逐個開關
- 一鍵預設：語音備忘、會議、訪問、音樂
- 實時波形、dB 電平錶連峰值保持同削波警告
- 錄音中撳掣加書籤，下面條 strip 睇得到書籤喺邊；暫停／繼續；有電話入嚟自動暫停，收線自動繼續
- 自動分段：每 15 / 30 / 60 / 120 分鐘開新檔，錄一日都唔會得一個巨型檔案
- VOX 聲控：靜音嗰陣唔寫入，一有聲即刻繼續（門檻 -50 至 -30 dB 可調）
- 崩潰或冇電之後，下次開 App 自動復原未完成嘅錄音
- 橫向會自動變成左右兩欄，錄音掣唔會被擠出畫面

**檔案管理**
- 檔名範本（`{date}`、`{time}`、`{seq}`、`{preset}`、`{folder}`），錄完可即時改名
- 資料夾分類（真實目錄；Android 11 之後手機上面嘅檔案管理員睇唔到 `Android/data`，但插電腦行 MTP 見到同一個結構）、我的最愛、備註
- 搜尋（檔名／備註／資料夾）、排序（最新／最舊／最長／最大／名稱）
- 長按多選：一次過分享／移動／刪除；向側滑刪除，有 Undo
- 列表睇得出邊個播緊、邊個聽咗一半（迷你波形填色 + 百分比）
- **回收桶**：刪除唔會即刻冇，預設保留 30 日（7–90 日可調），隨時還原
- 啟動時同檔案系統對帳，喺外面改動都跟得返
- 匯入：揀部機入面其他音檔入嚟，一樣可以剪、可以分類
- 匯出細碼率：另存一個細嘅 AAC 檔（32–192 kbps），原檔唔郁
- **另存一份去你揀嘅資料夾**：錄音本體仍然放喺 App 目錄（錄音、剪輯、波形都要真路徑），
  但每錄完一個檔會自動複製一份去你用系統檔案選擇器揀嘅位置，噉手機嘅檔案管理員就見到。
  係「多一份」唔係「搬走」，所以會用多一倍空間，預設關咗

**播放同剪輯**
- 波形拖曳定位、±10 秒、變速 0.5x–3x、睡眠計時器、記憶播放位置
- 書籤：錄音中或者播放中都加得，長按刪除
- 背景播放：離開畫面、熄螢幕都繼續；通知欄／鎖屏／藍牙耳機／車機控制到
- 底部 mini player：喺 Record / Files 兩個 tab 都見到播緊乜、暫停得、撳一下返全螢幕
- A-B 循環：撳一下設 A，再撳設 B，第三下清除；波形上面會標出嗰段
- 音量增益（最多 +20 dB）同跳過靜音，兩樣都經 Media3 custom session command 傳落去
- 無損裁剪：WAV 精確到取樣點；M4A / OGG 唔重新編碼，切口對齊音框
- 喺播放頭切開一個檔案（一樣無損）
- 淡入淡出、音量正規化 —— **淨係 WAV**。壓縮格式要做呢兩樣就要重新編碼，
  每做一次質素跌一次，所以個編輯器會直接講明而唔會靜靜哋降質
- 裁剪可以「另存新檔」或者「取代原檔」（取代前會確認，同埋話你知會切走幾多）

## 點樣裝落部機

**方法一：GitHub Actions（唔使裝 Android Studio）**

每次 push 都會自動 build。去 repo 嘅 **Actions** 分頁 → 揀最新一次 **Build APK** → 喺 Artifacts 下載 `recorder-release-apk` → 解壓出 `app-release.apk` → 喺手機開個檔案直接安裝（要允許「安裝未知來源應用程式」）。

**方法二：本機 build**

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

> Release APK 而家用 debug key 簽名，方便自己安裝。要正式發佈就要換成自己嘅 keystore。

## 首次使用要留意

1. 開 App 會問咪高峰權限同通知權限，兩個都要俾。
2. **Samsung 電池優化好惡**：設定 → 應用程式 → Recorder → 電池 → 揀「不受限制」，唔係長時間後台錄音可能被系統殺。
3. 錄音檔案放喺 `Android/data/com.sclastro.recorder/files/Recordings/`，資料夾就係真實目錄。留意 Android 11 之後系統唔准第三方檔案管理員 browse 呢個位置，要插電腦先睇到。

## 開發

```bash
./gradlew testDebugUnitTest   # 單元測試
./gradlew lintDebug           # Android lint
./gradlew assembleDebug       # debug APK
```

技術棧：Kotlin 2.3 · AGP 9.3 / Gradle 9.7 · Jetpack Compose + Material 3 · Room · DataStore · Media3 ExoPlayer · AudioRecord + MediaCodec/MediaMuxer。手寫 DI（`AppContainer`），冇用 Hilt。

📄 完整開發計劃同下一步：[PLAN.md](PLAN.md)
