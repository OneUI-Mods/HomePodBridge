# Kotlin nullable契約の再照合（2026-10-31）

移行前Javaと比較し、正常なnullや省略入力を検証関数へ渡す前に`!!`で停止する経路を修正した。
グローバルに例外を無視したり、認証・入力検証を取り除いたりする修正ではない。

- RootHalInput: timing=nullは短周期を使わない通常の専用経路。CaptureServiceの実際の呼び出しにもnullがある。主コンストラクタへそのまま渡す。
- SystemBridgeProvider: 空の登録BundleはRootConnectorの既存のIllegalArgumentExceptionで拒否する。SystemUIのUID認証は維持する。
- ReadOnlyHalState: オプションHALサービスがないときは既存のSecurityExceptionで拒否する。受理後のmoduleフィールドは非nullのまま維持する。

JVMのServiceManagerフィクスチャは存在しないサービスを返すだけで、実際のReadOnlyHalStateの安全ガードを試す。
[Android実行フィクスチャ](../tools/fixtures/null-contracts/README.md)は配布APKへ入らない。

## 検証の範囲

修正前の署名済みrelease APKで、各該当経路のNullPointerExceptionをAndroid16/17の両方で再現した。
RootHalInputは無効なソース名を渡し、音声・ソケット・rootホスト作成前の既存ガードに到達するかを確認する。
独立したproviderテストは、テストプロセスのBinder呼出元情報だけを一時設定し、終了時に復元する。インストールされたアプリや外部IPCの認証を変更しない。
実機での再生・録画・撮影、全機能の組み合わせ、Android16のSamsung実機の動作保証とは別の確認である。
