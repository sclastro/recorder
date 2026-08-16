# 介面檢視（2026-08-11，對住 commit `a8e16c6`）

呢份淨係講介面同互動，功能缺口睇 `REVIEW.md`。分三級：**缺陷**（而家已經壞或者
就快壞）、**結構**（資訊架構同流程）、**細節**（視覺同一致性）。

---

## A. 缺陷

### A1. 播放頁會溢出，備註欄應該已經睇唔到 🔴

`PlayerScreen` 個 `Column` **冇 `verticalScroll`**。而家由上到下疊住：

| 元素 | 約高度 |
|---|---|
| 資訊行 + 間距 | 32dp |
| 波形卡（計時器 + 波形 + 時長） | 270dp |
| 播放控制列 | 72dp |
| 速度 chip 行 | 32dp |
| 睡眠計時器行 | 44dp |
| 書籤行 + 提示 | 48dp |
| 備註輸入框 | 110dp |
| 各段 spacer | 100dp+ |
| **合計** | **≈ 700dp** |

S23 扣咗頂欄同系統列之後大約 720dp 可用 —— 即係啱啱好或者已經超。我第二、三批
先後加咗睡眠計時器同書籤行，多咗大約 90dp，所以**備註欄好可能已經被切走**。

呢個同剪輯頁「Save 掣消失」、錄音頁嗰個係**同一類 bug**，已經係第三次。剪輯頁同
錄音頁我修咗，播放頁漏咗。

**應該點**：播放頁上半部加 `verticalScroll`，或者將速度／睡眠／書籤收埋入一個
「Options」bottom sheet，個頁淨返計時器、波形、控制列、備註。我傾向後者 —— 見 B2。

### A2. 播緊嘢但畫面完全冇提示 🔴

第二批做咗背景播放，副作用係：你撳返上一頁，聲繼續出，但成個 App 入面**冇任何地方
話俾你知播緊乜、亦冇得暫停**。要拉開通知欄先控制到。

一個播放器最基本嘅嘢：**底部 mini player**。應該喺 Record / Files 兩個 tab 上面、
navigation bar 之下顯示一條，有名、進度、播放暫停掣，撳一下展開返全螢幕播放頁。

### A3. 列表睇唔出邊個播緊、邊個聽咗一半

已經有 `lastPositionMs`，但列表完全冇用。應該：

- 播緊嗰個列出用播放色嘅小標記（例如迷你波形變色，或者名前面一粒點）
- 聽咗一半嘅顯示進度（迷你波形填色到嗰個位置 —— `MiniWaveform` 本身有過
  `progress` 參數，我第一批當佢係死碼刪咗，而家有真數據可以填返落去）

---

## B. 結構

### B1. 底部只有兩個 tab，但實際有六個畫面

Record / Files 係 tab；Settings 收喺齒輪、Trash 收喺垃圾桶圖示、Player 同 Editor
係 push 上去。層級唔算深，但有兩個問題：

- **Settings 同 Trash 都喺 Files 頁頂部**，兩個圖示連 New folder 三個逼埋一齊
- 由 Player 撳「剪輯」入到 Editor，返返嚟係返 Player 定 Files？而家係返 Player，
  但剪完之後個新檔案喺 Files，用戶要撳兩次返先見到

### B2. 播放頁四行橫向 scroll 疊住

速度、睡眠、書籤、加上頂部一行，一頁四行可以左右拉嘅嘢。每行都唔夠闊要拉，
但用戶唔知邊行拉得、拉到幾多。而且橫向 scroll 同垂直 scroll 打交。

**建議**：控制列下面淨返一行「1.0×｜Sleep｜Bookmark」三個掣，撳落去彈 bottom
sheet 揀。書籤列表搬入 sheet 或者直接標喺波形上（波形已經畫緊書籤線）。

### B3. 錄音頁「存入邊個資料夾」呢步太重

錄音之前要先揀資料夾，但九成情況都係同一個。而錄完之後個 SaveSheet 又再問一次
資料夾。等於問咗兩次。

**建議**：錄音頁淨係顯示目前目標資料夾（一行細字，撳到可以改），唔好成行 chip；
分類主力交返俾錄完之後嗰個 sheet。

### B4. 剪輯淨係得「另存新檔」，冇「取代原檔」

每次剪都多一個檔案。剪頭尾靜音呢類操作，多數人想直接取代。應該喺儲存嗰陣俾兩個
選擇。（要小心：取代係破壞性，要確認。）

---

## C. 細節

### C1. 錄音計時器顯示百分之一秒

`formatDurationPrecise` 喺錄音頁會跳到 `00:03.24` —— 每 40ms 跳一次，好嘈，而且
錄音時你根本唔需要百分之一秒。百分之一秒喺剪輯頁先有意義。錄音頁應該用
`mm:ss`，長過一個鐘先加時位。

### C2. 波形完全冇無障礙描述

三個 Canvas（LiveWaveform、MiniWaveform、WaveformScrubber）都冇 `semantics`。
TalkBack 讀落去係一片空白。至少要有 contentDescription，scrubber 仲應該有
`progressBarRangeInfo` 同 seek action。

### C3. 垂直節奏唔一致

`Spacer(Modifier.height(...))` 用過 4 / 6 / 8 / 10 / 12 / 14 / 16 / 18 / 20 / 24dp。
應該收斂到一套（例如 4 / 8 / 16 / 24），或者用 `Arrangement.spacedBy`。

### C4. 播放色（sage）幾乎用唔到

`accents.playback` 淨係喺波形已播部分同電平錶漸層出現。播放控制列、播放中嘅列表
項目都係用主色紅。紅色本來係「錄音」嘅意思，用嚟表示播放會混淆。播放頁嘅
FilledIconButton 應該用 sage。

### C5. 空狀態太乾

「No recordings yet — tap the button on the Record tab」淨係一行字。應該有個圖示
加一個直接跳去錄音頁嘅掣。回收桶空嘅時候同樣。

### C6. 深色主題未驗證過

色票寫齊，但由頭到尾冇喺實機睇過。特別要睇：錄音掣個漸層喺深底會唔會太搶、
`surfaceContainerLowest` 同 `Night` 對比夠唔夠、削波紅喺深底夠唔夠深。

### C7. 橫向同摺疊機

全部畫面單欄。橫向嘅時候錄音頁個波形卡會食晒成個高度，控制列出唔到。至少要
喺橫向改成左右兩欄。

### C8. 設定頁嘅檔名 token 係一堵細字

六行 `{token} — 說明` 灰色細字疊住。應該做成可以撳嘅 chip，撳一下插入去輸入框。

---

## 建議次序

1. **A1**（播放頁溢出）—— 已經係壞嘅，同 A 類第三次
2. **A2**（mini player）—— 背景播放做咗但用戶用唔到，呢個係配套
3. **C1**（計時器精度）、**C4**（播放色）—— 兩分鐘嘅嘢，觀感提升明顯
4. **A3**（列表播放狀態同進度）
5. **B2**（播放頁控制收埋入 sheet）—— 順手解決 A1 嘅根源
6. **C2**（無障礙）、**C5**（空狀態）
7. **B3 / B4 / C7 / C8** 睇需要

---

## 已完成

第一批（A1 / A2 / B2 / C1 / C4）：

- ✅ **A1 + B2** 播放頁溢出 —— 上半部加咗 `verticalScroll`；速度、睡眠計時器、
  書籤三行橫向 scroll 收埋成一行「1.0× ｜ Sleep ｜ Marks」三個掣，撳落去彈
  `PlayerOptionsSheet`（bottom sheet）。書籤列表搬入 sheet，可以喺度加、跳、刪。
  兩個問題一次過解決：頁面唔再溢出，橫向同垂直 scroll 亦唔再打交。
- ✅ **A2 mini player** —— `MiniPlayerBar` 喺 Record / Files 兩個 tab 嘅
  navigation bar 上面，顯示檔名、進度線、位置／總長、播放暫停同關閉掣，撳一下
  開返全螢幕播放頁。用 Media3「一個 session 可以有多過一個 controller」嘅特性，
  開多個 `MediaController` 去睇同一個 session，唔會同播放頁爭。
- ✅ **C1 計時器精度** —— 錄音頁改用 `mm:ss`，唔再每 40ms 跳一次百分之一秒。
  百分之一秒淨係喺剪輯頁同播放頁嘅位置讀數度出現，嗰度先有意義。
- ✅ **C4 播放色** —— 播放頁個大播放掣由紅轉 sage（`accents.playback`），mini
  player 嘅進度線同播放掣一樣。紅色而家淨係代表錄音。

第二批（A3 / C2 / C5）：

- ✅ **A3 列表播放狀態同進度** —— `MiniWaveform` 攞返個 `progress` 參數（第一批
  我當死碼刪咗，而家真係有數據）。播緊嗰行：名前面一個 sage 喇叭／暫停圖示、
  邊框轉 sage、迷你波形跟住 mini player 實時填色。聽咗一半嘅行：填到
  `lastPositionMs` 嗰個位，後面加個 sage「45%」。頭尾三秒唔當「聽過」，同播放
  續播嗰條規則一致 —— 呢個判斷抽咗做 `Recording.listenedFraction`，有六個單元測試。
- ✅ **C2 波形無障礙** —— `WaveformScrubber` 加咗 `contentDescription`、
  `progressBarRangeInfo` 同 `setProgress` action，TalkBack 開住都拉得動（拖 canvas
  本身係碰唔到嘅）。描述由呼叫方傳實際時間入嚟：播放頁「Waveform, 02:05 of 10:30」、
  剪輯頁「Waveform, keeping 00:03.20 to 04:11.50」。`LiveWaveform` 有靜態描述；
  `MiniWaveform` 明確標成裝飾（`clearAndSetSemantics`），因為同一行嘅文字已經講晒。
- ✅ **C5 空狀態** —— 由一行灰字變成圖示 + 標題 + 一句解釋 + 一個掣，而且分四種
  情況講唔同嘢：未有錄音（「Start recording」跳去錄音頁）、搜尋冇結果（「Clear
  search」）、我的最愛係空（「Show all」）、資料夾係空（「Show all」）。回收桶空
  嘅時候同樣，順帶收起上面嗰句重複嘅保留期說明。

第三批（B1 / B3 / B4 / C3 / C8）：

- ✅ **B3** 錄音頁嘅資料夾 chip 行變成一行「Saving to …」，撳到彈 picker
- ✅ **B4** 剪輯加咗「Replace the original」，有確認同埋話你知會切走幾多；
  `replaceFile()` 會先將原檔搬開，換唔成就搬返，範圍外嘅書籤會清走、範圍內嘅平移
- ✅ **B1** 資料夾／回收桶／排序三個圖示收埋成一個 overflow，回收桶個數字提上去
- ✅ **C8** 檔名 token 變成 chip，撳一下插入去游標位置
- ✅ **C3** `Spacer` 高度收斂到 4 / 8 / 16 / 24

第四批（功能）：

- ✅ 錄音：自動分段（時間或大小）、VOX 聲控、書籤 strip
- ✅ 播放：A-B 循環、音量增益（`LoudnessEnhancer`）、跳過靜音（custom session command）
- ✅ 剪輯：喺播放頭切開、淡入淡出、正規化（後兩樣淨係 WAV，見 CLAUDE.md）
- ✅ 匯入外部音檔、匯出細碼率
- ✅ **C7** 錄音頁橫向變左右兩欄
- ✅ **C6** 深色主題 —— 冇實機，所以改為用單元測試量對比度：
  正文對背景全部過 WCAG AA 4.5:1，accent 當圖形計過 3:1，七個測試一次過
- ✅ SAF 自訂位置 —— 做成「另存一份」而唔係搬走，理由喺 CLAUDE.md
- ✅ Baseline Profile（手寫，冇 device 量唔到真數據）

**冇做，而且係刻意冇做**：字串搬去 `strings.xml`。CLAUDE.md 本身寫住「英文獨大
就唔使搬」，而家幾百句 inline 字串搬一次係大規模改動、容易改壞嘢，而且喺得一種
語言嘅情況下一分好處都冇。要加第二種語言嗰日先做。

順帶執咗一個真 bug：`RecorderApp.uriFor()` 個 FileProvider authority 寫成
`"${'$'}{context.packageName}.fileprovider"`，即係字面上嘅 `${context.packageName}
.fileprovider`。多選分享兩個以上檔案會直接 crash。單選嗰條路徑係另一段正常嘅碼，
所以之前試唔出。而家兩條路徑共用同一個 helper。

第二個真 bug：`LibraryScreen` 由頭到尾冇 render 過 `SnackbarHost`，所以第三批
講咗「滑動刪除 + Undo snackbar」嗰個 Undo **從來冇出現過** —— `showSnackbar`
冇 host 就一直 suspend，畫都冇畫出嚟。而家補返。
