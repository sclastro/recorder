# 第三次系統檢視（2026-08-23，對住 commit `c635e3f`）

上一輪一次過加咗好多嘢 —— 自動分段、VOX、A-B、mirror、編輯器 —— 而全部都冇上過
機。所以今次係逐行讀新碼，唔係再列願望清單。

搵到 **七個真問題**，全部修咗。另外做咗兩樣功能／介面改善。

---

## 一、真 bug

### 1. 音頻線程每 20ms 一次 `stat()`，就算冇開自動分段 🔴

```kotlin
policy.shouldSplit(segmentFrames, config.sampleRate, currentFile.length())
```

`shouldSplit()` 入面第一句就係 `if (!splits) return false`，睇落好安全。但係
**Kotlin 嘅參數係先計後傳** —— `currentFile.length()` 係一個 `stat()` 系統呼叫，
喺參數位置就已經行咗，短路根本救唔到。

即係話：**每一個錄音、每秒五十次，喺音頻線程度做一個檔案系統呼叫**，而九成情況
下個結果即刻掉咗。音頻線程正正就係最唔應該有 syscall 嘅地方。

**修法**：改成 lambda（`fileBytes: () -> Long`），真係要量先量。加咗個測試釘住
「冇設大小上限就唔准掂個檔案」—— 呢個先係真正防止翻兜嘅嘢。

### 2. WAV 過 4 GB 會寫出壞檔案 🔴

`WavSink.buildHeader()` 用 `i32()` 寫 RIFF 同 data chunk 嘅長度 —— RIFF 呢兩個
欄位係 **無符號 32-bit**，過咗 4 GB 就 wrap。`dataBytes` 本身係 Long，所以音訊
繼續寫得落，但**個 header 講大話**。

呢個唔係理論問題，係呢個 App 主打嘅用法：

| 格式 | 幾耐到 4 GB |
|---|---|
| 96 kHz / 24-bit / 立體聲 | **2 小時 04 分** |
| 48 kHz / 24-bit / 立體聲 | 4 小時 08 分 |
| 48 kHz / 32-bit float / 立體聲 | 3 小時 07 分 |
| 44.1 kHz / 16-bit / 單聲道 | 13 小時 32 分 |

而錄音服務個 wake lock 攞足 **12 個鐘**。即係最細嗰個格式都摸得到。

仲有一個令佢更難被發現嘅地方：**本 App 自己讀返呢啲檔案係冇事嘅** ——
`WavFile.read()` 見到 size 唔合理就會 fallback 去真實檔案長度。所以喺機上面試
一世都試唔出，插上電腦或者用第二個播放器先會爆。

**修法**：capture 去到上限之前強制分段，唔理用戶設定 —— 兩個正常檔案好過一個
壞檔案。呢個係格式限制，唔係偏好。設定頁會講明點解 WAV 會自己分段，如果唔講，
兩個鐘嘅 WAV 突然變兩個檔案睇落就似 bug。

### 3. 撳 Discard，但已經分段存咗嘅部分留喺檔案庫 🔴

對話框寫住「nothing will be saved」。但自動分段係**一切完就即刻入檔**，所以錄咗
三個鐘、切咗五段、然後撳 Discard —— 前面四段仲喺度。個承諾係假嘅。

**修法**：service 記住今次 capture 已經入咗檔嘅 id；discard 嗰陣一齊掉入回收桶
（唔係直接刪 —— 同 App 其他刪除行為一致，仲原得返）。對話框亦都改成講真話：
「已經存咗嘅 N 個部分會入回收桶」。

### 4. 音量增益／跳過靜音會同 UI 脫節 🔴

呢兩個設定係喺 **session** 上面（全個 App 一個），但 UI 狀態係喺
`PlayerViewModel`（每個畫面一個）。所以：錄音 A 開 +10 dB → 返去 → 開錄音 B →
新 ViewModel、`gainDb = 0` → **個 sheet 話你知「Off」，但把聲仲係大咗 10 dB**。

**修法**：service 經 `setSessionExtras` 公佈實際值，controller 一連上就讀返。

### 5. Mirror 資料夾喺主線程做 ContentResolver 查詢 🔴

`FolderMirror.label()` / `isUsable()` 係普通 function，而 `SettingsScreen` 直接
喺 composition 入面叫 —— **每次 recomposition 兩個阻塞查詢**。`LibraryViewModel`
嗰個 `stateIn` 一樣係喺 Main 上面行。

**修法**：合併成一個 `suspend describe()`（`withContext(IO)`），畫面改用
`produceState`，ViewModel 加 `distinctUntilChanged`。

### 6. 「Original replaced」呢句提示永遠唔會出現 🟠

```kotlin
load(recording.id)                                    // 開咗個新 coroutine
_state.value = _state.value.copy(message = "Original replaced")
```

`load()` 唔等 —— 佢遲啲先做完，然後 **整個 state 換成一個全新嘅 `EditorUiState`**，
把個 message 抹走。`StateFlow` 會 conflate，所以個 snackbar 多數乜都見唔到。

**修法**：抽咗個 suspend `reload()`，等佢做完先寫 message。順手喺 reload 度加
`player.stop()` —— 個檔案啱啱喺佢腳下面被換過。

### 7. VOX 一路冇聽到聲，撳停之後乜都冇發生 🟠

VOX 開住但從來冇夠大聲 → 一個 frame 都冇寫 → engine 返 `null` → service 靜靜咁
`stopSelf()`。用戶錄咗五分鐘，撳停，**冇檔案、冇訊息、乜都冇**，好似 App 食咗佢。

**修法**：冇嘢錄到就出聲，而且分開講係 VOX 門檻問題定係錄得太短。

---

## 二、功能／介面改善

### VOX 門檻而家睇得到

之前喺設定揀 −40 dB 係盲摸 —— 冇任何參照。而家開咗 VOX 之後，**電平錶上面會有
一條門檻線**，下面亦都寫住「Peak −32 · VOX −40 dB」。開口講嘢就見到自己過唔過到
條線，唔使靠估。

### 大小分段接返上 UI

引擎一直支援 `splitMegabytes`（連測試都有），但設定頁從來冇設過佢 —— 即係一半
做完嘅功能。而家「Every 15 min…」下面多一行「Or every 100 MB / 250 MB / 500 MB /
1 GB」。

---

## 三、其他檢查（過）

- `Locale.getDefault()`：零個（CLAUDE.md 禁止）
- `runBlocking`：零個
- 中文字串殘留：搵到一個，但係喺 `Entities.kt` 嘅**註釋**入面，唔係 shipped 字串
- `!!`：兩個，都喺同一個 null check 入面，安全
- 57 個單元測試、lint、debug + release build 全部過

---

## 四、仲未做

**字串搬去 `strings.xml`** —— 同上一輪一樣，刻意冇做，理由見 CLAUDE.md。

**冇實機驗證嘅嘢**（呢度冇模擬器）：自動分段真係切得乾唔乾淨、VOX hangover
一秒半啱唔啱手、mirror 寫入 SAF 樹嘅實際速度、深色主題睇落點（對比度量過，
但「靚唔靚」量唔到）。
