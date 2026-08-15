# 全面檢視（2026-08-11）

對住 commit `6e950ce` 逐個檔案睇一次，分四級。P0 係「而家已經係錯或者會出事」，
P1 係「明顯缺咗嘅功能」，P2 係介面打磨，P3 係長遠架構。

---

## P0 — 真問題，建議優先修

### 1. `LevelMeter` 喺 composition 期間寫 state

```kotlin
var peakHold by remember { mutableFloatStateOf(0f) }
peakHold = if (peak >= peakHold) peak else (peakHold - PEAK_DECAY)...
```

喺 composable function body 直接改 state 係 Compose 大忌 —— 寫入會觸發新一輪
recomposition，而衰減值取決於「recompose 咗幾多次」而唔係「過咗幾多時間」。
結果係峰值保持嘅衰退速度會隨畫面繁忙程度浮動，理論上仲可以自我觸發無限
recomposition。應該改用 `animateFloatAsState` 或者一個由時間驅動的
`LaunchedEffect`。

**影響**：電平錶行為唔穩定、可能耗電。**工夫**：細。

### 2. 錄音頁唔會 scroll —— 同剪輯頁一樣嘅溢出風險

`RecordScreen` 個 `Column` 係 `fillMaxSize()` 加一個 `weight(1f)` spacer，冇
`verticalScroll`。而家喺 S23 啱啱夠位，但係：系統字體放到最大、顯示尺寸調大、
或者細螢幕機，就會同剪輯頁一模一樣噉將錄音掣頂出畫面外。呢個 bug 已經中過一次。

**影響**：特定設定下錄音掣按唔到。**工夫**：細。

### 3. 播放冇背景播放 —— 一離開畫面就停

`PlayerViewModel` 入面直接 `ExoPlayer.Builder(...).build()`，綁死喺 ViewModel。
返上一頁 → ViewModel 銷毀 → `player.release()` → 播放中斷。冇 `MediaSession`，
所以通知欄、鎖屏、藍牙耳機、車機全部控制唔到。

聽一個鐘嘅會議錄音而唔可以熄螢幕，係好核心嘅缺陷。應該將播放搬入
`MediaSessionService`，同錄音服務並存。

**影響**：長錄音基本上聽唔到。**工夫**：中（半日左右）。

### 4. 每次入「Files」都做全盤磁碟對帳

```kotlin
LaunchedEffect(Unit) { viewModel.refresh() }   // → repository.reconcile()
```

`reconcile()` 會掃晒成個目錄樹、逐個檔案 `File.length()`、遇到未知檔案仲會用
`PeakGenerator` 解碼成個檔案去砌波形。錄音一多，每次撳 tab 都會卡。而且 App
啟動嗰陣已經做過一次。應該改成：只喺啟動同用戶下拉重新整理先做，或者用
`lastModified` 做快取判斷。

**影響**：檔案多咗會明顯卡頓。**工夫**：細至中。

### 5. 備註每打一個字就寫一次資料庫

`PlayerScreen` 個 `onValueChange` 直接叫 `viewModel.setNote(value)`，而
`setNote` 每次都做一次 DB update 再重新查詢。打一句話 = 幾十次磁碟寫入。
應該 debounce（停 500ms 先寫）或者失焦／離開畫面先寫。

**影響**：打字卡、無謂磨損。**工夫**：細。

### 6. 錄音途中冇「取消，唔要呢段」

`RecorderEngine.cancel()` 寫咗但係全個 App 冇一個地方叫佢 —— 死碼。而家錄咗
之後淨係停得，停完一定會存檔。撳錯掣就要去檔案庫再刪一次。

**影響**：多餘步驟。**工夫**：細。

### 7. 資料夾可以建立，但改唔到名、刪唔到

`RecordingRepository.deleteFolder()` / `renameFolder()` 同 `LibraryViewModel`
嘅包裝都寫好晒，但係 `LibraryScreen` 從來冇叫過 —— 又係死碼。用戶打錯字建咗
個資料夾就永遠喺度。

**影響**：分類系統唔完整。**工夫**：細。

### 8. 檔案位置喺手機上面其實睇唔到

錄音放喺 `Android/data/com.sclastro.recorder/files/Recordings/`。Android 11 之後
系統唔准第三方檔案管理員（包括 Samsung「我的檔案」）browse `Android/data`。
插電腦用 MTP 睇得到，但係喺部機自己睇唔到。README 同設定頁嗰句「插上電腦可以
直接睇到同一個結構」只講啱咗一半，應該修正措辭，並且推進 SAF 自訂位置（P2 原本
就排咗）。

**影響**：預期落差。**工夫**：文案細；SAF 中。

### 9. 細碎死碼

- `RecordViewModel._message` / `message` / `consumeMessage()` —— 從來冇 set 過值
- `RecordingRepository.observeRecording()` —— 零呼叫
- `MiniWaveform(progress = 0f)` —— 列表永遠傳 0，個參數等於冇用
- `RecorderEngine.togglePause()` —— 只有一個呼叫點，同 `pause`/`resume` 重複

---

## P1 — 明顯缺咗嘅功能

### 檔案管理
- **批量操作**：長按多選 → 一次過刪除／移動／分享。而家一次只可以做一個。
- **滑動刪除 + Undo snackbar**：刪除係入回收桶，但冇即時「復原」提示。
- **回收桶入口太隱蔽**：而家收喺排序選單最底。應該喺頂欄或者做成一個資料夾。
- **搜尋冇清除掣**，亦冇搜尋歷史。
- **排序缺咗「檔案大小」**。

### 播放
- **記憶播放位置**：聽長錄音中途走開，返嚟由頭再嚟。
- **睡眠計時器**、**A-B 循環**（原計劃有，未做）。
- **播放時加／刪書籤**：而家書籤只可以錄音途中加，播放時只可以跳去，改唔到、刪唔到。
- **音量增益**：細聲錄音冇得放大。

### 錄音
- **裝置能力探測**：96kHz、24-bit、UNPROCESSED 唔係每部機支援。而家係揀完、撳
  錄音、失敗、彈錯誤訊息。應該啟動時探測一次，唔支援嘅選項直接灰咗。
- **錄音中睇唔到書籤位置**：只顯示「已加 N 個」，睇唔到喺邊。
- **自動分段**（每 X 分鐘／X MB 開新檔）、**VOX 聲控**：計劃入面有，未做。
- **來電自動暫停**：計劃入面有，未做。而家有電話入嚟可能直接斷。

### 剪輯
- 只有 trim。**分割、合併、淡入淡出、音量正規化、自動剪頭尾靜音** 全部未做。
- **匯入外部音檔嚟剪** 未做。

### 分享／匯出
- 分享係原檔直出。冇「匯出成細碼率」、冇「匯出到 Music 資料夾」。

---

## P2 — 介面打磨

- **`qualityLine()` 對壓縮格式顯示 `16bit` 好誤導** —— AAC 講位元深度冇意義，
  應該顯示 bitrate。
- **`"1 bookmark(s)"`** —— 單複數未處理。
- **錄音頁兩行 chip**（預設 + 資料夾）各自橫向 scroll，視覺上有啲散。
- **冇 About／版本頁**，出咗事都唔知自己裝緊邊個版本。
- **波形冇無障礙描述**，TalkBack 用戶完全讀唔到。
- **橫向、平板、摺疊機** 未處理，而家淨係單欄。
- **深色主題未實機驗證過**。
- **空狀態可以再友善**（例如檔案庫空嘅時候直接俾個「開始錄音」掣）。
- **載入狀態**：開一個長錄音，波形要解碼，而家係空白冇 spinner。

---

## P3 — 架構／長遠

- 字串全部 inline 喺 Kotlin。純英文冇問題，但將來要多語言就要先搬去 `strings.xml`。
- 得 JVM 單元測試（19 個），冇 instrumented test，錄音／播放／剪輯嘅實機路徑
  完全靠手動測。至少值得加 `TrimEngine` 對真 M4A 嘅 instrumented test。
- 單一 `:app` module。而家規模（~6100 行）仲可以，再大就值得拆。
- 冇 Baseline Profile，冷啟動可以再快。
- Release 用 debug key 簽名。自己用冇所謂，但要記住換咗 key 就要移除重裝。

---

## 建議次序

1. **P0 第 1、2、5、6、7、9** —— 全部係細嘢，一次過清晒
2. **P0 第 3（背景播放 + MediaSession）** —— 單一最大體驗提升
3. **P0 第 4（reconcile 時機）+ P1 批量操作 + 滑動刪除** —— 檔案多咗就會需要
4. **P1 裝置能力探測 + 來電暫停** —— 錄音可靠性
5. 之後先至到 SAF、分割合併、轉文字嗰啲大功能

---

## 已完成（commit 待補）

第一批 P0 細項全部做咗：

- ✅ 1 電平錶 composition 期間寫 state → 改用 frame loop，衰減按真實時間
- ✅ 2 錄音頁上半部可以 scroll，錄音掣固定喺底部
- ✅ 5 備註 debounce 600ms 先寫 DB，離開畫面即刻 flush
- ✅ 6 錄音途中可以「Discard recording」（有確認對話框）
- ✅ 7 資料夾可以新增／改名／刪除（Files 頁資料夾圖示 → Manage folders）
- ✅ 8 儲存位置嘅講法修正咗（設定頁 + README）
- ✅ 9 死碼清晒（`_message`、`observeRecording`、`togglePause`、`MiniWaveform.progress`）
- ✅ 額外：回收桶升做獨立按鈕連數量徽章；AAC/Opus 唔再顯示誤導嘅 `16bit`；書籤單複數

第二批：

- ✅ 3 背景播放 —— 播放搬入 `PlaybackService`（Media3 `MediaSessionService`），
  ViewModel 改用 `MediaController` 連過去。離開畫面、熄螢幕都繼續播；通知欄、
  鎖屏、藍牙耳機、車機全部控制到；拔耳機自動暫停；音訊焦點交返俾系統處理。
- ✅ 4 reconcile 改成只喺 App 啟動同用戶下拉重新整理先做（檔案庫加咗 pull-to-refresh）
- ✅ 順帶：開長錄音時波形解碼有 spinner，唔再係空白

一個取捨要記住：`skipSilenceEnabled` 係 ExoPlayer 專有嘅設定，隔住
`MediaController` 撳唔到，所以「Skip silence」個掣暫時移除咗。要恢復就要喺
session 加一個 custom command 傳落去。
