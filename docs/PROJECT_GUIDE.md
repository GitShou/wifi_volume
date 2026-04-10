# wifi_volume プロジェクト探索ガイド

この文書は、Android 開発の知識がほとんどない人が、このプロジェクトを一通り追いかけて全体像をつかむためのガイドです。

目的は次の 3 つです。

1. このプロジェクトが「どんな構造で動いているか」を理解する
2. Android プロジェクト特有の仕組みを、このプロジェクトを題材に理解する
3. どのファイルをどの順番で読めばよいか分かるようにする

このアプリは、Wi-Fi や Bluetooth の接続状況に応じて、端末の音量設定を自動で切り替える Android アプリです。

## 目次

1. [最初に押さえる全体像](#sec-1)
2. [Android プロジェクト特有の構造](#sec-2)
3. [ディレクトリ構造の見方](#sec-3)
4. [実行時の処理フロー](#sec-4)
5. [主要ファイル解説](#sec-5)
6. [AndroidManifest の読み方](#sec-6)
7. [UI 層](#sec-7)
8. [データモデル層](#sec-8)
9. [永続化層](#sec-9)
10. [接続状態取得層](#sec-10)
11. [音量操作層](#sec-11)
12. [バックグラウンド監視層](#sec-12)
13. [リソース層](#sec-13)
14. [その他の XML](#sec-14)
15. [テスト構造の読み方](#sec-15)
16. [このプロジェクトの設計上の特徴](#sec-16)
17. [学習者向けのおすすめ読書順](#sec-17)
18. [このプロジェクトで学べる Android のポイント](#sec-18)
19. [現時点の注意点](#sec-19)
20. [最後に: このプロジェクトを一言で言うと](#sec-20)

<a id="sec-1"></a>
## 1. 最初に押さえる全体像

このアプリの大きな流れは、かなり単純です。

- 画面で設定を作る
- 設定を保存する
- 画面を閉じても常駐監視する
- 接続状況が変わったら、保存済み設定の中から一致するものを探す
- 一致した設定の音量を端末へ適用する

AA 図にするとこうです。

```text
+------------------+
| MainActivity     |
| 画面と入力       |
+--------+---------+
         |
         | 保存
         v
+------------------+
| SettingsRepository |
| DataStore に保存   |
+--------+-----------+
         ^
         | 読み出し
         |
+--------+-----------------------------+
| ConnectionMonitorService             |
| 常駐監視、通知、適用判定             |
+--------+-----------------------------+
         |
         | 現在状態を取得
         +-------------------------------+
         |                               |
         v                               v
+------------------------+    +-------------------------+
| ConnectionStateResolver|    | BluetoothStateResolver |
| Wi-Fi / 非Wi-Fi 判定   |    | 接続中端末の取得       |
+------------------------+    +-------------------------+
         |
         | 一致した設定を適用
         v
+------------------+
| VolumeController |
| AudioManager操作 |
+------------------+
```

<a id="sec-2"></a>
## 2. Android プロジェクト特有の構造

Web アプリや一般的な CLI ツールと違って、Android プロジェクトには独特の分割があります。

### 2-1. Kotlin ファイルだけでは完結しない

Android では、次の 3 種類を組み合わせてアプリを作ります。

- Kotlin / Java のコード
- XML レイアウト
- Manifest / Gradle / リソース定義

このプロジェクトにもそれがそのまま表れています。

```text
app/
  src/main/
    java/...     <- 処理本体
    res/layout/  <- 画面の見た目
    res/values/  <- 文字列、色、寸法、テーマ
    AndroidManifest.xml <- アプリの宣言
```

### 2-2. 画面の入口は `main()` ではない

一般的な Kotlin プログラムなら `main()` が入口ですが、Android は違います。

このアプリの入口は次です。

- [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml)
  - どの Activity を起動対象にするかを宣言
- [`MainActivity.kt`](../app/src/main/java/com/example/wifi_volume/MainActivity.kt)
  - 実際の初期化処理を行う

Manifest にある以下の部分が「この画面を最初に開く」と示しています。

```xml
<activity android:name=".MainActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

### 2-3. 画面を閉じても動かすには Service が必要

Android では、画面が閉じたあとも何かを継続実行したい場合、普通の Activity だけでは足りません。

このアプリでは `Foreground Service` を使っています。

- [`ConnectionMonitorService.kt`](../app/src/main/java/com/example/wifi_volume/monitor/ConnectionMonitorService.kt)

これは Android 特有の重要ポイントです。

- Activity: 画面担当
- Service: バックグラウンド処理担当

この分担を理解すると、このアプリの構造はかなり読みやすくなります。

### 2-4. Android は権限がかなり重要

このアプリは端末状態を読むので、権限が重要です。

現在の Manifest では次の権限を使っています。

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SPECIAL_USE`
- `POST_NOTIFICATIONS`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION`
- `NEARBY_WIFI_DEVICES`

なぜこんなに必要かというと、Android では次のように API ごとに許可が必要だからです。

- Bluetooth 端末一覧を見る
- Wi-Fi 接続状態を見る
- SSID を読む
- 通知を出す
- 常駐サービスを動かす

特に SSID 取得は Android らしい癖が強いです。

- Wi-Fi 接続中でも SSID が常に確実に取れるわけではない
- 位置情報権限や端末側の位置情報 ON/OFF が関係する

このあたりは [`ConnectionStateResolver.kt`](../app/src/main/java/com/example/wifi_volume/network/ConnectionStateResolver.kt) に集約されています。

<a id="sec-3"></a>
## 3. ディレクトリ構造の見方

このプロジェクトで学習者が最初に意識すべき部分だけ抜き出すと、こうなります。

```text
android/                                          <- プロジェクト全体のルート
├─ settings.gradle.kts                            <- Gradle プロジェクト全体の入口
├─ build.gradle.kts                               <- ルート共通のビルド設定
├─ gradle.properties                              <- Gradle の共通実行設定
├─ gradle/libs.versions.toml                      <- 依存ライブラリのバージョン表
├─ app/                                           <- Android アプリ本体のモジュール
│  ├─ build.gradle.kts                            <- アプリ本体のビルド設定
│  ├─ src/main/                                   <- 実際にアプリへ入る本番コードとリソース
│  │  ├─ AndroidManifest.xml                      <- 権限、Activity、Service などの宣言書
│  │  ├─ java/com/example/wifi_volume/    
│  │  │  ├─ MainActivity.kt                       <- メイン画面の処理
│  │  │  ├─ audio/VolumeController.kt             <- 音量の読み取りと適用
│  │  │  ├─ bluetooth/    
│  │  │  │  ├─ BluetoothStateResolver.kt          <- 接続中 Bluetooth 端末の取得
│  │  │  │  └─ ConnectedBluetoothDevice.kt        <- Bluetooth 端末 1 件のデータ型
│  │  │  ├─ data/SettingsRepository.kt            <- 設定保存と復元の窓口
│  │  │  ├─ model/VolumeProfile.kt                <- 設定データ型と判定ルール
│  │  │  ├─ monitor/ConnectionMonitorService.kt   <- 常駐監視と自動切替
│  │  │  └─ network/ConnectionStateResolver.kt    <- Wi-Fi / 非 Wi-Fi 判定と SSID 取得
│  │  ├─ res/layout/    
│  │  │  ├─ activity_main.xml                     <- メイン画面の骨組み
│  │  │  ├─ item_rule_card.xml                    <- 設定カード 1 件ぶんの見た目
│  │  │  └─ item_condition_row.xml                <- 条件一覧の 1 行ぶん
│  │  ├─ res/values/    
│  │  │  ├─ strings.xml                           <- 画面や通知の文言一覧
│  │  │  ├─ colors.xml                            <- アプリで使う色定義
│  │  │  ├─ dimens.xml                            <- 寸法の共通定義
│  │  │  └─ themes.xml                            <- アプリ全体のテーマ定義
│  │  └─ res/xml/   
│  │     ├─ backup_rules.xml                      <- 自動バックアップの設定
│  │     └─ data_extraction_rules.xml             <- 端末移行 / バックアップの補助設定
```

読む優先度としては次の順がよいです。

1. `app/src/main/AndroidManifest.xml`
2. `MainActivity.kt`
3. `activity_main.xml`
4. `VolumeProfile.kt`
5. `SettingsRepository.kt`
6. `ConnectionMonitorService.kt`
7. `ConnectionStateResolver.kt`
8. `BluetoothStateResolver.kt`
9. `VolumeController.kt`
10. `item_rule_card.xml`, `item_condition_row.xml`
11. `strings.xml`, `colors.xml`, `themes.xml`
12. `app/build.gradle.kts`, `libs.versions.toml`

<a id="sec-15"></a>
## 15. テスト構造の読み方

このプロジェクトでは、テストも本番コードと同じくらいレイヤーを意識して分けています。

```text
+----------------------------------+
| app/src/test                     |
| WSL で回す JVM テスト            |
| - model / usecase / data codec   |
| - Robolectric                    |
+----------------+-----------------+
                 |
                 v
+----------------------------------+
| app/src/androidTest              |
| Windows 実機で回す UI/統合テスト |
| - MainActivity                   |
| - Service / 通知 / 権限          |
+----------------+-----------------+
                 |
                 v
+----------------------------------+
| scripts                          |
| - test-wsl-unit.sh               |
| - test-windows-instrumented.ps1  |
+----------------------------------+
```

このとき重要なのは、Windows の実機テストが本番アプリとは別領域で動くことです。

- 通常利用アプリ
  - `com.example.wifi_volume`
- 実機テスト用アプリ
  - `com.example.wifi_volume.e2e`

そのため、通常利用中の設定や `DataStore` を壊さずに、実機で UI テストを流せます。

テストの入口は次です。

- WSL
  - `bash scripts/test-wsl-unit.sh`
- Windows
  - `.\scripts\test-windows-instrumented.ps1`

<a id="sec-4"></a>
## 4. 実行時の処理フロー

### 4-1. アプリ起動時

```text
アプリ起動
  |
  v
MainActivity.onCreate()
  |
  +-- 画面を読み込む
  +-- 各種クラスを初期化する
  +-- 保存済み設定を読む
  +-- 必要な権限を要求する
  +-- ConnectionMonitorService を開始する
```

対応ファイル:

- [`MainActivity.kt`](../app/src/main/java/com/example/wifi_volume/MainActivity.kt)
- [`activity_main.xml`](../app/src/main/res/layout/activity_main.xml)

### 4-2. 設定保存時

```text
保存ボタン押下
  |
  v
画面上の入力値を RuleConfig の一覧に戻す
  |
  v
優先度や fallback 条件を検証する
  |
  v
SettingsRepository に保存
  |
  v
現在の接続状況を取得
  |
  v
RuleEvaluator で一致する設定を選ぶ
  |
  v
VolumeController で即時反映
```

対応ファイル:

- [`MainActivity.kt`](../app/src/main/java/com/example/wifi_volume/MainActivity.kt)
- [`SettingsRepository.kt`](../app/src/main/java/com/example/wifi_volume/data/SettingsRepository.kt)
- [`VolumeProfile.kt`](../app/src/main/java/com/example/wifi_volume/model/VolumeProfile.kt)
- [`VolumeController.kt`](../app/src/main/java/com/example/wifi_volume/audio/VolumeController.kt)

### 4-3. 自動切替時

```text
Wi-Fi / Bluetooth に変化
  |
  v
ConnectionMonitorService がイベントを受ける
  |
  v
現在状態を取得
  |- Wi-Fi 側 -> ConnectionStateResolver
  |- Bluetooth 側 -> BluetoothStateResolver
  |
  v
RuleEvaluator で一致ルールを選ぶ
  |
  +-- 同じ設定なら何もしない場合もある
  +-- 設定変更通知を出す場合もある
  |
  v
VolumeController で音量適用
```

対応ファイル:

- [`ConnectionMonitorService.kt`](../app/src/main/java/com/example/wifi_volume/monitor/ConnectionMonitorService.kt)
- [`ConnectionStateResolver.kt`](../app/src/main/java/com/example/wifi_volume/network/ConnectionStateResolver.kt)
- [`BluetoothStateResolver.kt`](../app/src/main/java/com/example/wifi_volume/bluetooth/BluetoothStateResolver.kt)
- [`VolumeController.kt`](../app/src/main/java/com/example/wifi_volume/audio/VolumeController.kt)

<a id="sec-5"></a>
## 5. 主要ファイル解説

ここからは主要ファイルを 1 つずつ見ます。

### 5-1. [`settings.gradle.kts`](../settings.gradle.kts)

Gradle プロジェクト全体の入り口です。

役割:

- どのリポジトリから依存ライブラリを取るか決める
- どのモジュールをプロジェクトに含めるか決める
- ルートプロジェクト名を決める

このプロジェクトでは:

- `google()`
- `mavenCentral()`
- `gradlePluginPortal()`

を使っています。

また、`include(":app")` によって、実際の Android アプリ本体は `app` モジュールであることが分かります。

### 5-2. [`build.gradle.kts`](../build.gradle.kts)

ルートプロジェクトのビルド設定です。

役割:

- モジュール共通の plugin 設定
- Codex / WSL 用の build ディレクトリ上書き対応

このプロジェクトでは `codexRootBuildDir` が渡された時だけ build ディレクトリを変える仕組みがあります。

これは Android Studio の通常利用を壊さず、WSL 側ビルドだけ一時的に `/tmp` へ逃がせるようにするための工夫です。

### 5-3. [`app/build.gradle.kts`](../app/build.gradle.kts)

アプリ本体のビルド設定です。

ここが Android アプリとしての重要な定義場所です。

主な項目:

- `namespace`
- `applicationId`
- `minSdk`
- `targetSdk`
- `compileSdk`
- 依存ライブラリ

このプロジェクトの特徴:

- `compileSdk = 36`
- `minSdk = 36`
- `targetSdk = 36`

つまり、かなり新しい Android 専用のアプリです。古い端末互換はほぼ考えない設計です。

これは学習者にとって重要です。

- 古い API 互換コードが少ない
- その分、最新 Android の権限や制約をそのまま受ける

### 5-4. [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)

ライブラリのバージョン表です。

役割:

- 依存ライブラリとそのバージョンを 1 か所にまとめる
- `build.gradle.kts` 側を読みやすくする

使っている主な依存:

- `androidx.core-ktx`
- `appcompat`
- `material`
- `activity`
- `constraintlayout`
- `lifecycle-runtime-ktx`
- `datastore-preferences`
- `kotlinx-coroutines-android`

このアプリでは特に次が重要です。

- `material`
  - MaterialButton, MaterialCardView, TabLayout, Slider など UI 部品
- `datastore-preferences`
  - 設定保存
- `coroutines`
  - 非同期処理

<a id="sec-6"></a>
## 6. AndroidManifest の読み方

### 6-1. [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml)

Manifest は Android アプリの「宣言書」です。

ここでは主に次を宣言します。

- このアプリが使う権限
- 最初に起動する画面
- サービスの存在
- アプリアイコンやテーマ

このプロジェクトで学習者がまず見るべき箇所は 3 つです。

#### 権限

```text
ネットワーク確認:
  ACCESS_NETWORK_STATE
  ACCESS_WIFI_STATE

Bluetooth:
  BLUETOOTH_CONNECT

Wi-Fi / SSID:
  ACCESS_FINE_LOCATION
  NEARBY_WIFI_DEVICES

通知 / 常駐:
  POST_NOTIFICATIONS
  FOREGROUND_SERVICE
  FOREGROUND_SERVICE_SPECIAL_USE
```

#### Activity

`MainActivity` がランチャーです。

#### Service

`ConnectionMonitorService` が foreground service です。

ここから「このアプリは画面だけでなく、常駐サービスを持つ」と分かります。

<a id="sec-7"></a>
## 7. UI 層

### 7-1. [`MainActivity.kt`](../app/src/main/java/com/example/wifi_volume/MainActivity.kt)

このアプリで最も大きいファイルです。

主な責務:

- 画面部品の初期化
- 保存済み設定の表示
- ルールカードの動的生成
- 設定追加 / 削除 / 名前変更
- 条件追加 / 条件削除
- 権限要求
- 保存ボタン処理
- 常駐サービス開始

このファイルを読む時は、上から全部読むより、次の順で追うと理解しやすいです。

#### 読む順番

1. `onCreate`
2. `bindViews`
3. `initializeSettings`
4. `renderRules`
5. `bindRuleCard`
6. `saveAndApplySettings`
7. `showAddSettingDialog` / `showAddConditionDialog`
8. `requestPermissionsIfNeeded`
9. `startMonitoringService`

#### UI と内部データの関係

Android の XML レイアウトは見た目だけです。実際のデータは Kotlin 側で持ちます。

このアプリでは、

- 画面で見えている設定一覧
- 実際に保存する前の設定一覧

を `editableRules` で管理しています。

```text
画面上の Slider / EditText / Spinner
   |
   v
syncEditableRulesFromViews()
   |
   v
editableRules : MutableList<RuleConfig>
   |
   v
saveAndApplySettings()
```

これは Android 学習でかなり重要な考え方です。

- XML は状態を持たない
- Activity 側が「いまの状態」を集め直して保存する

このプロジェクトでは ViewBinding ではなく `findViewById` で書いているので、学習者が Android の基本構造を追いやすいです。

### 7-2. [`activity_main.xml`](../app/src/main/res/layout/activity_main.xml)

画面全体のレイアウトです。

主な構成:

- タイトル
- 現在適用中のステータス表示
- タブ
  - 音量設定
  - 全体設定
- 上部操作ボタン
  - 設定を追加
  - 設定を削除
- 設定カード一覧コンテナ
- 全体設定カード
  - 再適用モード
  - 切替通知 ON/OFF
  - 権限再確認
- 保存ボタン

Android の XML は入れ子が深くなりやすいですが、やっていることは単に「縦に並べる」「横に並べる」です。

### 7-3. [`item_rule_card.xml`](../app/src/main/res/layout/item_rule_card.xml)

設定 1 件ぶんの見た目です。

中にあるもの:

- 設定名
- 説明文
- 優先度
- 条件一覧
- 条件追加ボタン
- 音量スライダー 4 本
- 着信モード Spinner
- 設定名変更 / 削除ボタン

この XML は固定1回しか表示されるわけではなく、`MainActivity.renderRules()` がルール数ぶん複製します。

### 7-4. [`item_condition_row.xml`](../app/src/main/res/layout/item_condition_row.xml)

条件一覧の 1 行ぶんです。

表示内容:

- 条件名
- 条件削除ボタン

これはリストの最小単位です。

<a id="sec-8"></a>
## 8. データモデル層

### 8-1. [`VolumeProfile.kt`](../app/src/main/java/com/example/wifi_volume/model/VolumeProfile.kt)

名前は `VolumeProfile.kt` ですが、実際にはモデル定義全体が入っています。

入っている主な型:

- `RingerModeOption`
- `VolumeProfile`
- `ReapplyMode`
- `RuleConditionType`
- `RuleCondition`
- `RuleConfig`
- `AppSettings`
- `ActiveRuleState`
- `VolumeLimits`
- `DeviceState`
- `RuleEvaluator`

#### 特に重要な型

##### `VolumeProfile`

1つの音量設定そのものです。

```text
media
ring
notification
alarm
ringerMode
```

##### `RuleCondition`

ある設定が有効になる条件の 1 件です。

種類:

- `WIFI_ANY`
- `WIFI_SSID`
- `BLUETOOTH_ANY`
- `BLUETOOTH_DEVICE`

##### `RuleConfig`

画面に見えている「設定カード 1 枚」がこれです。

含むもの:

- ID
- 設定名
- 優先度
- 条件一覧
- 音量設定
- fallback かどうか

##### `AppSettings`

アプリ全体で保存する設定です。

含むもの:

- 設定カード一覧
- 再適用モード
- 切替通知 ON/OFF

#### `RuleEvaluator` の意味

このアプリの判定ロジックの中心です。

考え方:

- 優先度順に設定を並べる
- その設定の条件一覧を OR 判定する
- 最初に一致した設定を採用する
- どれにも一致しなければ `その他` を採用する

AA 図:

```text
設定1 (priority 1)
  |- 条件A
  |- 条件B
  -> A or B が true なら採用

設定2 (priority 2)
  |- 条件C
  -> C が true なら採用

その他
  -> 最後の fallback
```

<a id="sec-9"></a>
## 9. 永続化層

### 9-1. [`SettingsRepository.kt`](../app/src/main/java/com/example/wifi_volume/data/SettingsRepository.kt)

設定保存の窓口です。

Android では SharedPreferences を使う例も多いですが、このプロジェクトでは `DataStore` を使っています。

#### 何を保存しているか

- 設定全体の JSON
- 現在適用中の設定 ID
- 現在適用中の設定ラベル

#### なぜ JSON なのか

`RuleConfig` はネストした構造です。

```text
AppSettings
  └─ rules: List<RuleConfig>
       └─ conditions: List<RuleCondition>
```

`Preferences DataStore` は単純な key-value 保存が基本なので、そのままでは複雑なリスト構造を保存しづらいです。

そこでこのプロジェクトでは、

- `AppSettings` を JSON 文字列に変換
- DataStore にはその文字列を保存

という形をとっています。

これは Android 学習上かなり参考になる実装です。

#### 後方互換

このファイルには旧保存形式からの移行処理もあります。

重要なのは次の 2 つです。

- `migrateOrCreateDefaults`
- `decodeLegacyCondition`

昔の固定構成

- Bluetooth
- Wi-Fi
- Mobile

から、今の可変ルール形式へ移行できるようにしています。

また、旧 `MOBILE_DEFAULT` 文字列も読めるようにしています。

これは「アプリを更新した時に、既存ユーザーの設定を壊さない」ための Android アプリらしい対応です。

<a id="sec-10"></a>
## 10. 接続状態取得層

### 10-1. [`ConnectionStateResolver.kt`](../app/src/main/java/com/example/wifi_volume/network/ConnectionStateResolver.kt)

Wi-Fi / 非 Wi-Fi の判定と、現在の SSID 取得を担当します。

#### 何を返すか

- `ConnectionState.WIFI`
- `ConnectionState.NON_WIFI`
- `currentWifiSsid: String?`

#### なぜ単純でないのか

Android では「Wi-Fi につながっている」と「SSID が読める」は別問題です。

SSID 取得には次の条件が影響します。

- `ACCESS_FINE_LOCATION`
- `NEARBY_WIFI_DEVICES`
- 端末の位置情報が ON か

そのため、このファイルでは次をまとめて見ています。

- `ConnectivityManager`
- `NetworkCapabilities`
- `WifiManager`
- `LocationManager`

#### フォールバック

まず `NetworkCapabilities.transportInfo as WifiInfo` を見る  
取れなければ `WifiManager.connectionInfo` を見る

という 2 段構えです。

これは Android 特有の「端末状態 API が一筋縄ではいかない」例として良い教材です。

### 10-2. [`BluetoothStateResolver.kt`](../app/src/main/java/com/example/wifi_volume/bluetooth/BluetoothStateResolver.kt)

Bluetooth 接続中端末一覧を取得します。

#### なぜ profile ごとに見ているのか

Android の Bluetooth API は少し扱いが面倒です。

ヘッドホンや LE Audio などが profile ごとに分かれているため、次を順に見ています。

- `A2DP`
- `HEADSET`
- `HEARING_AID`
- `LE_AUDIO`

このファイルがやっていること:

- profile ごとの proxy を取得
- 接続中端末一覧を集める
- 重複アドレスをまとめる

### 10-3. [`ConnectedBluetoothDevice.kt`](../app/src/main/java/com/example/wifi_volume/bluetooth/ConnectedBluetoothDevice.kt)

Bluetooth 端末 1 件ぶんのデータです。

内容:

- `name`
- `address`
- `displayName`

Android の API は返り値がそのまま UI に向かないことが多いので、このような中間型を置くのはよくある整理方法です。

<a id="sec-11"></a>
## 11. 音量操作層

### 11-1. [`VolumeController.kt`](../app/src/main/java/com/example/wifi_volume/audio/VolumeController.kt)

音量の読み取りと適用だけを担当します。

やっていること:

- 現在音量の読み取り
- 各ストリーム最大値の読み取り
- 保存済み設定の適用

このファイルの良い点は、`AudioManager` への直接アクセスをここに閉じ込めていることです。

その結果:

- 画面側は「音量設定」という抽象的なデータだけを扱えばよい
- サービス側も「適用したいプロファイル」を渡すだけでよい

これは責務分離の分かりやすい例です。

<a id="sec-12"></a>
## 12. バックグラウンド監視層

### 12-1. [`ConnectionMonitorService.kt`](../app/src/main/java/com/example/wifi_volume/monitor/ConnectionMonitorService.kt)

このアプリの自動切替の中心です。

#### 何をしているか

- foreground service として起動
- ネットワーク callback 登録
- Bluetooth の broadcast receiver 登録
- watchdog 定期再評価
- 評価結果に応じた音量適用
- 常駐通知更新
- 設定切替通知

#### Android 的に重要な点

##### Foreground Service

画面を閉じても監視し続けるために必要です。

ただし Android では、foreground service を使うと常駐通知が必要です。

このアプリで通知が常に出るのはそのためです。

##### Broadcast / Callback ベース

Android は「状態を毎秒ポーリングする」より、「イベントを受け取る」構造が多いです。

このファイルでも、

- `registerDefaultNetworkCallback`
- Bluetooth の `BroadcastReceiver`

を使っています。

##### Watchdog

理想はイベントだけで十分ですが、実端末ではイベント取りこぼしや遅延もあります。

そのためこのアプリでは 10 秒ごとの再評価も入れています。

これは現実的な Android 実装です。

#### 特殊な補正

`retainPreviousWifiRuleIfNeeded` という補正があります。

これは、

- Wi-Fi にはつながっている
- でも一時的に SSID が読めない
- その瞬間だけ fallback に落ちてしまう

という Android 端末側の揺らぎを吸収するためのものです。

このような「OS の揺らぎ補正」は、モバイル開発でよく出てきます。

<a id="sec-13"></a>
## 13. リソース層

### 13-1. [`strings.xml`](../app/src/main/res/values/strings.xml)

表示文言の管理です。

ここに入っているもの:

- 画面文言
- ボタン文言
- ダイアログ文言
- 通知文言
- トースト文言

Android では文字列をコードへ直接書かず、ここへ集めるのが基本です。

理由:

- 文言修正がしやすい
- 多言語対応しやすい
- XML と Kotlin の両方から再利用できる

### 13-2. [`colors.xml`](../app/src/main/res/values/colors.xml)

色定義です。

今は主に、

- 追加ボタンの緑
- 削除ボタンの赤

を管理しています。

### 13-3. [`dimens.xml`](../app/src/main/res/values/dimens.xml)

寸法定義です。

現時点では、適用中カードの枠線太さだけを持っています。

### 13-4. [`themes.xml`](../app/src/main/res/values/themes.xml)

アプリ全体のテーマです。

このプロジェクトでは Material3 ベースです。

初心者向け補足:

- Android は CSS のように見た目を全部インラインで書かない
- テーマで全体方針を決めて、各 XML はそれに乗る

<a id="sec-14"></a>
## 14. その他の XML

### 14-1. [`backup_rules.xml`](../app/src/main/res/xml/backup_rules.xml)

自動バックアップの制御用です。

今は Android Studio 初期生成のサンプルに近い状態で、特別な設定はしていません。

### 14-2. [`data_extraction_rules.xml`](../app/src/main/res/xml/data_extraction_rules.xml)

Android 12 以降のバックアップ / 端末移行制御用です。

これも今はほぼ初期状態です。

つまり、このアプリの本体理解には必須ではありませんが、「Android プロジェクトにはこういう補助 XML もある」と知っておくとよいです。

<a id="sec-16"></a>
## 16. このプロジェクトの設計上の特徴

このアプリは Android プロジェクトとして見ると、次のような特徴があります。

### 16-1. ViewModel を使わず Activity 中心

最近の Android では ViewModel や Compose を使う例も多いですが、このプロジェクトは違います。

- UI は XML
- 画面ロジックは Activity
- `findViewById` で View を取得

学習用としてはむしろ分かりやすいです。

### 16-2. DataStore は使うが、データ層は薄い

Repository はあるものの、Room や DB は使っていません。

理由:

- 保存したいのは複雑な一覧ではあるが、件数が少ない
- ローカル個人用アプリである

そのため、JSON + DataStore で十分です。

### 16-3. ルール判定を `RuleEvaluator` に寄せている

UI や Service に判定ロジックを書き散らさず、モデル層に寄せています。

このおかげで、

- 判定の修正点が集まりやすい
- 保存・表示・監視を分離できる

という利点があります。

### 16-4. Android の不安定さを現実的に吸収している

例:

- SSID 取得失敗
- Bluetooth 切断直後の状態揺れ
- イベント取りこぼし対策としての watchdog

これは教科書的というより、現実の端末挙動を踏まえた設計です。

<a id="sec-17"></a>
## 17. 学習者向けのおすすめ読書順

Android 未経験なら、この順で読むのがおすすめです。

### ステップ1: まず Android の入口を知る

1. [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml)
2. [`MainActivity.kt`](../app/src/main/java/com/example/wifi_volume/MainActivity.kt)
3. [`activity_main.xml`](../app/src/main/res/layout/activity_main.xml)

ここで、

- どの画面が最初に開くか
- 画面は XML で作ること
- 画面ロジックは Activity にあること

を理解します。

### ステップ2: データの形を知る

4. [`VolumeProfile.kt`](../app/src/main/java/com/example/wifi_volume/model/VolumeProfile.kt)
5. [`SettingsRepository.kt`](../app/src/main/java/com/example/wifi_volume/data/SettingsRepository.kt)

ここで、

- 設定 1 件とは何か
- 条件とは何か
- アプリ全体の保存形式はどうなっているか

を理解します。

### ステップ3: 自動切替の本体を知る

6. [`ConnectionMonitorService.kt`](../app/src/main/java/com/example/wifi_volume/monitor/ConnectionMonitorService.kt)
7. [`ConnectionStateResolver.kt`](../app/src/main/java/com/example/wifi_volume/network/ConnectionStateResolver.kt)
8. [`BluetoothStateResolver.kt`](../app/src/main/java/com/example/wifi_volume/bluetooth/BluetoothStateResolver.kt)
9. [`VolumeController.kt`](../app/src/main/java/com/example/wifi_volume/audio/VolumeController.kt)

ここで、

- バックグラウンド監視
- 接続状態取得
- 音量適用

の流れを理解します。

### ステップ4: 画面部品を知る

10. [`item_rule_card.xml`](../app/src/main/res/layout/item_rule_card.xml)
11. [`item_condition_row.xml`](../app/src/main/res/layout/item_condition_row.xml)
12. [`strings.xml`](../app/src/main/res/values/strings.xml)

ここで、UI 部品の分割と Android の resource 管理を理解できます。

<a id="sec-18"></a>
## 18. このプロジェクトで学べる Android のポイント

このプロジェクトは、次の学習題材としてかなり良いです。

- Activity ベースの画面実装
- XML レイアウト
- Material Components
- DataStore による設定保存
- Runtime Permission
- Foreground Service
- Notification
- ConnectivityManager
- Bluetooth API
- AudioManager
- リソース分離 (`strings.xml`, `colors.xml`)

つまり、「Android アプリの基本要素が一通り入っているが、規模はまだ追える」状態です。

<a id="sec-19"></a>
## 19. 現時点の注意点

学習者向けに、今の実装で意識すべき注意点も書いておきます。

### 19-1. `MainActivity.kt` は大きい

実務では将来的に分割候補です。

例えば:

- 画面操作
- ダイアログ生成
- ルール編集ロジック
- 権限ロジック

を別クラスへ分ける余地があります。

ただし学習段階では、1 ファイルにまとまっている方が追いやすい利点もあります。

### 19-2. `minSdk = 36`

かなり最新 Android 限定です。

古い Android 互換を学びたい教材ではありません。

その代わり、最新権限モデルや foreground service 制約を学ぶには向いています。

### 19-3. Compose ではない

今の Android 学習では Jetpack Compose をよく見ますが、このアプリは XML ベースです。

ただし Android の基礎理解には XML ベースの方が役立つ場面も多いです。

<a id="sec-20"></a>
## 20. 最後に: このプロジェクトを一言で言うと

このプロジェクトは、

> 「設定画面を持つ Android アプリ」と「画面を閉じても動く常駐監視サービス」を組み合わせた、接続条件ベースの音量自動切替アプリ

です。

学習者としては、次の対応関係を頭に入れると一気に分かりやすくなります。

```text
画面                -> MainActivity + XML
保存                -> SettingsRepository + DataStore
判定ロジック        -> RuleEvaluator
Wi-Fi 取得          -> ConnectionStateResolver
Bluetooth 取得      -> BluetoothStateResolver
音量変更            -> VolumeController
バックグラウンド監視 -> ConnectionMonitorService
宣言と権限          -> AndroidManifest.xml
```

この対応関係が見えたら、プロジェクト全体の構造はかなり掴めています。
