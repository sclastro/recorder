# 第四次系統檢視（2026-08-24，對住 commit `fb592ea`）

上一輪審咗最新加嘅嗰批（引擎、播放器、編輯器、mirror）。今次係讀返一直冇細睇過
嘅底層：編碼器、波形記錄器、儲存、探測、資料庫、manifest。

搵到 **五個問題**，全部修咗；另外做咗兩樣介面改善。

---

## 一、真問題

### 1. 編碼器嘅時間戳會慢慢飄 🔴

`EncodedSink.write()`：

```kotlin
val chunk = minOf(input.remaining(), size - offset)
val ptsUs = totalFramesFed * 1_000_000L / sampleRate
totalFramesFed += chunk / bytesPerFrame     // ← 每次都截斷
```

`chunk` 係「codec 個 input buffer 裝得落幾多」，**唔保證係 frame 大小嘅倍數**。
所以 `chunk / bytesPerFrame` 每次都掉咗個餘數 —— 位元組照樣餵晒俾 encoder，但
**帳面上嘅 frame 數一路少計**，寫落去嘅 presentation timestamp 就一路落後於真正
嘅音訊。錄得越長飄得越多。

立體聲 + 32 KB buffer 啱啱好整除，所以多數機唔會見到；但呢個係靠彩數，唔係靠
設計。

**修法**：改成數位元組，最後先除一次 —— 冇咗逐次截斷，冇咗累積誤差。

### 2. 波形記錄器喺音頻線程度養緊一個 `ArrayList<Byte>` 🔴

`Peaks.Recorder` 每 50ms 一個 bucket。十二個鐘嘅錄音 = **86 萬個 entry**。
`ArrayList<Byte>` 每個 entry 係一個物件參照，即係幾 MB 嘅參照陣列，而且**每次
容量翻倍就要複製成個陣列** —— 而呢件事發生喺
`THREAD_PRIORITY_URGENT_AUDIO` 嘅擷取線程上面。喺嗰度花一毫秒複製 =
掉一個 buffer。

CLAUDE.md 本身就寫住「擷取迴圈唔可以掂檔案系統」，同一個道理應該一早就套用喺
記憶體分配上。

**修法**：改成手動長大嘅 `ByteArray`。加咗六個測試釘住 bucket 算術同埋跨過
初始容量之後嘅成長。

### 3. 宣告咗一個從來冇用過嘅危險權限 🟠

`AndroidManifest.xml` 有 `BLUETOOTH_CONNECT`，但**成個 codebase 冇一個地方用過**。
Media3 經 media session 已經處理晒藍牙耳機／車機嘅路由同埋按鍵。

Android 12+ 佢係 runtime 權限，即係話呢個 App 嘅權限清單上面白白多咗一項
「附近的裝置」。對一個錄音 App 嚟講呢個交易好唔抵 —— 用戶睇你權限清單嗰陣，每
一項都要你攞信任嚟換。

**修法**：刪咗。

### 4. 備份規則漏咗所有設定 🟠

```xml
<include domain="database" path="." />
<include domain="sharedpref" path="." />
```

Room（我的最愛、備註、書籤、資料夾）有備份。但**所有設定都冇** —— 錄音質素、
檔名範本、主題、保留期、VOX、自動分段、mirror 資料夾，全部住喺 DataStore，
即係 `files/datastore/`，屬於 `file` domain，冇列出。

即係換機之後：檔案庫 metadata 返晒嚟，但偏好設定全部重設。

**修法**：兩個備份設定檔都加返 `<include domain="file" path="datastore/" />`。
（錄音本體本來就備份唔到 —— 幾 GB，遠超 auto-backup 上限。呢個係格式限制，
文件入面寫明。）

### 5. 資料夾可以叫做 `.trash`，直接撞上回收桶目錄 🟠

`sanitiseName()` 會 `trimEnd('.')` 但唔會 `trimStart`。所以
`sanitiseName(".trash") == ".trash"`，而 `folderDir()` 就會指去 App 自己嗰個回收
桶目錄。放入去嗰啲錄音**睇落就好似已經刪咗**。

機會唔大，但係一條靜靜哋整爛數據嘅路。

**修法**：連開頭嘅點都剝走（順帶避免造出隱藏檔案）。

---

## 二、介面改善

### 睇得到自己用咗幾多空間

呢個 App 嘅錄音放喺 `Android/data` —— **手機上面冇任何檔案管理員睇得到**。即係
話用戶完全冇辦法知道自己用緊幾多空間，直到部機話佢知冇位。

- Files 列表最底加咗一行：「42 recordings · 3.1 GB · 5 in the bin」
- 回收桶頂部改成「Holding 840 MB. Deleted recordings stay here for 30 days…」

回收桶嗰個特別有用 —— 「點解呢個 App 食咗咁多位」嘅答案九成喺嗰度。

### `MediaProbe` 唔再自己維護一份 RIFF parser

上一輪抽咗個 `WavFile` 出嚟俾 trim 同 edit engine 用，但 `MediaProbe` 仲留住一份
一模一樣嘅（連 `le16` / `le32` 都抄埋）。而家統一咗，改一次就三邊都啱。

---

## 三、檢查過冇問題嘅

- **OGG / API 29**：`AudioContainer.isSupported` 已經喺 API 29 以下擋咗 OGG，
  設定頁會 filter，`normalised()` 會 fallback 去 M4A。設計啱。
- **Room schema**：`exportSchema = true` + `room.schemaLocation` 設好，1.json 同
  2.json 都 commit 咗。做得啱。
- **磁碟寫滿**：`WavSink.write` 拋 IOException → captureLoop catch → **已經錄到
  嗰部分仍然會存低**，唔會成個掉。行為啱。
- **`.trash` 唔會被 reconcile 掃**：確認過，回收桶入面嘅檔案唔會被重新收養。

---

## 四、仲未做

**字串搬去 `strings.xml`** —— 第三次刻意冇做，理由見 CLAUDE.md。

**冇實機驗證嘅嘢**：AAC / Opus 嘅時間戳修正要真係錄一段長嘢再對時先確認到；
`Peaks.Recorder` 嘅成長行為有單元測試，但「唔會掉 buffer」呢件事量唔到。
