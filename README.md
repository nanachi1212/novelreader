# 輕閱 NovelReader

Windows + Android 雙平台小說閱讀器（Compose Multiplatform）。專為中文本地小說設計：

- **編碼自動偵測**：UTF-8 / UTF-16 / GBK / Big5 自動判斷，永不亂碼
- **TXT / EPUB 匯入**：EPUB 會解析目錄、正文與封面；桌面版可從 zip / rar / 7z 批次匯入
- **章節目錄**：匯入時自動解析「第X章」、序章、番外、Chapter N 等標題，點目錄直接跳轉
- **大檔穩定**：20MB+ 的 txt 也不當機（只載入當前章節，全書不進記憶體，LRU 快取 3 章）
- **閱讀設定**：字體（Windows 可用所有系統字體）、字級、行距、邊距、白晝/夜間/護眼主題
- **書架管理**：書名搜尋、排序、標籤、閱讀進度、相似書名提示
- **閱讀輔助**：書籤、全文搜尋、簡繁轉換、文字選取、鍵盤翻頁
- **進度同步**：可選 Google Drive / Autosync 等同步資料夾，進度與書籤寫入 `_novelreader`
- **Windows 朗讀**：使用系統 SAPI 語音逐段朗讀，支援語速、語音選擇、暫停/繼續、上下段與自動下一章

目前 Android 版尚未接入 TTS 引擎；朗讀入口只會在平台提供語音引擎時顯示。

## 系統支援

- Windows 簡體中文與繁體中文操作系統目前都已驗證可正常啟動、匯入與閱讀
- Windows 朗讀依賴系統已安裝的 SAPI 語音；中文語音可在 Windows「設定 → 時間與語言 → 語音」新增
- Android 版支援檔案匯入、閱讀、搜尋、書籤與同步；TTS 朗讀尚未接入

## 使用方式

- **書架**：右下角匯入 txt / epub；桌面版也可匯入 zip / rar / 7z。長按（桌面為長點）書籍卡片可編輯標籤或移除
- **閱讀畫面**：點畫面中央叫出/收起工具列；工具列有目錄、上下章、書籤、搜尋、朗讀、字體設定（Aa）與更多選項
- **朗讀**：Windows 版點「朗讀」後可暫停/繼續、上下段、調整語速與切換語音；讀完一章會自動接下一章
- **桌面快捷鍵**：`←`/`→`、`↑`/`↓`、`PgUp`/`PgDn`/`空白鍵` 翻頁或換章，`Ctrl+F` 搜尋，`Esc` 回書架

## 本機建置（Windows）

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$gradle = 'E:\android-tools\gradle-8.11.1\bin\gradle.bat'

& $gradle :composeApp:run              # 直接執行桌面版
& $gradle :composeApp:assembleDebug    # Android debug APK
& $gradle :composeApp:desktopTest      # 單元測試
& $gradle :composeApp:createReleaseDistributable  # Windows 可攜版（免安裝資料夾）
```

- 桌面版輸出：`composeApp\build\compose\binaries\main-release\app\NovelReader\NovelReader.exe`
- APK 輸出：`composeApp\build\outputs\apk\debug\composeApp-debug.apk`

## 發版

推 tag（`v*`）後 GitHub Actions 會自動建置簽章 APK 與 Windows zip 掛到 Release：

```powershell
git tag v0.1.0
git push origin main --tags
```

> Windows 版未做程式碼簽章，第一次執行 SmartScreen 會警告：
> 點「其他資訊」→「仍要執行」即可。

## 技術備忘

- Kotlin 2.2.20 + Compose Multiplatform 1.9.0 + AGP 8.7.3（Gradle 8.11.1 / JDK 17）
- 共用程式碼在 `composeApp/src/jvmShared/kotlin`，以 srcDir 同時掛進 androidMain 與
  desktopMain（兩邊都是 JVM，可直接用 java.io；避開中間 source set 不能用 JDK 的限制）
- 匯入時把原始檔轉碼成 UTF-8 的 `content.txt` 並記錄章節位元組偏移，
  閱讀時 `RandomAccessFile.seek` 只讀當前章節（LRU 快取 3 章）
- 每本書的進度與書籤存在本機 `book.json`；同步資料夾只寫 sidecar JSON，不碰原始小說檔
- Windows 朗讀由 `SapiTtsEngine` 啟動長駐 PowerShell worker，透過 System.Speech.SpeechSynthesizer 控制 SAPI
- 資料目錄：Windows `%APPDATA%\NovelReader`，Android `filesDir`
