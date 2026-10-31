# 独立したnull契約の検証

配布APKに含めないKotlinソース。Android SDK 37のandroid.jarでコンパイルし、Kotlin runtimeを同梱したJARをd8でDEXに変換する。Android16/17の専用AOSPエミュレータで使う。撮影・音声再生・録音・設定切り替えは行わない。

NullContractProbeは引数にHome TweaksとHomePod Bridgeの署名済みrelease APKの端末パスを受け取り、PathClassLoaderで実クラスを読む。末尾にbeforeを付けると修正前のNPEを期待する。付けない場合は既存の安全な拒否処理を期待する。

```sh
CLASSPATH=/data/local/tmp/probe.dex app_process / dev.ryuya.nullprobe.NullContractProbe /data/local/tmp/home.apk /data/local/tmp/bridge.apk
```

ProviderProbeはテスト専用APKのInstrumentationとして実行する。カメラ用はpackage/targetPackage=com.sec.android.app.camera、ホーム用はcom.sec.android.app.launcher。Manifestに対象モジュールのqueriesを加え、applicationはtestOnly/debuggableとする。Activityは持たせない。正規のBinder通信を通し、空のzoom_curves_publish/grid_resultがそれぞれIllegalArgumentException/SecurityExceptionで拒否されることを確認する。

**これらのテスト専用ホストは実機へ絶対にインストールしない。** 専用AOSPエミュレータだけを使い、署名済みrelease APK本体とテストAPKを明確に分ける。Samsung製ホストの実描画や音声接続の証明ではない。
