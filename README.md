# Recorder

一個為 Android（開發／測試機：Samsung Galaxy S23）而寫嘅專業錄音 App。

淺色暖白介面，柔和紅錄音色，錄音質素完全可調，剪輯無損。介面文字係英文。

## 功能

**錄音**
- 撳一下即錄；前景服務，熄螢幕／切走 App 都繼續
- 取樣率 8k–96kHz、16 / 24 / 32-bit float、單聲道／立體聲
- 格式：WAV（無損 PCM）、M4A（AAC）、OGG（Opus）
- 音源：預設咪 / 人聲優先 / 原始訊號（無 AGC 降噪，錄音樂用）/ 指向性
- AEC / NS / AGC 逐個開關
- 一鍵預設：語音備忘、會議、訪問、音樂
- 實時波形、dB 電平錶連峰值保持同削波警告
- 錄音中撳掣加書籤；暫停／繼續
- 崩潰或冇電之後，下次開 App 自動復原未完成嘅錄音

**檔案管理**
- 檔名範本（`{date}`、`{time}`、`{seq}`、`{preset}`、`{folder}`），錄完可即時改名
- 資料夾分類（真實目錄，插上電腦見到同一個結構）、我的最愛、備註
- 搜尋（檔名／備註／資料夾）、排序（最新／最舊／最長／名稱）
- **回收桶**：刪除唔會即刻冇，預設保留 30 日（7–90 日可調），隨時還原
- 啟動時同檔案系統對帳，喺外面改動都跟得返

**播放同剪輯**
- 波形拖曳定位、±10 秒、變速 0.5x–3x、跳過靜音、書籤跳轉
- 無損裁剪：WAV 精確到取樣點；M4A / OGG 唔重新編碼，切口對齊音框
- 一律另存新檔，原檔唔會改到

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
3. 錄音檔案放喺 `Android/data/com.sclastro.recorder/files/Recordings/`，資料夾就係真實目錄。

## 開發

```bash
./gradlew testDebugUnitTest   # 單元測試
./gradlew lintDebug           # Android lint
./gradlew assembleDebug       # debug APK
```

技術棧：Kotlin 2.3 · AGP 9.3 / Gradle 9.7 · Jetpack Compose + Material 3 · Room · DataStore · Media3 ExoPlayer · AudioRecord + MediaCodec/MediaMuxer。手寫 DI（`AppContainer`），冇用 Hilt。

📄 完整開發計劃同下一步：[PLAN.md](PLAN.md)
