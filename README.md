# 輕閱 NovelReader

Windows + Android 雙平台小說閱讀器（Compose Multiplatform）。專為中文本地小說設計：

- **編碼自動偵測**：UTF-8 / UTF-16 / GBK / Big5 自動判斷，永不亂碼
- **TXT / EPUB 匯入**：EPUB 會解析目錄、正文與封面；桌面版可從 zip / rar / 7z 批次匯入
- **章節目錄**：匯入時自動解析「第X章」、序章、番外、Chapter N 等標題，點目錄直接跳轉
- **大檔穩定**：20MB+ 的 txt 也不當機（只載入當前章節，全書不進記憶體，LRU 快取 3 章）
- **閱讀設定**：字體（Windows 可用所有系統字體）、字級、行距、邊距、白晝/夜間/護眼主題
- **書架管理**：書名搜尋、排序、標籤、閱讀進度、相似書名提示
- **閱讀輔助**：書籤、全文搜尋、簡繁轉換、文字選取、鍵盤翻頁、左右滑動翻頁
- **進度同步**：可選 Google Drive / Autosync 等同步資料夾，進度與書籤寫入 `_novelreader`
- **系統朗讀**：Windows OneCore 與 Android 系統 TTS 逐段朗讀，支援語速、暫停/繼續、上一段/下一段與自動下一章
- **Android 操作**：九宮格可自訂前頁/後頁/關閉，音量上鍵前翻、下鍵後翻；朗讀時顯示通知，可直接停止朗讀

## 系統支援

- Windows 簡體中文與繁體中文操作系統目前都已驗證可正常啟動、匯入與閱讀
- Windows 朗讀可使用從 Windows 輔助功能下載的 OneCore 自然語音
- Android 8.0 以上支援 txt / epub 匯入、閱讀設定、進度保存與系統 TTS 朗讀

## 使用方式

- **書架**：右下角匯入 txt / epub；桌面版也可匯入 zip / rar / 7z。長按（桌面為長點）書籍卡片可編輯標籤或移除
- **閱讀畫面**：點畫面中央叫出/收起工具列；工具列有目錄、上下章、書籤、搜尋、朗讀、字體設定（Aa）與更多選項
- **朗讀**：點「朗讀」後可暫停/繼續及調整語速；讀完一章會自動接下一章。桌面版另可切換語音與上下段
- **Android 翻頁**：設定中的「觸控翻頁（九宮格）」可逐格循環設定關閉、前頁或後頁；也可左右滑動或使用音量鍵翻頁
- **Android 朗讀**：朗讀時會顯示系統通知，Android 13+ 首次使用需允許通知權限
- **桌面快捷鍵**：`←`/`→`、`↑`/`↓`、`PgUp`/`PgDn`/`空白鍵` 翻頁或換章，`Ctrl+F` 搜尋，`Esc` 回書架

## 本機建置（Windows）

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$gradle = 'E:\android-tools\gradle-8.11.1\bin\gradle.bat'

& $gradle :composeApp:run              # 直接執行桌面版
& $gradle :composeApp:assembleRelease  # Android 正式 APK
& $gradle :composeApp:desktopTest      # 單元測試
& $gradle :composeApp:createReleaseDistributable  # Windows 可攜版（免安裝資料夾）
```

- 桌面版輸出：`composeApp\build\compose\binaries\main-release\app\NovelReader\NovelReader.exe`
- APK 輸出：`composeApp\build\outputs\apk\release\composeApp-release.apk`

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
- Windows 朗讀使用 OneCore；Android 朗讀使用原生 `android.speech.tts.TextToSpeech`
- 資料目錄：Windows `%APPDATA%\NovelReader`，Android `filesDir`
