# HomePod Bridge

Galaxyのシステムの「メディア出力」からHomePodへ音声を送る、開発中のAndroidアプリとVectorモジュールです。

Apple Musicは変更・フックしません。HomePodをAndroidのメディアルートとして登録し、SystemUIから選択したアプリのメディア音声をAirPlay 2で送ります。マイクは使用しません。

## 対応環境

- Android 16/17、arm64またはx86_64
- rootとVector。モジュールの対象はSystem UIだけです。
- 同じローカルネットワーク上のHomePod／HomePod mini
- HomePod側で同じネットワークからのAirPlay接続を許可していること

## 状態

Galaxy S26 Ultra / Android 17 / One UI 9で、標準のメディア出力一覧への表示、SystemUIでの音声取り込み、HomePod miniからの実際の再生を確認しています。接続直後はミュートから始まり、通常版では0〜100%で音量を調整できます。現在の実機検証版は上限5%です。HomePodのステレオペア、パスワードが必要な受信機、IPv6のみのネットワークは未対応です。

システムの「メディア出力」と、アプリ内部のGoogle Cast専用一覧は別です。Google Cast機器としての偽装は行いません。

## 導入

1. 署名済みrelease APKをインストールします。
2. VectorでHomePod Bridgeを有効にし、対象にSystem UIを指定します。
3. GalaxyReloadでSystemUIを再読み込みします。
4. HomePod Bridgeでローカルネットワークへのアクセスを許可し、音声の共有を開始します。
5. 音楽を再生し、システムの「メディア出力」からHomePodを選びます。
6. 接続直後はミュートです。アプリまたはシステムの音量スライダーで調整してください。VPNによりWi-Fiへ直接接続できない場合は、HomePod Bridgeのroot権限が必要です。

モジュールが使えない場合は、Android標準の音声共有によるApple Music限定の取り込みも実装しています。この場合はシステムの確認画面が表示され、スマートフォンからも音が出る場合があります。

## 開発

JDK 21、Android SDK / Build Tools 37、NDK 30.0.14904198、CMake 4.1.2を使用します。

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
python tools/check_apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

詳細は[開発記録](docs/development.md)と[配布手順](docs/releasing.md)を参照してください。

## ライセンス

本プロジェクトはApache-2.0です。AirPlay 2送信部分は[airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp)を利用しています。上流コミットはthird_party/airplay2-sender-cpp/UPSTREAM.txtに固定し、著作権・ライセンスを保持しています。Mbed TLSは上流指定のコミットに固定しています。アプリの「オープンソースライセンス」からも確認できます。

AirMusicのソースやAPKを流用していません。Apple、Samsung、AirMusic、Vectorの公式アプリではありません。
