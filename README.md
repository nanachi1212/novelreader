# 輕閱 NovelReader

Windows + Android 雙平台小說閱讀器（Compose Multiplatform）。專為中文本地小說設計：

- **編碼自動偵測**：UTF-8 / UTF-16 / GBK / Big5 自動判斷，永不亂碼
- **TXT / EPUB 匯入**：EPUB 會解析目錄、正文與封面；桌面版可從 zip / rar / 7z 批次匯入
- **章節目錄**：匯入時自動解析「第X章」、序章、番外、Chapter N 等標題，點目錄直接跳轉
- **大檔穩定**：20MB+ 的 txt 也不當機（只載入當前章節，全書不進記憶體，LRU 快取 3 章）
- **閱讀設定**：字體（Windows 可用所有系統字體）、字級、行距、邊距、連續滾動/分頁、直向/橫向、白晝/夜間/護眼主題
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
- **匯入資料夾（桌面）**：右下角「匯入資料夾」會連同子資料夾一起掃描 txt / epub，勾選後批次匯入；
  第一層子資料夾名（例如 `小說\01武俠小說\`）會自動變成該書的標籤，書架上點標籤即可按資料夾分類瀏覽
- **批次移除**：書架右上角垃圾桶圖示可勾選多本一次移除（只移除書架紀錄，不刪原始檔案）
- **閱讀畫面**：點畫面中央叫出/收起工具列；工具列有目錄、上下章、書籤、搜尋、朗讀、字體設定（Aa）與更多選項
- **朗讀**：點「朗讀」後可暫停/繼續及調整語速；讀完一章會自動接下一章。桌面版另可切換語音與上下段
- **Android 翻頁**：設定中的「觸控翻頁（九宮格）」可逐格循環設定關閉、前頁或後頁；也可左右滑動或使用音量鍵翻頁
- **閱讀模式**：在 Aa 設定中切換連續滾動或分頁模式，也可切換直向或橫向閱讀方向
- **Android 朗讀**：朗讀時會顯示系統通知，Android 13+ 首次使用需允許通知權限
- **桌面快捷鍵**：`←`/`→`、`↑`/`↓`、`PgUp`/`PgDn`/`空白鍵` 翻頁或換章，`Ctrl+F` 搜尋，`Esc` 回書架

## 本機建置（Windows）

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'

.\gradlew.bat :composeApp:run              # 直接執行桌面版
.\gradlew.bat :composeApp:assembleRelease  # Android 正式 APK
.\gradlew.bat :composeApp:desktopTest      # 單元測試
.\gradlew.bat :composeApp:createReleaseDistributable  # Windows 可攜版（免安裝資料夾）
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

- Kotlin 2.2.21 + Compose Multiplatform 1.9.0 + AGP 8.10.1（Gradle 8.11.1 / JDK 17）
- 共用程式碼在 `composeApp/src/jvmShared/kotlin`，以 srcDir 同時掛進 androidMain 與
  desktopMain（兩邊都是 JVM，可直接用 java.io；避開中間 source set 不能用 JDK 的限制）
- 匯入時把原始檔轉碼成 UTF-8 的 `content.txt` 並記錄章節位元組偏移，
  閱讀時 `RandomAccessFile.seek` 只讀當前章節（LRU 快取 3 章）
- 每本書的進度與書籤存在本機 `book.json`；同步資料夾只寫 sidecar JSON，不碰原始小說檔
- Windows 朗讀使用 OneCore；Android 朗讀使用原生 `android.speech.tts.TextToSpeech`
- 資料目錄：Windows `%APPDATA%\NovelReader`，Android `filesDir`。排錯或手動修改前先備份這個資料夾；
  `library.json` / `settings.json` / `book.json` 損壞時會自動從同名 `.bak` 恢復，損壞副本保留為 `.corrupt`
- 大型 TXT 不要改成整本載入記憶體（目前只載入當前章節）

## 技術債：AGP 9

`composeApp` 同時是 Kotlin Multiplatform 共用模組與 Android application。AGP 9 不再允許
`org.jetbrains.kotlin.multiplatform` 與 `com.android.application` 共用同一 Gradle 模組，不能只改版本號。
升級時須拆出獨立 Android app module、共用模組改用 `com.android.kotlin.multiplatform.library`，
再一起升 Gradle 9.1+、Compose Multiplatform 1.10+ 與 compileSdk 36.1。拆分前保留 AGP 8.10.1。
