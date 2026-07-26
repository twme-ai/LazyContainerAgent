# LazyContainerAgent

**中文** ｜ [English](README.en.md)

> **箱子物品「延遲反序列化(不急著把資料拆解成遊戲內物件,拖到真的要用才拆)+ 沒碰過就原樣寫回」的 Java agent。**
> 支援 Paper 26.2、26.1.x、1.21.11、1.21.8、1.20.6、1.19.4,把 chunk(遊戲世界切成一塊一塊的地圖區域,伺服器以此為單位載入/卸載)載入時「立刻把每個箱子的物品從 NBT(Minecraft 儲存物品/方塊資料的二進位格式)解包」與卸載時「重新打包」這兩筆白工砍掉。

🧠 原始單版本 26.2 實作曾經 Claude Fable 5 對抗審計(49 個獨立 agent,詳見 [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)),並修復一個管理員指令才會踩到的資料別名漏洞。**該報告是歷史設計證據,不涵蓋本次多版本重構**;本次變更以四種真實 NMS 位元碼測試與七版 Paper round-trip 作為驗證。

⚠️ **這不是外掛(plugin),是 Java agent** —— 用 `-javaagent:` 掛在 JVM 上,**不要丟 `plugins/`**(丟了沒用)。

> 🔒 **版本敏感(務必先讀)**
> 本 agent 以 bytecode 直接織入 Paper 內部類別,屬**版本敏感**工具。單一 jar 內含四套經真實 Paper NMS 編譯的 template,啟動時依實際方法名稱與 descriptor 自動選擇,不依版本字串猜測。
> - 已驗證的執行組合:Paper **26.2 / 26.1.2 / 26.1.1 + Java 25**、**1.21.11 / 1.21.8 / 1.20.6 + Java 21**、**1.19.4 + Java 17**。
> - Paper 官方沒有單獨的 `26.1` 下載項目;本文件中的 `26.1.x` 指已實測的 `26.1.1` 與 `26.1.2`。
> - 不認得的 NMS 結構會印出 `unsupported NMS layout`,保持全部 vanilla、不啟用優化;任一 leaf 攔截點不完整也會整個 leaf 原樣保留,避免部分改寫。
> - 測試素材(region / 物品 dump)為目標版格式,請勿在其他版本載入。
> - 多版本實機測試報告:[`docs/test-reports/multi-version.md`](docs/test-reports/multi-version.md);原 26.2 深度報告:[`docs/test-reports/26.2.md`](docs/test-reports/26.2.md)。

---

## 快速上手

> 前提:使用上列已驗證的 Paper / Java 組合。

**1. 放 jar** —— 把 `LazyContainerAgent.jar` 放到節點看得到的位置(跟你的伺服器 jar 放同一層最省事)。**不要丟進 `plugins/`**:它是 Java agent、不是外掛,丟了沒作用。

**2. 改啟動參數** —— 在 `java` 那行、`-jar` 的**前面**,加上以下幾段(第一次**請先用 shadow 驗證模式**):

```bash
java ... \
  -javaagent:LazyContainerAgent.jar \
  -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true \
  -jar <你的 Paper>.jar nogui
```

**3. 先驗證,別急著上真效能** —— 開著 `shadow=true` 跑個幾天。它會把優化後的輸出跟原版做法**逐位元組對照**:只要 `shadowMismatch` 一直是 **0**,就代表輸出跟原版完全一致、**資料零風險**。代價是這階段兩套都做、暫時不會變快。
> - 開機 log 應出現 `[LazyContainer] agent installed … [SHADOW mode]`。
> - verbose 每隔一段印一行 `stash=… rawSave=… shadowMismatch=0 …`;`stash` 持續往上爬 = 正在運作。

**4. 確認沒問題,再換真效能** —— 跑數天 `shadowMismatch=0`、也沒玩家回報少東西,就把 `-Dlazycontainer.shadow=true` 拿掉、重啟。這時「沒人碰過的箱子」會直接原樣寫回(跳過打包),效能才真正省下來。

**回滾** —— 把那幾段 `-D` 與 `-javaagent` 拿掉、重啟,立刻回 100% 原版,**不需要任何資料遷移**(硬碟格式從頭到尾沒被改過)。

---

## 這東西在解決什麼

開伺服器開久了,你大概都會撞上一種很微妙的卡頓:

明明沒什麼人在線上,主執行緒卻莫名其妙地忙。

抓 spark 一看,真兇往往不是怪、不是紅石——是**箱子**。

更精確一點,是「箱子裡的東西」。Minecraft 把物品存在硬碟上時是壓縮打包的;每當一塊地圖(chunk)被載進記憶體,伺服器就把那一區**每一個箱子、每一格物品,從 NBT 完整拆包一遍**;這塊地圖要卸載時,又**整批重新打包**寫回去。

問題是——那些箱子,絕大多數從載入到卸載,**根本沒人去開**。

拆了、又包回去,中間沒人看一眼。純白工。

而且 1.21 之後物品帶了 data components(附魔、lore、自訂名稱、容器內容…),拆包打包更貴。一座放滿地圖畫的倉庫、一條塞滿界伏盒的儲存線,光是「被載入」這件事,就能把主執行緒吃掉好幾成——在廢土(約 110 個 Paper 節點)的正式環境裡,負載最重的節點一度有 **45%** 的主執行緒,就卡在這一條鏈上:

```
ChunkFullTask.run → … → ChestBlockEntity.loadAdditional
  → ContainerHelper.loadAllItems     ← 拆包箱子物品 ≈ 45%
```

面對這種卡頓,最輕鬆的解法是**禁止**——限制每個箱子能放幾張地圖、叫大家別蓋大倉庫。但這就像為了省電把冰箱拔掉:LAG 是不見了,玩家的東西也跟著不見了。我一向不信這套——**能用技術克服的,就不該用規則去閹割玩法。**

所以這個 agent 做的事,白話講就一句:

**沒人要看的箱子,別急著拆;沒人動過的箱子,就原封不動地放回去。**

載入時,先把箱子的原始資料**收著**、先不拆;真的有人去開、漏斗去抽、比較器去讀,才當場拆**那一個**。從載入到卸載都沒人碰的,就把當初收著的那包原始 bytes **逐位元組原樣寫回**——完全跳過重新打包。

對玩家來說,箱子裡裝什麼、擺在第幾格,**一模一樣**,你驗證不出任何差別。差別只在伺服器:那一大批「拆了又包、卻根本沒人看」的白工,消失了。

---

## 效能實證(206 層波動拳 → 0%)

vanilla 載入一個放滿地圖畫/唱片的箱子,光把物品從 NBT 解出來,呼叫堆疊就深到 **~206 層**——因為資料是真的巢狀(箱子 → 界伏盒 → 地圖畫 → lore → 不同顏色文字)再乘上 Mojang codec 框架每層疊 15-20 個 frame。最底層那一行只是在 `TextColor.parseColor`(解析 lore 顏色)/ `String.equals`(比對欄位名)。

<p align="center">
<img src="docs/img/callchain-206-before.png" width="360" alt="206 層呼叫鏈(波動拳)">
&nbsp;&nbsp;
<img src="docs/img/improved-after.png" width="440" alt="改善後">
</p>

`.lctest` 同一塊密集容器 chunk、`forceload` churn、跑兩輪:

| Run | 模式 | spark | 容器解碼佔主執行緒 | profile 節點數 | 最深呼叫 |
|---|---|---|---|---|---|
| 1 | vanilla | `WOVkupfiJx` | **62.17%** | 3378 | 200 層 |
| 1 | **agent** | `wGDbUTbZKN` | **0.00%** | 482 | 9 層 |
| 2 | vanilla | `AjXLAdXzTd` | **65.65%** | 4305 | 200 層 |
| 2 | **agent** | `caXFofKSVQ` | **0.00%** | 763 | 36 層 |

整座解碼塔在 agent profile **直接消失**。4 份 spark 原始檔(已靜態存檔避免連結失效)+ 完整 206 行鏈 + 說明:[`docs/spark/`](docs/spark/SUMMARY.md)。

> ⚠️ 62~66% 是「容器解碼**單獨隔離**」的壓力測;真實混合負載下佔比為載入 **~24%** + 卸載 **~11%**(負載最重的節點可達 **~45%**)。省的是解/打包 CPU,不省 I/O / GC。

---

## 怎麼運作

### 白話比喻
像搬家公司本來**每個經過倉庫的箱子都拆開檢查再封回**(連沒人問的也拆)。改成:**沒人要看的別拆;沒動過的原封出貨。**

### 技術機制
注入 NMS 容器類別,加入兩個合成欄位 + 改寫存取點:

| 動作 | 計數器 | 說明 |
|---|---|---|
| **延遲載入** | `stash` | `loadAdditional` 不呼 `ContainerHelper.loadAllItems`,改抓未解碼的原始 `Items` ListTag 暫存、標記 `pending`。**跳過解包。** |
| **存取時物化** | `ensure` | 首次有人呼 `getItems()/getContents()` → 才把暫存的 raw 解進清單(只解這一個)。 |
| **原樣寫回** | `rawSave` | 卸載存檔時若該容器全程沒被碰(`pending`)→ 把原始 bytes 逐位元組寫回。**跳過打包。** |
| **退回 eager** | `eagerLoad` | input 不是 `TagValueInput`(理論上不會)→ 安全退回原本 vanilla 行為。 |

涵蓋型別:`ChestBlockEntity`、`BarrelBlockEntity`、`ShulkerBoxBlockEntity`。
**唯一咽喉 = `getItems()`**:NMS `BaseContainerBlockEntity` 所有容器讀寫(isEmpty/getItem/removeItem/setItem/clearContent/掉落/比較器…)都經它,守一個即覆蓋全部;CraftBukkit 的 `getContents()` 會繞過,額外守。`getContainerSize()` 不經內容(結構性),不守。

---

## 架構(怎麼注入的)

純外掛(plugin)無法覆寫 NMS(Minecraft 伺服器內部程式碼)裡標記 `final`(禁止被子類別覆寫)的方法,所以用 **Java agent + ASM(操作 Java bytecode、能在類別載入當下動態改寫它的工具)注入**:

1. **`LazyContainerAgentMain`**(premain,JVM 啟動時最先跑的進入點):把整個 jar 用 `appendToBootstrapClassLoaderSearch` 掛上 bootstrap classloader(JVM 最底層、所有類別載入器共同的祖先,這樣才能繞過 Paper 把 Minecraft 內部程式碼隔離起來的機制),再註冊 transformer(下面第 2 點的類別改寫器)。
2. **`LazyContainerTransformer`**(執行實際改寫的 ASM 邏輯):
   - 先用 base class 的真實方法簽章辨認 value-io、registry-nbt、legacy Mojang 或 legacy Spigot API,再把對應的 **`LazyContainerTemplate`**(用一般 Java 語法、對著真實 Paper NMS 編譯,而不是手刻 bytecode)splice(接枝:把外來的欄位/方法插進既有類別)進共同父類別。→ **編譯器會幫忙驗證方法簽章對不對,比手寫 bytecode 安全得多。**
   - 在箱子/木桶/界伏盒這三個實際子類別(繼承鏈最底層的類別,術語叫 leaf)的 `getItems/getContents/setItems` 入口插「守門檢查」(guard,判斷這容器還沒被解碼、要不要先補解碼)、把 load/save 裡呼叫 `ContainerHelper` 的地方改成呼叫延遲版邏輯。
3. **`LazyContainerRuntime`**(掛在 bootstrap classloader、純 JDK 沒有依賴任何 Minecraft 類別):shadow(驗證模式)開關 + 計數器。
4. **安全鐵律**:父類別(base/superclass)沒改寫成功,就**完全不動子類別(leaf)** → 整個退回純原版行為,絕不會產生「方法不存在」這類崩潰性錯誤(`NoSuchMethodError`);過程中任何例外 → 回傳原本沒改過的 bytecode。

---

## 為什麼不會掉資料 / 改到區塊資料

**它改的是「什麼時候解包」,不是「箱子存什麼」。硬碟格式從頭到尾沒變。**

- **沒碰的箱子** → 寫回的是載入時讀到的同一份 bytes(逐位元組相同),沒經轉換。
- **被碰的箱子** → 跟 vanilla 一模一樣地解碼、再一模一樣地存回。
- 只動箱子的 `Items`(容器裡的物品清單),不碰地形 / 方塊 / 實體 / 光照 / 其他 BE(BlockEntity,附在方塊上、替它存額外資料的物件,例如告示牌的文字、箱子的內容物)。

已驗證:
- **離線 JVM bytecode 驗證**:注入的 4 個類別全通過 link/verify(JVM 載入類別時檢查 bytecode 合不合法的機制)。
- **多版本真實 Paper 端對端**:26.2、26.1.2、26.1.1、1.21.11、1.21.8、1.20.6、1.19.4 都完成兩次啟動 round-trip;箱子、木桶、界伏盒內容完整,且同一 block entity 重載無 `Items` 後不會殘留舊物品。每版皆為 `stash=5 ensure=2 rawSave=8 eagerLoad=0 shadowMismatch=0`。詳見 [`docs/test-reports/multi-version.md`](docs/test-reports/multi-version.md)。
- **原 Paper 26.2 深度驗證**:含 data-component 與真實世界 56 容器的 shadow 驗證,`shadowMismatch=0`。詳見 [`docs/test-reports/26.2.md`](docs/test-reports/26.2.md)。
- **原 26.2 對抗審查**:涵蓋 8 種失效模式,並修正 byte-identity 小差異;此為改版前實作的歷史報告。
- **原 26.2 Fable 5 二輪審計**:49 agent 對當時程式碼審查,找到並修復 1 個管理員指令才會踩到的邊角漏洞。詳見 [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)。
- **DFU 跨版本**:暫存的原始資料本來就是「DFU(DataFixerUpper,Minecraft 用來把舊版存檔資料自動升級成新版格式的機制)升級後」的版本(DFU 在區塊資料被讀出來的最早期、BE 物件都還沒建立前就跑完了),回寫的自然也是升級後的版本,不會有跨版本相容性問題。

詳見 [`FINDINGS.md`](FINDINGS.md)、[`ADVERSARIAL-REVIEW.md`](ADVERSARIAL-REVIEW.md)、[`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)。

---

## shadow 模式(上線必開的驗證)

`-Dlazycontainer.shadow=true`:每次要寫 raw **之前**,額外把 vanilla 的做法(解開→重打包)算一遍**逐位元組比對**:
- **一樣** → 寫 raw(並得到一筆「快路徑正確」的證據)。
- **不一樣** → 改寫 **vanilla 那份**(安全的),`shadowMismatch++` 並印座標。

→ **開著 shadow,硬碟輸出在數學上不可能跟 vanilla 不同**(零風險驗證)。代價:兩套都做了,**暫時沒加速**。
跑數天 `shadowMismatch=0` + 無玩家回報少東西 → 才關 shadow 換真效能。

### shadowMismatch vs benignReorder(語意感知比對)

純逐位元組比對對「**同一組物品、只是 Items 清單順序不同**」會誤判(常見於外掛產的容器——每個 entry 自帶 `Slot`,清單順序不影響槽位)。所以 v2 起把差異分兩類:

- **`benignReorder`** — raw 與 eager 是**同一組物品+槽位、只差清單順序**(以 multiset 比對確認)→ **安全寫 raw、不算問題**。但仍**偵測並回報**:印 `benign reorder @ <pos> — … NO IMPACT (raw kept)`(前 30 次,之後僅累加避免洗版)。
- **`shadowMismatch`** — 真正的結構差異(物品數量/內容變了,例如槽位越界被丟棄)→ 寫 eager(對齊 vanilla)+ 印座標。

→ 盯 **`shadowMismatch=0`** 即可;`benignReorder` 只是「外掛寫法不同」的無害提示,**不是要修的東西**。`-Dlazycontainer.dump=true` 時兩類都會把 raw/eager 各存一份(`lc-mismatch-N` / `lc-benign-N`)供離線 diff。

---

## 建置

```bash
bash build.sh --prepare  # 首次:下載固定版 Paper NMS、編譯、測試、打包
bash build.sh            # 後續:沿用 nms-lib/,離線重建
```
需要 **JDK 21+、Maven、curl、jq、sha256sum**。`--prepare` 會透過 Paper 官方 Fill API 下載固定 build、驗證 SHA-256,再產生 `nms-lib/`;此目錄不入 git(太大、含 Mojang/Paper 產物)。

流程:① 以 Java 17 bytecode 編出 bootstrap agent → ② `javac` 分別把四套 template 對真實 NMS 編成 Java 21 / 17 bytecode → ③ JUnit 直接拿固定版 Paper class 驗證 base splice、三種 leaf 改寫與失敗原子性 → ④ Shade ASM 並把四份 template class 當純資源放入同一個 jar。執行時 template 不會被 JVM 當一般類別載入,只會由 ASM 讀取與接枝。

---

## 部署

把 jar 放到節點看得到的位置,在 `java` 那行 **`-jar` 前面**插旗標:

```bash
java -Xms8000M -Xmx8000M \
  -javaagent:LazyContainerAgent.jar \
  -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true \
  ... 原本的 -XX 旗標 ... \
  -jar <你的 Paper>.jar nogui
```

開機 log 應出現:
```
[LazyContainer] LazyContainerAgent —— crafted by 廢土貓大 LogoCat · 廢土 · mcfallout.net
[LazyContainer] agent installed (transformer registered) [SHADOW mode]
[LazyContainer] detected Paper ... (layout=...)
[LazyContainer] spliced ... fields + ... methods into ...
[LazyContainer] transformed leaf ...Chest... (load=1 save=1 getItems=1 getContents=1 setItems=1)
[LazyContainer] transformed leaf ...Barrel... (load=1 save=1 getItems=1 getContents=1 setItems=1)
[LazyContainer] transformed leaf ...ShulkerBox... (load=1 save=1 getItems=1 getContents=1 setItems=1)
```

| 旗標 | 作用 |
|---|---|
| `-Dlazycontainer.shadow=true` | **上線必開**。輸出保證等同 vanilla;暫無加速。 |
| `-Dlazycontainer.verbose=true` | 背景 daemon 定期印計數。 |
| `-Dlazycontainer.verbose.ms=8000` | verbose 列印間隔(ms,預設 30000)。 |
| `-Dlazycontainer.dump=true` | mismatch / benign reorder 時把 raw/eager SNBT 各落一檔(`lc-mismatch-N` / `lc-benign-N`,各前 30 次),供離線 diff。 |
| `-Dlazycontainer.dump.dir=<路徑>` | dump 落檔目錄(預設 `.` = 伺服器工作目錄)。 |

**回滾**:刪掉那幾段旗標重啟 → 回 100% vanilla,**不需任何資料遷移**(硬碟格式沒被改過)。

---

## 實測(正式環境,shadow 模式)

在正式環境的 Paper 節點實掛 shadow 模式,觀察到的行為:

- **`shadowMismatch=0`(持續)** → 輸出與 vanilla 逐位元組一致,資料零風險。
- `stash` 持續累積 → 載入時「立刻解包」這件白工確實被攔下(也就是那 45% 的源頭)。
- `ensure` 的高低取決於該節點漏斗/比較器的活躍度:被碰到的箱子會即時物化(分散到各 tick),「完全省掉」的是「從載入到卸載都沒被碰」的那一批(`rawSave`)。

因此最大效益落在「閒置或 churn 中的容器」;最終加速幅度待關閉 shadow 後重抓 spark 對照(見上方「效能實證」)。

---

## 限制與注意

- **益處依賴「箱子沒被碰」**:churn / 閒置儲存(載入→沒人碰→卸載)大勝;**活躍的漏斗/比較器分類倉**會把箱子 ensure 掉,純省比例變小(主要益處變成「把載入尖峰打散」)。姊妹專案 **ChunkForceManager** 從「別讓 chunk 反覆載卸」那端互補。
- **版本敏感**:只支援文件上列的 Paper 系列與其相符 NMS 結構。即使版本名稱相同,Paper 未來 build 若改動內部簽章也可能被拒絕;請先在 shadow 測試服確認啟動 log 顯示正確 layout 與三個 `transformed leaf`。
- 不影響:loot table 箱子(走另一條路徑,正交)、雙箱 CompoundContainer(委派到子箱 getItems,已守)、執行緒(載入/tick/卸載皆主執行緒)。

---

## 檔案地圖

```
src/main/java/io/github/kuohsuanlo/lazycontainer/
  LazyContainerAgentMain.java   premain / bootstrap 掛載
  LazyContainerRuntime.java     bootstrap 純 JDK:shadow 開關 + 計數器
  NmsTarget.java                四種 NMS 結構、簽章與 template 對照
  LazyContainerTransformer.java ASM:splice base + 改寫 leaf
templates/{value-io,registry-nbt,legacy-*}/...   四套 javac 驗證的 splice 來源
tools/prepare-paper-nms.sh      下載、驗證並 patch 固定版 Paper NMS
tools/runtime-test.sh           真實 Paper 兩次開機 round-trip 測試
tools/scan_containers.py        掃 region 檔找箱子最密的 chunk(找「載入最貴」的地點)
build.sh  pom.xml  nms-lib/(不入 git)
FINDINGS.md           反編譯確認的事實 + 設計定案 + 風險分析
ADVERSARIAL-REVIEW.md 原 26.2 對抗審查報告(8 失效模式,12 agent)
FABLE5-AUDIT.md        原 26.2 Fable 5 二輪對抗審計(49 agent)
TESTING.md            怎麼自己測(自動 round-trip / 手動玩測 / 真實世界副本驗 shadow)
```

延伸閱讀:[`FINDINGS.md`](FINDINGS.md)(技術全貌)· [`TESTING.md`](TESTING.md)(自測)· [`ADVERSARIAL-REVIEW.md`](ADVERSARIAL-REVIEW.md)(審查)· [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)(Fable 5 審計)。
