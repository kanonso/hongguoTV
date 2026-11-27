# hongguoTV 簽名恢復與 APK 候選發布流程

目標倉庫：kanonso/hongguoTV（唯一正式發布倉庫）

## 目前狀態（2026-11-27）

- kanonso/hongguoTV 目前沒有 GitHub Releases。
- 以下四個原簽名 Actions Secrets 尚未確認存在：
  - HONGGUOTV_KEYSTORE_B64
  - HONGGUOTV_STORE_PASSWORD
  - HONGGUOTV_KEY_ALIAS
  - HONGGUOTV_KEY_PASSWORD
- 已知原正式簽名憑證 SHA-256：
  3579f7a8a91bbbc44688db60fb16a5561921784af23fa92f39b849086d76ca98
- 正式包名：com.hongguotv.nativeapp。
- Debug 包名：com.hongguotv.nativeapp.debug，可與正式包並存。

在原 Keystore 恢復前，不會以新金鑰簽署相同正式包名，也不會建立冒充可覆蓋舊版的正式 APK。

## 1. 原簽名恢復步驟

優先搜尋以下位置（只在你自己擁有或有權存取的設備／備份中查找）：

1. 舊開發電腦、外接硬碟、NAS、雲端硬碟和密碼管理器。
2. 專案舊工作目錄中的 kotlin-tv/keystore.properties。
3. 曾經使用的 .jks、.keystore 檔案及其密碼、alias。
4. 舊電腦的 Actions／CI 設定備份；GitHub Secrets 不能從 GitHub 讀回明文。

如果找到 Keystore，在本機確認憑證：

    keytool -list -v -keystore /path/to/original.jks -alias YOUR_ALIAS

憑證 SHA-256 必須與上面列出的原憑證一致。不要把 Keystore 或密碼提交到 Git，也不要貼到聊天。

如需確認電視上現有 APK 的簽名，可用 ADB 取出已安裝 APK（或從原安裝 Release 重新下載）：

    adb shell pm path com.hongguotv.nativeapp
    adb pull /data/app/RETURNED_PATH/base.apk installed-hongguotv.apk
    $ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs installed-hongguotv.apk

這只能讀出公開憑證，不能從 APK 還原私鑰或 Keystore 密碼。若安裝版本的憑證不同，必須先釐清實際安裝來源與原簽名，不可強行更新。

恢復成功後，到 Settings → Secrets and variables → Actions 設定四個上述 Secret。將 JKS 以 Base64 文字存入 HONGGUOTV_KEYSTORE_B64，其餘三項分別填入原密碼與 alias。Secret 名稱可檢查，但已存值不能由 GitHub 顯示回來。

## 2. Debug 候選 APK（現在即可執行，不需要原簽名）

1. 開啟 GitHub → Actions → Build Android TV debug candidate。
2. 選 Run workflow，使用目前測試分支或合併後的 main。
3. 等待核心測試、Android 單元測試、Lint、兩種 API 建置及 ABI／FFmpeg 驗證完成。
4. 在該次 Actions 執行頁面的 Artifacts 下載 hongguotv-debug-candidate-...。
5. 解壓後按設備選擇：
   - *-debug-android5.apk：Android 5 / API 21–22。
   - *-debug-android6.apk：Android 6+ 通用包。
   - *-debug-android6-arm32-v7a.apk：Android 6+ ARM32。
   - *-debug-android6-arm64-v8a.apk：Android 6+ ARM64。

Debug APK 使用 .debug application ID，應可與原正式 App 並存，且不使用正式簽名 Secret。Debug 資料與正式 App 資料分離。每次 Actions 執行可能使用新的臨時 debug 憑證；換一輪候選包時，必要時只移除舊的 .debug App，再安裝新包。不要卸載正式 App。

Debug Artifact 保留 30 天；它不是 GitHub Release，也不會被正式版自動更新器選中。

## 3. 正式簽名 Pre-release（找回原 Keystore 後）

1. 先合併已完成整合與版本遞增的 PR；versionCode 必須高於設備上安裝的版本。
2. 確認四個原簽名 Secret 已設定，並再次核對 Keystore 憑證 SHA-256。
3. GitHub → Actions → Build and publish signed Android TV pre-release candidate → Run workflow，選 main。
4. 工作流程會執行所有測試、簽名建置四種 APK、比對原簽名指紋及 ABI，再建立 kanonso 的 GitHub Pre-release。
5. 到 https://github.com/kanonso/hongguoTV/releases 下載 APK，在 Android TV 直接覆蓋測試。原簽名正確時不應要求先卸載。
6. 驗收原有功能、登入／收藏／歷史、播放、取源、下載器、遙控器及安裝更新行為。

Pre-release 不會被 GitHub 的 latest-release 更新端點視為正式最新版，因此不會自動推送給一般使用者。

## 4. 實機通過後批准正式版

先在 GitHub Settings → Environments 建立 production，將自己設為 Required reviewer。若只有一位審核者，不要啟用禁止發起者自我審核的規則。

實機測試成功後：

1. Actions → Approve and promote tested Android TV candidate。
2. Run workflow，輸入已驗收的 Pre-release tag。
3. GitHub 先要求 production 環境審核；你批准部署後，腳本會重新驗證候選 Release 的七個附件及 SHA-256。
4. 驗證通過後，只把同一個 Pre-release 改為正式 Release／Latest；不重新編譯、不替換 APK。

## 5. 找不到原 Keystore 時

停止同包名正式更新流程。可繼續使用 Debug APK 進行功能測試，但它不能作為正式升級包。

若原私鑰確實無法從原持有人或備份恢復，安全的替代方案是規劃新的 application ID（例如獨立新 App），並設計明確的使用者資料匯出／遷移方案。不要用新憑證簽署 com.hongguotv.nativeapp 再假稱能覆蓋舊版本；不要要求使用者先卸載舊 App。

## 6. 安全原則

- 正式簽名材料只放 GitHub Actions Secrets 或安全本機儲存，不進入 Git、Artifact 或 Source ZIP。
- Fork／Pull Request 不取得正式簽名 Secret。
- Debug Artifact 與正式 Release 分開。
- Pre-release 必須先完成實機測試；只有經 production 人工批准才成為 Latest。
- 正式候選版和正式版使用同一組已驗證附件及 SHA-256。
