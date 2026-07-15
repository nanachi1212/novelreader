# 輕閱 NovelReader

Windows + Android 雙平台小說閱讀器（Compose Multiplatform）。專為中文 txt 設計：

- **編碼自動偵測**：UTF-8 / UTF-16 / GBK / Big5 自動判斷，永不亂碼
- **章節目錄**：匯入時自動解析「第X章」等標題，點目錄直接跳轉
- **大檔穩定**：20MB+ 的 txt 也不當機（只載入當前章節，全書不進記憶體）
- **閱讀設定**：字體（Windows 可用所有系統字體）、字級、行距、邊距、白晝/夜間/護眼主題
- 閱讀進度自動記憶，重開直接回到上次位置

規劃中：進度同步（Google Drive 同步資料夾）、書籤、全文搜尋、簡繁轉換、EPUB。

## 使用方式

- **閱讀畫面**：點畫面中央叫出/收起工具列；工具列有目錄、上下章、字體設定（Aa）
- **桌面快捷鍵**：`←`/`→` 上下章、`PgUp`/`PgDn`/`空白鍵` 翻頁、`Esc` 回書架
- **書架**：長按（桌面為長點）書籍卡片可移除

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
- 資料目錄：Windows `%APPDATA%\NovelReader`，Android `filesDir`
