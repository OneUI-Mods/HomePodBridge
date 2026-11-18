# HomePod Bridge

[日本語](README.md) · English

An Android app and Vector module, under development, for sending audio from Galaxy's system **Media output** picker to a HomePod.

Apple Music is not modified or hooked. The bridge registers HomePod as an Android media route and sends media audio from the app selected in SystemUI over AirPlay 2. Ordinary sharing does not use the microphone. Only explicitly requested development latency measurements use it briefly; microphone recordings are never forwarded to HomePod.

## First unlock after boot

Hook initialization that needs settings or cached discovery results waits for the first user unlock after boot. A later ordinary screen lock does not repeat initialization. Existing screen-off power-saving and security behavior is preserved. Unlock delivery is event-driven, with no polling or new resident service.

## Requirements

- Android 16/17, arm64 or x86_64.
- Root and Vector; the module scope is System UI only.
- A HomePod / HomePod mini on the same local network.
- HomePod configured to allow AirPlay connections from that network.

## Status

Galaxy S26 Ultra / Android 17 / One UI 9 validation covered connecting and maintaining a connection from the stock media output picker and Apple Music's cast list, and switching back to the phone. Actual HomePod mini playback was also confirmed. Connections start muted, with an adjustable 0–100% volume range. HomePod stereo pairs, receivers requiring passwords, and IPv6-only networks are not supported.

In Apple Music's cast list, the receiver appears as an AndroidX-compatible output. Audio is sent to HomePod over AirPlay 2 without modifying or hooking Apple Music. Support for other apps' cast lists depends on their implementations.

## Setup

1. Install the signed release APK.
2. Enable HomePod Bridge in Vector and select System UI.
3. Reload SystemUI with GalaxyReload.
4. Open HomePod Bridge and allow local-network access.
5. Play music and select HomePod from Apple Music's cast list or system **Media output**.
6. The connection starts muted. Adjust volume in the app or with the system slider. HomePod Bridge needs root permission when a VPN prevents direct Wi-Fi access.

Inside the app, choose **Start audio sharing**, then **Connect** next to a HomePod under destination speakers. Search and destination selection are separate; connected receivers have a check mark. Volume controls are disabled while disconnected.

Preparing a root connection does not automatically fall back to the stock mirroring-permission dialog. If the module is unavailable, explicitly choose **Use standard sharing** from the guidance. This targets Apple Music in selected-app mode, or Android-permitted media/game audio in whole-device mode. The system then asks for confirmation, and audio may also play from the phone.

## Audio modes and latency

Experimental version 0.1.6 organized settings into Material **Connection**, **Audio**, **Adjustments**, and **Diagnostics** tabs. Select a profile under Audio and inspect/edit saved manual values under Adjustments.

- **Standard:** 150 ms pre-send buffer, 10 ms standard capture, and a 1500 ms receiver target.
- **Low latency:** 0 ms pre-send buffer, approximately 0.50 ms dedicated capture, approximately 1.09 ms phone-mixer period, and a 50 ms receiver target. The dedicated path requires a compatible rooted device. It returns to standard capture only after confirming safe shutdown; if shutdown cannot be confirmed, sharing stops.
- **High quality / lossless:** preserves 44.1 kHz, 16-bit stereo, favoring stability with 150 / 10 / 1500 ms settings. Low-latency mode uses the same audio precision; neither mode creates high-resolution audio.
- **Fully manual:** disables preset overrides and automatic buffer adjustment. Edit **Adjustments → Manual settings** and choose **Apply** to reconnect once. Cancel changes nothing. Manual values are stored separately and remain available after choosing another profile.

Manual controls include:

| Setting | Supported range |
| --- | --- |
| Pre-send buffer | 0–300 ms |
| Standard capture block | 2–40 ms |
| Dedicated-path mixer period | 1.09–21.04 ms, rounded to the nearest of 21 supported values; the intended value is shown |
| Dedicated capture wait target | 1.45–18.14 ms (64–800 frames) |
| Standard-path phone buffer target | 0 (disabled), 928, or 1856 frames |
| Frames per packet | 44, 88, or 352; standard/fallback paths use 352 |
| Minimum/maximum receive wait and playback-time offset | Each 10–2000 ms; maximum must not be below minimum |

The same screen selects capture paths and uncompressed or lossless ALAC transport. Compression defaults OFF in every preset; existing saved choices are preserved. Compression preserves audio values, with size reduction depending on the content. The dedicated path shows its fixed approximately 0.50 ms (22-frame) read block.

Diagnostics show queued capture audio separately from the wait target. The average queued amount is not guaranteed to match the configured target. Long mixer periods combined with small wait amounts can increase dropouts. Short packets depend on the receiver and network. Receiver targets below 50 ms, or particular combinations of receiver wait and playback timing, may connect but produce silence. These settings are not total speaker-output latency measurements.

Connection preparation runs in parallel to shorten startup while retaining initial mute. Diagnostics show preparation stages, send errors, retransmissions, and network measurements. While disconnected, only a guidance card is shown. Network-priority settings under Adjustments are per-connection requests whose effect depends on the device/router; the normal setting is the default.

**Selected app** forwards that app's media audio; **Whole device** forwards playback from multiple apps and notification sounds. Microphone and call audio are excluded. Android may prevent some audio from being forwarded. Changes reconnect at 0% volume; the app's volume range is 0–100%.

In whole-device mode, phone-speaker media-volume operations control HomePod while connected. The receiver name appears in both the ordinary volume panel and the details opened through its three-dot menu. Use the receiver's slider in the detailed panel to adjust HomePod. This uses SystemUI's volume-control APIs and restores the original state when sharing stops, without an accessibility service. Some ROMs or combinations with other volume tools may require the app's own slider.

## Development

Use JDK 27, Android SDK / Build Tools 37, NDK 30.0.16248370, and CMake 4.1.2.

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
python tools/check_apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

See the [development record (Japanese)](docs/development.md) and [release guide](docs/releasing.md).

## License

This project is Apache-2.0. AirPlay 2 transmission uses [airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp), with its upstream commit pinned in `third_party/airplay2-sender-cpp/UPSTREAM.txt` and copyright/license notices preserved. Mbed TLS and Apple's ALAC codec are pinned to upstream commits as well. Notices are also available under **Open-source licenses** in the app.

No AirMusic source or APK was reused. This is not an official app from Apple, Samsung, AirMusic, or Vector.

Diagnostics show estimated time since capture, system-to-app transfer time, queued audio, compression time, and send-time headroom. One-way Wi-Fi time, HomePod internals, and total latency are explicitly unmeasured. Buffer capacities and targets are not summed and presented as measured latency.

A startup failure caused by handling omitted settings on the normal-period dedicated audio path has been corrected.

## String compatibility

Splitting empty device-state information now follows the previous behavior again.

## Compatibility after updates

After a target-app update, open this module once to finish preparation, then reopen the target. Discovery results remain in this module's own internal app data and are reused while unchanged. No other OneUI Mods app is required. Features stop applying when a unique compatible target cannot be confirmed. Compatibility with every OS or app update is not guaranteed.

## Track information on HomePod

The bridge reads title, artist, album, artwork, playback position, and duration from the source media session and sends them alongside audio to HomePod. This also supplies playback information when viewing HomePod from an iPhone. Selected-app mode reads only the selected app; whole-device mode uses the playing app. Fields the source app does not publish cannot be displayed.

After updating to 0.2.2, reload SystemUI with GalaxyReload. No additional notification-access or accessibility permission is required. Track information is not collected when using only Android's standard screen-sharing path.

[Build environment / ビルド環境](docs/build-environment.md)
