# 輕閱 NovelReader｜DEVELOPMENT

本文件是 `E:\\Codex project\\novelreader` 的維護文件。完整功能清單與版本資訊請先查看同一資料夾的 `README.md`。

## 建議流程

1. 建立獨立開發環境，不直接污染系統全域依賴。
2. 先執行現有測試或最小啟動。
3. 小幅修改並保留 diff。
4. 重新執行測試與手動 smoke test。
5. 只提交原始碼與必要設定，不提交快取、模型、密鑰或個人資料。

## 本專案環境

Kotlin 2.2；Compose Multiplatform；Gradle 8.11；JDK 17

## 啟動／驗證

設定 JDK 17 的 `JAVA_HOME` 後執行 `.\gradlew.bat :composeApp:run`；測試使用 `.\gradlew.bat desktopTest`。Wrapper 已鎖定 Gradle 8.11.1，不需要另外安裝 Gradle。

若實際版本與 README 不同，以鎖定檔、build 設定與當前錯誤訊息為準。
