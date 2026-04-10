# プロジェクト運用メモ

このファイルは、このプロジェクトで新しいスレッドを開始したときに前提として引き継ぐための運用ルールです。

## 1. 基本方針

- Windows 上の Android Studio / 実機実行を正とする
- 共通設定は Windows で破綻しない形を優先する
- WSL 固有のパスや `/tmp` 依存を共有 Gradle 設定へ直書きしない
- WSL からのビルドは、必ずコマンドライン引数で上書きする

## 2. SDK とビルド環境

- 共有 Android SDK は `/mnt/d/project/DevelopmentKit/AndroidStudioCLforLinux`
- `local.properties` の `sdk.dir` は信用しない
- WSL では `ANDROID_HOME=/mnt/d/project/DevelopmentKit/AndroidStudioCLforLinux` を使う
- Windows 側で `local.properties` が再生成されても、WSL ビルドが通るなら編集しない

## 3. WSL での Gradle 実行ルール

WSL では、共有設定を汚さずに次を必ず付与する。

- `ANDROID_HOME`
- `GRADLE_USER_HOME`
- `--project-cache-dir`
- `-PcodexRootBuildDir=...`
- `-PcodexAppBuildDir=...`
- `-Dkotlin.compiler.execution.strategy=in-process`
- `-Pkotlin.incremental=false`

推奨値:

- `GRADLE_USER_HOME=/home/shou/.gradle-codex`
- `--project-cache-dir /tmp/wifi_volume/project-cache`
- `-PcodexRootBuildDir=/tmp/wifi_volume/root-build`
- `-PcodexAppBuildDir=/tmp/wifi_volume/app-build`

既知の安定コマンド:

```bash
cd /mnt/d/project/android
ANDROID_HOME=/mnt/d/project/DevelopmentKit/AndroidStudioCLforLinux \
GRADLE_USER_HOME=/home/shou/.gradle-codex ./gradlew testDebugUnitTest \
  --no-daemon \
  --console=plain \
  --project-cache-dir /tmp/wifi_volume/project-cache \
  -PcodexRootBuildDir=/tmp/wifi_volume/root-build \
  -PcodexAppBuildDir=/tmp/wifi_volume/app-build \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

## 4. 現在のテスト設計

このプロジェクトのテストは、`WSL` と `Windows` で責務を分ける。

```text
+----------------------------------+
| WSL                              |
| - testDebugUnitTest              |
| - Robolectric                    |
| - model / usecase / codec        |
+----------------+-----------------+
                 |
                 v
+----------------------------------+
| Windows 実機                     |
| - connectedE2eAndroidTest        |
| - MainActivity / Service / 通知  |
| - permission / lifecycle         |
+----------------------------------+
```

### 4-1. WSL 側

- `scripts/test-wsl-unit.sh` を標準入口にする
- 既定タスクは `testDebugUnitTest`
- Pure JVM と Robolectric をここで回す

実行例:

```bash
bash scripts/test-wsl-unit.sh
```

### 4-2. Windows 側

- Windows は実機中心で運用する
- 標準入口は `scripts/test-windows-instrumented.ps1`
- 既定タスクは `connectedE2eAndroidTest`
- ログは UTF-8 で `test-logs/windows` に保存される

実行例:

```powershell
.\scripts\test-windows-instrumented.ps1
```

## 5. 実機テストの分離方針

通常利用データを保護するため、instrumented test は本番アプリとは別アプリ領域で実行する。

- 通常利用アプリ
  - `applicationId = com.example.wifi_volume`
- 実機テスト用アプリ
  - buildType `e2e`
  - `applicationIdSuffix = ".e2e"`
  - 実体は `com.example.wifi_volume.e2e`

この方針により:

- `assembleDebug` や通常の実行は既存アプリへ上書きされる
- `connectedE2eAndroidTest` は別アプリ領域を使う
- 通常利用中の `DataStore` を壊さない

注意:

- `DataStore` 名を変えて隔離する方針は採用しない
- 同じ `applicationId` のままファイル退避で `DataStore` を空化する方針も採用しない
- `Android Test Orchestrator` と `clearPackageData=true` を前提とする

## 6. adb とインストール運用

通常利用アプリを既存へ上書きする:

```powershell
.\gradlew.bat assembleDebug --no-daemon --console=plain
adb -s <device-id> install -r .\app\build\outputs\apk\debug\app-debug.apk
```

テスト用アプリを別領域へ入れる:

```powershell
.\gradlew.bat assembleE2e --no-daemon --console=plain
adb -s <device-id> install -r .\app\build\outputs\apk\e2e\app-e2e.apk
```

実機テストは通常、手動 install ではなくスクリプトで流す:

```powershell
.\scripts\test-windows-instrumented.ps1
```

## 7. テストを触るときの注意

- `MainActivity` の instrumented test は、本番権限要求や監視サービス自動起動を抑制する extra を使って起動している
- 実機テストは UI が速く動くので、視認したい場合は待機時間を調整する
- Windows 側の最新結果確認は `test-logs/windows/*.log` を優先する
- 追加する `androidTest` は、通常利用データに依存しないことを前提に書く

## 8. 変更時に確認すべき最小セット

ロジックや永続化を触ったとき:

```bash
bash scripts/test-wsl-unit.sh
```

画面、Service、通知、権限導線を触ったとき:

```powershell
.\scripts\test-windows-instrumented.ps1
```
