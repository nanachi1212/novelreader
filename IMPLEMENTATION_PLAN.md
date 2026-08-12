# NovelReader 完整化 Implementation Plan

## 現況

目前已具備書庫、最近閱讀排序、閱讀進度、章節目錄、全文搜尋、書籤、字體／字級／行距、深色與護眼主題、自動保存、TXT／EPUB／壓縮檔匯入、編碼偵測、桌面快捷鍵、Android 觸控／音量鍵與 TTS。

## 本次重點

1. **可靠保存**：JSON 採暫存檔、備份檔與原子替換；主檔損壞時自動讀取備份。
2. **啟動恢復**：檢查書庫紀錄與實體內容／章節索引是否一致，移除中斷匯入造成的殘缺項目。
3. **匯入交易**：新書匯入失敗時清除半成品；既有書重新轉碼失敗時保留舊資料。
4. **驗證**：新增資料恢復與匯入失敗測試，執行 desktopTest、Android assembleRelease 與 Windows portable build。

## 模組與介面

- `AppStores` 是持久化模組，介面維持 `load*`／`save*`／`repairLibrary`，備份與恢復細節藏在 implementation。
- `BookRepository.import` 是匯入模組介面，保證成功後書庫與內容一致，失敗不留下新書半成品。
- UI 只消費成功、進度或可讀錯誤，不直接處理檔案修復。

## 完成標準

- 損壞的 `library.json`、`settings.json` 或 `book.json` 可由 `.bak` 恢復。
- 中斷的新書匯入不留下可見或孤立的書籍資料夾。
- 啟動時書庫不包含缺少 `content.txt` 或 `chapters.json` 的項目。
- 現有閱讀功能測試保持通過，且能產出 Windows 可攜版。
