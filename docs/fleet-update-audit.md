# 全アプリの更新依存箇所の監査

2026-11-03。Android側の全16プロジェクトを確認した。DexKitの対象は、APK内の型・メソッド・フィールドを直接参照する7本。各アプリは自分の探索・保存・読込を所有し、他のモジュールや管理アプリに依存しない。共通ソースの実装方針は揃えているが、実行時に共有するサービス・保存データはない。

| プロジェクト | 対応 | 確認範囲 |
| --- | --- | --- |
| galaxy-adaptive-statusbar | DexKit | SystemUI、One UIホームのアプリ内型・メンバー |
| OneUIHomeTweaks | DexKit | ホーム、Home Up、SystemUI、Sound Assistant、壁紙設定、開発者設定。既存の呼び出し元探索も世代ごとに保存 |
| GalaxyCaptureTools | DexKit | Samsungキャプチャ、SystemUI。system_serverのフックは既存のOS別契約を維持 |
| GalaxyCameraTools | DexKit | カメラ、Camera Assistant、Expert RAW、SystemUI。Camera2の公開APIと既存の機種・OS契約は維持 |
| DarkAppTools | DexKit | povo、セサミのアプリ内描画型。対応するアプリごとの設定を維持 |
| galaxy-service-panel | DexKit | Service Mode Activity。無線・電話機能そのものの処理は変更しない |
| HomePodBridge | DexKit | SystemUIの音量表示型・メンバー。音声・HAL・ルート接続は既存契約を維持 |
| galaxy-reload | 既存契約を維持 | AndroidのActivityManager、Binder、ActivityThread。対象APKの難読化探索ではない |
| SleepStore | 既存契約を維持 | AndroidのPackageManagerとアプリ自身の入口。対象アプリ内部の難読化名への依存なし |
| legacy-game-tools | 既存契約を維持 | Unityの公開Java入口とIL2CPPのELF。ネイティブアドレスの互換性をDEX探索で保証しない |
| tail-adb | 探索対象なし | Go/ネイティブADB・Tailscaleの接続。アプリ内DEXフックなし |
| OneUIModsManager | 探索対象なし | Android公開API・GitHubリリース・JSONカタログ |
| galaxy-update-guard | 探索対象なし | パッケージの有効状態を操作。対象アプリ内DEXフックなし |
| sim-pilot | 探索対象なし | Telephony/Subscription APIとrootコマンド。アプリ内DEXフックなし |
| galaxy-shutter-mute | 既存契約を維持 | ネイティブ・設定値を扱うモジュール。対象APKの難読化探索ではない |
| galaxy-shutter-mute-manager | 探索対象なし | モジュール設定を管理。アプリ内DEXフックなし |

DexKitの追加だけではOSの非公開API、ネイティブELF、ネットワークプロトコル、リソースの変更まで追従できない。それらの既存ガードを削除して「全更新対応」とはしない。Android 16/17の既存契約を維持し、APK内部の参照に該当する箇所へ探索を適用する。

各リポジトリの`docs/dexkit-compatibility.md`に保存の世代・失敗結果・候補の一意性・クラスローダーの分離を記載する。秘密鍵・パスワード・調査用APK・ROM・端末DB・ユーザーデータは公開しない。
