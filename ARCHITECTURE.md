# 輕閱 NovelReader｜ARCHITECTURE

本文件是 `E:\\Codex project\\novelreader` 的維護文件。完整功能清單與版本資訊請先查看同一資料夾的 `README.md`。

## 分層概念

- **入口層**：啟動腳本、GUI 或主程式。
- **核心層**：Kotlin 2.2；Compose Multiplatform；Gradle 8.11；JDK 17 所提供的主要處理流程。
- **資料層**：設定、快取、輸入與輸出檔案。
- **整合層**：外部工具、模型、平台 API 或打包工具。

## 修改原則

先定位入口，再追到實際處理函式；保持資料格式相容；每次只改一個責任範圍並做最小回歸測試。
