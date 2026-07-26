# 測試 LazyContainerAgent

## 1. 可重現建置與真實 NMS 位元碼測試

第一次建置需要 JDK 21+、Maven、curl、jq、sha256sum 與網路:

```bash
bash build.sh --prepare
```

`--prepare` 會從 Paper 官方 Fill API 下載固定 build、驗證 SHA-256,再由 Paperclip 產生四種編譯輸入:

| profile | 代表 Paper | API |
|---|---|---|
| `value-io` | 1.21.8 build 60 | `ValueInput` / `ValueOutput` |
| `registry-nbt` | 1.20.6 build 151 | registry-aware `CompoundTag` |
| `legacy-mojang` | 1.19.4 build 550 | Mojang mapping |
| `legacy-spigot` | 1.19.4 build 550 | Paper 預設 Spigot mapping |

建置過程會對真實 NMS class 執行 JUnit 測試,確認每種 API 都能:

- splice 共同 base class;
- 完整改寫 chest、barrel、shulker box 三個 leaf;
- 插入 `getItems`、Paper `getContents`、`setItems` guard;
- 將 load/save helper 改到 lazy bridge;
- 在任何攔截點缺失時拒絕整個 leaf,不留下部分改寫。

後續已有 `nms-lib/` 時可直接執行 `bash build.sh`。產物是 `target/LazyContainerAgent.jar`。

## 2. 真實 Paper 兩次開機 round-trip

`tools/runtime-test.sh` 會建立隔離的 flat world,執行兩次 Paper 開機:

1. vanilla 開機,建立兩個 chest、barrel、shulker box 並放入測試物品;
2. shadow agent 開機,先走未碰容器 raw save,再修改 chest 觸發 ensure,存檔並讀回三種容器;
3. 將第四個 chest 物化後移除整個 `Items`,觸發同一 block entity reload,確認 live list 已像 vanilla 一樣清空。

用法:

```bash
bash tools/runtime-test.sh \
  <version> <java-bin> <paperclip.jar> <prepared-paper-work-dir>
```

以 `--prepare` 產生的 1.21.8 輸入為例:

```bash
bash tools/runtime-test.sh 1.21.8 \
  "$JAVA_HOME/bin/java" \
  nms-lib/value-io/paperclip.jar \
  nms-lib/value-io/work
```

測試只在以下條件全部成立時回報 `PASS`:

- `stash >= 5`、`ensure >= 2`、`rawSave >= 3`;
- `eagerLoad=0`、`shadowMismatch=0`;
- 沒有 verifier / linkage / transformer 錯誤;
- diamond、netherite ingot、gold ingot、emerald 都能從存檔讀回。
- reload 測試箱中的 redstone 與 coal 不會在移除 `Items` 後重新出現。

完整實測版本與結果見 [`docs/test-reports/multi-version.md`](docs/test-reports/multi-version.md)。

## 3. 上線前 canary

先備份世界,在一個測試節點的 `java` 後、`-jar` 前加入:

```text
-javaagent:/abs/path/LazyContainerAgent.jar
-Dlazycontainer.shadow=true
-Dlazycontainer.verbose=true
```

啟動 log 應顯示正確的 `detected Paper ... (layout=...)`、一行 `spliced ...` 與三個 `transformed leaf`。若出現 `unsupported NMS layout` 或 `incompatible leaf`,該版本/build 不應啟用。

在測試世界操作箱子、木桶、界伏盒、漏斗、比較器與裝滿物品的界伏盒,並跨重啟檢查內容。先維持 shadow 數天,只有在 `shadowMismatch=0` 且沒有資料問題後才移除 `-Dlazycontainer.shadow=true` 取得效能。

回滾只需移除 `-javaagent` 與相關 `-Dlazycontainer.*` 參數後重啟;磁碟格式從未改變,不需要資料遷移。
