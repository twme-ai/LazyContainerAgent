# LazyContainerAgent 多版本驗證報告

最終矩陣測試日期:2026-07-26 UTC

## 結論

同一份 `LazyContainerAgent.jar` 已在下列 Paper 版本完成真實伺服器兩次開機 round-trip。所有版本都成功自動選擇對應 NMS layout,三種容器內容完整,且 shadow 輸出無差異。

Paper 官方沒有名為 `26.1` 的獨立版本;目前發布的是 `26.1.1` 與 `26.1.2`,兩者均已測試。

| Minecraft / Paper build | Java | 偵測 layout | stash | ensure | rawSave | eagerLoad | shadowMismatch | 結果 |
|---|---:|---|---:|---:|---:|---:|---:|---|
| 26.2 build 65 (`fc9375a`) | 25 | `value-io` | 5 | 2 | 8 | 0 | 0 | PASS |
| 26.1.2 build 74 (`e4e17fc`) | 25 | `value-io` | 5 | 2 | 8 | 0 | 0 | PASS |
| 26.1.1 build 29 (`77c0866`) | 25 | `value-io` | 5 | 2 | 8 | 0 | 0 | PASS |
| 1.21.11 build 132 (`c5eb079`) | 21 | `value-io` | 5 | 2 | 8 | 0 | 0 | PASS |
| 1.21.8 build 60 (`29c8822`) | 21 | `value-io` | 5 | 2 | 8 | 0 | 0 | PASS |
| 1.20.6 build 151 (`a4f0f5c`) | 21 | `registry-nbt` | 5 | 2 | 8 | 0 | 0 | PASS |
| 1.19.4 build 550 (`483368e`) | 17 | `legacy-spigot` | 5 | 2 | 8 | 0 | 0 | PASS |

`1.19.4` 表格列使用一般 Paperclip 啟動時的預設 Spigot-mapped server artifact。另以 `server:mojang` artifact 驗證 `legacy-mojang` template 的注入與 JVM 啟動,沒有 verifier 或 linkage 錯誤。

## Round-trip 流程

[`tools/runtime-test.sh`](../../tools/runtime-test.sh) 對每個版本執行相同程序:

1. 不掛 agent 啟動 Paper,在強制載入 chunk 建立兩個 chest、barrel、shulker box。
2. 分別寫入 diamond x42、gold ingot x17、emerald x9 與 reload 測試物品,儲存並正常關服。
3. 掛上 shadow agent 重新啟動,等待四個容器走 lazy stash。
4. 先 `save-all flush` 驗證 untouched raw write-back。
5. 將 netherite ingot x3 寫入 chest 第二格,觸發 materialize-on-access,再次儲存。
6. 將另一個 chest 物化後執行 `data remove block ... Items`,驗證同實例 reload 會清空舊 live list。
7. 用 `data get block ... Items` 讀回容器,再正常關服。

PASS 條件同時檢查計數器、四種物品、正常關服,並拒絕任何 `VerifyError`、`NoSuchMethodError`、`NoSuchFieldError`、`IllegalAccessError` 或 transformer failure。

## 建置期驗證

[`src/test/java/io/github/kuohsuanlo/lazycontainer/RealNmsTransformerTest.java`](../../src/test/java/io/github/kuohsuanlo/lazycontainer/RealNmsTransformerTest.java) 直接讀取固定版真實 Paper NMS class,覆蓋四個 layout:

- `value-io`:1.21.8 build 60;
- `registry-nbt`:1.20.6 build 151;
- `legacy-mojang`:1.19.4 build 550 `server:mojang`;
- `legacy-spigot`:1.19.4 build 550 `server:default`。

每個 layout 都驗證 base splice、Chest/Barrel/ShulkerBox 三個 leaf 的 load/save bridge 與存取 guard。另刻意破壞 save helper owner,確認 transformer 會拒絕整個 leaf,而不是產生只改一半的 bytecode。

可用 `bash build.sh --prepare` 從 Paper 官方來源重建輸入並重跑測試。
